//! Engine worker thread and JVM-facing handle.
//!
//! The `typebit::Engine` is not thread-safe and must be driven from a single
//! thread. This module owns that thread: the JNI layer sends [`Cmd`] messages
//! over an mpsc channel, and the worker replies through one-shot channels
//! embedded in each command. Events are drained every tick, serialized to
//! JSON and pushed into a shared queue that Kotlin polls.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use typebit::engine::Engine;
use typebit::platform::NetAddr;
use typebit::session::SessionConfig;
use typebit::{EngineConfig, EngineEvent, Host, InfoHash};

use crate::host::{LogBuffer, NativeHost};
use crate::json::JsonWriter;
use crate::leech;
use crate::meta::MetaRegistry;

/// Engine tick cadence (ms). Chosen to balance CPU and UI responsiveness.
pub const TICK_MS: u64 = 100;

/// Event queue shared with the JVM (polled via `nativeTakeEvents`).
pub type EventQueue = Arc<Mutex<VecDeque<String>>>;

/// A command submitted from the JNI layer to the engine thread.
pub enum Cmd {
    AddTorrent {
        data: Vec<u8>,
        save_dir: String,
        tx: Sender<Result<String, String>>,
    },
    AddMagnet {
        uri: String,
        save_dir: String,
        tx: Sender<Result<String, String>>,
    },
    Start {
        hash: String,
        tx: Sender<Result<(), String>>,
    },
    Pause {
        hash: String,
    },
    Resume {
        hash: String,
    },
    Remove {
        hash: String,
        tx: Sender<Result<(), String>>,
    },
    Progress {
        hash: String,
        tx: Sender<f64>,
    },
    Downloaded {
        hash: String,
        tx: Sender<u64>,
    },
    IsComplete {
        hash: String,
        tx: Sender<bool>,
    },
    TorrentInfo {
        hash: String,
        tx: Sender<Option<String>>,
    },
    TorrentStates {
        tx: Sender<String>,
    },
    /// One batched snapshot for the whole UI poll tick: per-torrent runtime
    /// stats + meta essentials + DHT count, all in a single JSON response.
    /// This collapses what used to be 4N+3 blocking JNI round-trips into one.
    Snapshot {
        tx: Sender<String>,
    },
    TorrentCount {
        tx: Sender<usize>,
    },
    DhtCount {
        tx: Sender<usize>,
    },
    PeerId {
        tx: Sender<String>,
    },
    SetLimits {
        down: u64,
        up: u64,
    },
    SetSessionConfig {
        cfg: SessionConfig,
    },
    /// Global wire counters (down_total, up_total) from the host limiter.
    Totals {
        tx: Sender<(u64, u64)>,
    },
    SaveState {
        tx: Sender<Option<Vec<u8>>>,
    },
    LoadState {
        data: Vec<u8>,
    },
    Shutdown {
        tx: Sender<()>,
    },
}

/// Opaque handle handed to the JNI layer.
pub struct EngineHandle {
    cmd_tx: Sender<Cmd>,
    pub events: EventQueue,
    pub logs: LogBuffer,
}

impl EngineHandle {
    /// Fire a command without waiting (pause/resume/limits/…).
    pub fn send(&self, cmd: Cmd) {
        let _ = self.cmd_tx.send(cmd);
    }

    /// Fire a command and wait for its reply (bounded by `timeout`).
    pub fn request<T>(&self, cmd: Cmd, rx: Receiver<T>, timeout: Duration) -> Option<T> {
        if self.cmd_tx.send(cmd).is_err() {
            return None;
        }
        rx.recv_timeout(timeout).ok()
    }

    pub fn shutdown(&self) {
        let (tx, rx) = channel();
        self.send(Cmd::Shutdown { tx });
        let _ = rx.recv_timeout(Duration::from_secs(2));
    }
}

/// Spawn the engine worker thread. `config_json` is the JSON blob produced by
/// the Kotlin side (see `parse_config`). Returns a handle or a string error.
pub fn spawn_engine(
    config_json: &str,
    save_dir: &str,
    logs: LogBuffer,
) -> Result<EngineHandle, String> {
    let (cfg, _session_cfg) = parse_config(config_json, save_dir)?;
    let (cmd_tx, cmd_rx) = channel::<Cmd>();
    let events: EventQueue = Arc::new(Mutex::new(VecDeque::new()));
    let events_worker = events.clone();
    let logs_worker = logs.clone();

    std::thread::Builder::new()
        .name("typebit-engine".to_string())
        .spawn(move || run_loop(cfg, logs_worker, cmd_rx, events_worker))
        .map_err(|e| format!("failed to spawn engine thread: {e}"))?;

    Ok(EngineHandle {
        cmd_tx,
        events,
        logs,
    })
}

/// The engine thread's main loop.
fn run_loop(engine_cfg: EngineConfig, logs: LogBuffer, cmd_rx: Receiver<Cmd>, events: EventQueue) {
    let down_limit = Arc::new(AtomicU64::new(0));
    let up_limit = Arc::new(AtomicU64::new(0));
    let mut host = NativeHost::new(down_limit.clone(), up_limit.clone(), logs.clone());
    // Bind TCP now so the actual port (possibly ephemeral) is fixed before
    // the first announce. The engine advertises engine_cfg.listen_port; if
    // binding fell back, we log a warning (see host.rs).
    host.bind_tcp(engine_cfg.listen_port);

    let mut engine = Engine::new(host, engine_cfg);
    let mut meta = MetaRegistry::new();
    let mut running = true;

    while running {
        // 1) Drain commands.
        while let Ok(cmd) = cmd_rx.try_recv() {
            if let Some(stop) =
                handle_cmd(&mut engine, &mut meta, cmd, &events, &down_limit, &up_limit)
            {
                running = !stop;
                break;
            }
        }
        if !running {
            break;
        }

        // 2) Inbound accepts + completed outbound connects.
        let accepted = engine.host.accept_pending();
        engine.host.drain_established();
        for (conn, addr) in accepted {
            engine.on_inbound_connection(conn, addr);
        }

        // 3) Advance the whole engine.
        let _ = engine.tick();

        // 4) Serialize events into the shared queue (one object per event).
        let evs = engine.take_events();
        if !evs.is_empty() {
            // Keep the mirror in sync: metadata arriving for a magnet, and
            // run anti-leech detection on every peer connection.
            for ev in &evs {
                if let EngineEvent::MetadataComplete { info_hash } = ev {
                    meta.mark_metadata_ready(&info_hash.to_hex());
                }
                if let EngineEvent::PeerConnected { peer_id, addr, .. } = ev {
                    if let Some(name) = leech::detect_leech(peer_id) {
                        let a = addr_string(addr);
                        engine.host.log(
                            typebit::platform::LogLevel::Warn,
                            &format!("anti-leech: {name} ({a}) is a known leeching client"),
                        );
                        if let Ok(mut q) = events.lock() {
                            q.push_back(anti_leech_json(name, &a));
                        }
                    }
                }
            }
            if let Ok(mut q) = events.lock() {
                for ev in &evs {
                    q.push_back(event_to_json(ev));
                }
                // Bounded queue: drop oldest on overflow.
                while q.len() > 2048 {
                    q.pop_front();
                }
            }
        }

        std::thread::sleep(Duration::from_millis(TICK_MS));
    }

    engine.host.shutdown();
}

/// Handle one command. Returns `Some(true)` when the loop must stop.
fn handle_cmd(
    engine: &mut Engine<NativeHost>,
    meta: &mut MetaRegistry,
    cmd: Cmd,
    events: &EventQueue,
    down_limit: &AtomicU64,
    up_limit: &AtomicU64,
) -> Option<bool> {
    match cmd {
        Cmd::AddTorrent { data, save_dir, tx } => {
            let res = engine.add_torrent(&data, &save_dir).map(|h| h.to_hex());
            let res = match res {
                Ok(hex) => {
                    if let Ok(t) = typebit::metainfo::Torrent::from_bytes(&data) {
                        meta.register(&t);
                    }
                    Ok(hex)
                }
                Err(e) => {
                    let msg = format!("add_torrent failed: {} ({} bytes)", e.tag(), data.len());
                    engine.host.log(typebit::platform::LogLevel::Warn, &msg);
                    Err(e.tag().to_string())
                }
            };
            let _ = tx.send(res);
        }
        Cmd::AddMagnet { uri, save_dir, tx } => {
            let res = engine.add_magnet(&uri, &save_dir).map(|h| h.to_hex());
            let res = match res {
                Ok(hex) => {
                    let name = typebit::magnet::Magnet::parse(&uri)
                        .ok()
                        .and_then(|m| m.name)
                        .unwrap_or_else(|| "magnet".to_string());
                    meta.register_magnet(&hex, &name);
                    Ok(hex)
                }
                Err(e) => {
                    let msg = format!("add_magnet failed: {}", e.tag());
                    engine.host.log(typebit::platform::LogLevel::Warn, &msg);
                    Err(e.tag().to_string())
                }
            };
            let _ = tx.send(res);
        }
        Cmd::Start { hash, tx } => {
            let res = match InfoHash::from_hex(&hash) {
                Ok(h) => engine.start(&h).map_err(|e| e.tag().to_string()),
                Err(_) => Err("invalid hash".to_string()),
            };
            let _ = tx.send(res);
        }
        Cmd::Pause { hash } => {
            if let Ok(h) = InfoHash::from_hex(&hash) {
                engine.pause(&h);
            }
        }
        Cmd::Resume { hash } => {
            if let Ok(h) = InfoHash::from_hex(&hash) {
                engine.resume(&h);
            }
        }
        Cmd::Remove { hash, tx } => {
            let res = match InfoHash::from_hex(&hash) {
                Ok(h) => engine.remove_torrent(&h).map_err(|e| e.tag().to_string()),
                Err(_) => Err("invalid hash".to_string()),
            };
            meta.remove(&hash);
            let _ = tx.send(res);
        }
        Cmd::Progress { hash, tx } => {
            let v = InfoHash::from_hex(&hash)
                .map(|h| engine.progress(&h))
                .unwrap_or(0.0);
            let _ = tx.send(v);
        }
        Cmd::Downloaded { hash, tx } => {
            let v = InfoHash::from_hex(&hash)
                .map(|h| engine.downloaded(&h))
                .unwrap_or(0);
            let _ = tx.send(v);
        }
        Cmd::IsComplete { hash, tx } => {
            let v = InfoHash::from_hex(&hash)
                .map(|h| engine.is_complete(&h))
                .unwrap_or(false);
            let _ = tx.send(v);
        }
        Cmd::TorrentInfo { hash, tx } => {
            let v = meta.json_for(&hash);
            let _ = tx.send(v);
        }
        Cmd::TorrentStates { tx } => {
            let st = engine.save_state();
            let mut w = JsonWriter::new();
            w.begin_array();
            for (i, t) in st.torrents.iter().enumerate() {
                if i > 0 {
                    w.comma();
                }
                w.begin_object();
                w.kv_string("hash", &hex_of(&t.info_hash));
                w.kv_string("save_path", &t.save_path);
                w.kv_u64("have", count_bits(&t.have));
                w.kv_string("hx", &hex_of(&t.have));
                w.kv_bool("paused", t.paused);
                w.end_object();
            }
            w.end_array();
            let _ = tx.send(w.into_string());
        }
        Cmd::Snapshot { tx } => {
            // One batched response for the whole UI poll tick. Iterating the
            // saved state keeps the paused/have data authoritative, while the
            // per-session queries are cheap in-process lookups (no JNI).
            let st = engine.save_state();
            let dht = engine.dht().map(|d| d.table().size()).unwrap_or(0);
            let (d_total, u_total) = engine.host.totals();
            let mut w = JsonWriter::new();
            w.begin_object();
            w.kv_u64("dht", dht as u64);
            w.comma();
            w.key("totals");
            w.begin_object();
            w.kv_u64("d", d_total);
            w.comma();
            w.kv_u64("u", u_total);
            w.end_object();
            w.comma();
            w.key("torrents");
            w.begin_array();
            for (i, t) in st.torrents.iter().enumerate() {
                if i > 0 {
                    w.comma();
                }
                let hash = hex_of(&t.info_hash);
                let ih = InfoHash::from_hex(&hash).ok();
                let progress = ih.as_ref().map(|h| engine.progress(h)).unwrap_or(0.0);
                let downloaded = ih.as_ref().map(|h| engine.downloaded(h)).unwrap_or(0);
                let complete = ih.as_ref().map(|h| engine.is_complete(h)).unwrap_or(false);
                let (name, size, pieces, meta_ready) = meta
                    .get(&hash)
                    .map(|m| {
                        (
                            m.name.clone(),
                            m.size,
                            m.piece_count as u64,
                            m.metadata_ready,
                        )
                    })
                    .unwrap_or_else(|| (String::new(), 0, 0, false));
                w.begin_object();
                w.kv_string("h", &hash);
                w.comma();
                w.kv_f64("p", progress);
                w.comma();
                w.kv_u64("d", downloaded);
                w.comma();
                w.kv_bool("c", complete);
                w.comma();
                w.kv_bool("paused", t.paused);
                w.comma();
                w.kv_u64("have", count_bits(&t.have));
                w.comma();
                w.kv_string("hx", &hex_of(&t.have));
                w.comma();
                w.kv_string("name", &name);
                w.comma();
                w.kv_u64("size", size);
                w.comma();
                w.kv_u64("pieces", pieces);
                w.comma();
                w.kv_bool("meta", meta_ready);
                w.end_object();
            }
            w.end_array();
            w.end_object();
            let _ = tx.send(w.into_string());
        }
        Cmd::TorrentCount { tx } => {
            let _ = tx.send(engine.torrent_count());
        }
        Cmd::DhtCount { tx } => {
            let n = engine.dht().map(|d| d.table().size()).unwrap_or(0);
            let _ = tx.send(n);
        }
        Cmd::PeerId { tx } => {
            let pid = engine
                .peer_id()
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>();
            let _ = tx.send(pid);
        }
        Cmd::SetLimits { down, up } => {
            down_limit.store(down, Ordering::Relaxed);
            up_limit.store(up, Ordering::Relaxed);
        }
        Cmd::SetSessionConfig { cfg } => {
            // Applies to torrents added from now on (0.1.0 has no runtime
            // per-session mutation — see README).
            engine.cfg.session = cfg;
        }
        Cmd::Totals { tx } => {
            let totals = engine.host.totals();
            let _ = tx.send(totals);
        }
        Cmd::SaveState { tx } => {
            let st = engine.save_state();
            let bytes = st.to_binary().ok();
            let _ = tx.send(bytes);
        }
        Cmd::LoadState { data } => {
            if let Ok(st) = typebit::state::SessionState::from_binary(&data) {
                let now = engine.host.now_ms();
                engine.load_state(&st, now);
            }
        }
        Cmd::Shutdown { tx } => {
            let _ = tx.send(());
            return Some(true);
        }
    }
    // MetadataComplete events flip the `metadata_ready` flag on the mirror.
    let _ = events;
    None
}

// ---------- helpers ----------

fn hex_of(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn count_bits(bytes: &[u8]) -> u64 {
    bytes.iter().map(|b| b.count_ones() as u64).sum()
}

/// Serialize one engine event into a JSON object string.
fn event_to_json(ev: &EngineEvent) -> String {
    let mut w = JsonWriter::new();
    w.begin_object();
    match ev {
        EngineEvent::PeerConnected {
            info_hash,
            addr,
            peer_id,
        } => {
            w.kv_u64("t", 1);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
            w.comma();
            w.kv_string("a", &addr_string(addr));
            w.comma();
            w.kv_string("p", &hex_of(peer_id));
        }
        EngineEvent::PieceVerified { info_hash, piece } => {
            w.kv_u64("t", 2);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
            w.comma();
            w.kv_u64("piece", *piece as u64);
        }
        EngineEvent::HashFailure { info_hash, piece } => {
            w.kv_u64("t", 3);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
            w.comma();
            w.kv_u64("piece", *piece as u64);
        }
        EngineEvent::TorrentComplete { info_hash } => {
            w.kv_u64("t", 4);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
        }
        EngineEvent::MetadataComplete { info_hash } => {
            w.kv_u64("t", 5);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
        }
        EngineEvent::MetadataFailed { info_hash } => {
            w.kv_u64("t", 6);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
        }
        EngineEvent::TrackerAnnounced { info_hash, peers } => {
            w.kv_u64("t", 7);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
            w.comma();
            w.kv_u64("peers", *peers as u64);
        }
        EngineEvent::DhtNodeCount(n) => {
            w.kv_u64("t", 8);
            w.comma();
            w.kv_u64("n", *n as u64);
        }
        // `EngineEvent` is `#[non_exhaustive]` (forward-compatible enum).
        _ => {}
    }
    w.end_object();
    w.into_string()
}

fn addr_string(a: &NetAddr) -> String {
    a.to_alloc_string()
}

/// Anti-leech detection event (t=9): a known leeching client connected.
fn anti_leech_json(client: &str, addr: &str) -> String {
    let mut w = JsonWriter::new();
    w.begin_object();
    w.kv_u64("t", 9);
    w.comma();
    w.kv_string("c", client);
    w.comma();
    w.kv_string("a", addr);
    w.end_object();
    w.into_string()
}

// ---------- config parsing ----------

/// Parse the engine configuration JSON into `(EngineConfig, SessionConfig)`.
///
/// The blob is produced by the Kotlin `EngineConfigJson` serializer; every
/// key is optional and validated with safe defaults. Unknown keys are
/// ignored so forward/backward config compatibility is trivial.
pub fn parse_config(json: &str, save_dir: &str) -> Result<(EngineConfig, SessionConfig), String> {
    use nextjson::Value;

    let root: Value =
        nextjson::nextdecode(json.as_bytes()).map_err(|e| format!("bad config json: {e}"))?;

    let num = |key: &str, default: u64| -> u64 {
        root.get(key).and_then(Value::as_u64).unwrap_or(default)
    };
    let flag = |key: &str, default: bool| -> bool {
        root.get(key).and_then(Value::as_bool).unwrap_or(default)
    };

    let listen_port = num("listen_port", typebit::consts::DEFAULT_PORT as u64) as u16;
    let cache_bytes = num("cache_bytes", typebit::consts::DEFAULT_CACHE_BYTES);
    let dht_enabled = flag("dht_enabled", true);

    let mut session = parse_session_fields(&root, save_dir)?;
    session.save_dir = save_dir.to_string();

    let cfg = EngineConfig {
        listen_port,
        cache_bytes,
        dht_enabled,
        session: session.clone(),
    };

    Ok((cfg, session))
}

/// Parse a session-defaults JSON blob (applies to torrents added afterwards).
pub fn parse_session_config(json: &str) -> Result<SessionConfig, String> {
    use nextjson::Value;
    let root: Value = nextjson::nextdecode(json.as_bytes())
        .map_err(|e| format!("bad session config json: {e}"))?;
    parse_session_fields(&root, ".")
}

fn parse_session_fields(root: &nextjson::Value, save_dir: &str) -> Result<SessionConfig, String> {
    use nextjson::Value;

    let num = |key: &str, default: u64| -> u64 {
        root.get(key).and_then(Value::as_u64).unwrap_or(default)
    };
    let flag = |key: &str, default: bool| -> bool {
        root.get(key).and_then(Value::as_bool).unwrap_or(default)
    };
    let str_opt =
        |key: &str| -> Option<String> { root.get(key).and_then(Value::as_str).map(str::to_string) };

    let max_peers = num("max_peers", 80) as u32;
    let request_pipeline = num("request_pipeline", typebit::consts::REQUEST_PIPELINE as u64) as u32;
    let endgame_pieces = num("endgame_pieces", 32) as u32;
    let smart_scheduling = flag("smart_scheduling", true);
    let use_default_trackers = flag("use_default_trackers", true);

    let choke = typebit::swarm::ChokeConfig {
        seeding_slots: num("seeding_slots", 8) as u32,
        leeching_slots: num("leeching_slots", 8) as u32,
        optimistic_interval_ms: num("optimistic_interval_ms", 30_000),
        snub_timeout_ms: num("snub_timeout_ms", 60_000),
        interval_ms: num("choke_interval_ms", 10_000),
    };

    let scheduler = typebit::scheduler::SchedulerConfig {
        alpha: num("alpha", 8) as i64,
        beta: num("beta", 2) as i64,
        gamma: num("gamma", 1) as i64,
        delta: num("delta", 64) as i64,
        edge_bytes: num("edge_bytes", 4 * 1024 * 1024),
    };

    let trackers = root
        .get("trackers")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect::<Vec<String>>()
        })
        .unwrap_or_default();

    let node_secret = str_opt("node_secret")
        .and_then(|s| hex_decode_32(&s))
        .unwrap_or([0u8; 32]);

    Ok(SessionConfig {
        save_dir: save_dir.to_string(),
        max_peers,
        request_pipeline,
        endgame_pieces,
        smart_scheduling,
        choke,
        scheduler,
        node_secret,
        trackers,
        use_default_trackers,
    })
}

fn hex_decode_32(s: &str) -> Option<[u8; 32]> {
    if s.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for i in 0..32 {
        out[i] = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}
