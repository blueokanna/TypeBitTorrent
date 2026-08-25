//! Engine worker thread and JVM-facing handle.
//!
//! The `typebit::Engine` is not thread-safe and must be driven from a single
//! thread. This module owns that thread: the JNI layer sends [`Cmd`] messages
//! over an mpsc channel, and the worker replies through one-shot channels
//! embedded in each command. Events are drained every tick, serialized to
//! JSON and pushed into a shared queue that Kotlin polls.

use std::collections::VecDeque;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use typebit::engine::Engine;
use typebit::platform::NetAddr;
use typebit::session::{FilePriority, SessionConfig, WebSeedConfig};
use typebit::socks::ProxyConfig;
use typebit::{EngineConfig, EngineEvent, Host, InfoHash};

use crate::host::{LogBuffer, NativeHost};
use crate::json::JsonWriter;
use crate::meta::{FileMeta, MetaRegistry, TorrentMeta};

/// Engine tick cadence (ms). Chosen to balance CPU and UI responsiveness.
pub const TICK_MS: u64 = 100;

/// Event queue shared with the JVM (polled via `nativeTakeEvents`).
pub type EventQueue = Arc<Mutex<VecDeque<String>>>;

/// A command submitted from the JNI layer to the engine thread.
pub enum Cmd {
    AddTorrent {
        data: Vec<u8>,
        save_dir: String,
        /// Per-file priority bytes (0=Skip, 1=Normal, 2=High), aligned with
        /// the torrent's file table. Empty = all Normal.
        file_priorities: Vec<u8>,
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
    /// Rename one file of a running torrent (index into its file table).
    /// The engine keeps writing to the original staged path; the new name
    /// only affects the final promotion and the UI display.
    RenameFile {
        hash: String,
        file: u32,
        name: String,
        tx: Sender<Result<String, String>>,
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
    /// Selective download (typebit 0.1.1): set one file's priority.
    SetFilePriority {
        hash: String,
        file: u32,
        prio: u8,
        tx: Sender<Result<(), String>>,
    },
    /// Current per-file priorities of a torrent as a JSON array.
    FilePriorities {
        hash: String,
        tx: Sender<Option<String>>,
    },
    /// Add a tracker URL to a running torrent (0.1.1, no restart needed).
    AddTracker {
        hash: String,
        url: String,
        tx: Sender<Result<(), String>>,
    },
    /// Remove a tracker URL from a running torrent.
    RemoveTracker {
        hash: String,
        url: String,
        tx: Sender<Result<(), String>>,
    },
    /// Current tracker URLs of a torrent as a JSON array.
    Trackers {
        hash: String,
        tx: Sender<Option<String>>,
    },
    /// Live peer snapshot of a torrent as a JSON array.
    Peers {
        hash: String,
        tx: Sender<String>,
    },
    /// Global wire counters (down_total, up_total) from the host.
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
    let mut host = NativeHost::new(logs.clone());
    host.bind_tcp(engine_cfg.listen_port);

    let mut engine = Engine::new(host, engine_cfg);
    let mut meta = MetaRegistry::new();
    let mut running = true;
    let mut consecutive_panics = 0u32;

    while running {
        let iteration = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let mut stop = false;
            while let Ok(cmd) = cmd_rx.try_recv() {
                if let Some(s) = handle_cmd(&mut engine, &mut meta, cmd, &events) {
                    stop = !s;
                    break;
                }
            }
            if stop {
                running = false;
                return;
            }

            let accepted = engine.host.accept_pending();
            engine.host.drain_established();
            for (conn, addr) in accepted {
                engine.on_inbound_connection(conn, addr);
            }

            let _ = engine.tick();

            let evs = engine.take_events();
            if !evs.is_empty() {
                for ev in &evs {
                    if let EngineEvent::MetadataComplete { info_hash } = ev {
                        let h = info_hash.to_hex();
                        // Magnets start with an empty placeholder in the
                        // mirror. Once the engine has the metainfo, replace
                        // it with the full file table so the Files tab and
                        // the staged-file cleanup both see the real files.
                        if let Some(t) = engine.metainfo(&info_hash) {
                            meta.register_ready(t, &h);
                        } else {
                            meta.mark_metadata_ready(&h);
                        }
                    }
                    if let EngineEvent::TorrentComplete { info_hash } = ev {
                        finalize_torrent_files(&mut engine.host, &meta, &info_hash.to_hex());
                    }
                    if let EngineEvent::PeerConnected { peer_id, addr, .. } = ev {
                        let client = typebit::leech::fingerprint(peer_id);
                        if client.class() == typebit::leech::ClientClass::Leech {
                            let name = client.code_str();
                            let a = addr_string(addr);
                            engine.host.log(
                                typebit::platform::LogLevel::Warn,
                                &format!("anti-leech: {name} ({a}) is a known leeching client"),
                            );
                            if let Ok(mut q) = events.lock() {
                                q.push_back(anti_leech_json(&name, &a));
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
        }));

        match iteration {
            Ok(()) => consecutive_panics = 0,
            Err(payload) => {
                consecutive_panics = consecutive_panics.saturating_add(1);
                let msg = panic_message(payload.as_ref());
                if let Ok(mut q) = logs.lock() {
                    q.push_back((
                        3, // LogLevel::Error
                        format!("engine panic recovered (x{consecutive_panics}): {msg}"),
                    ));
                }
                // Surface it to the UI so the freeze is never "silent".
                if let Ok(mut q) = events.lock() {
                    q.push_back(format!(
                        r#"{{"t":11,"code":2,"detail":"engine panic recovered: {msg}"}}"#
                    ));
                }
                // A panic storm means the engine state is unsalvageable: stop
                // loudly instead of busy-looping forever.
                if consecutive_panics >= 10 {
                    if let Ok(mut q) = logs.lock() {
                        q.push_back((3, String::from("engine: repeated panics, shutting down")));
                    }
                    running = false;
                }
            }
        }
        if !running {
            break;
        }
        std::thread::sleep(Duration::from_millis(TICK_MS));
    }

    engine.host.shutdown();
}

/// Best-effort human-readable panic payload.
fn panic_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        return (*s).to_string();
    }
    if let Some(s) = payload.downcast_ref::<String>() {
        return s.clone();
    }
    String::from("unknown panic")
}

/// Handle one command. Returns `Some(true)` when the loop must stop.
fn handle_cmd(
    engine: &mut Engine<NativeHost>,
    meta: &mut MetaRegistry,
    cmd: Cmd,
    events: &EventQueue,
) -> Option<bool> {
    match cmd {
        Cmd::AddTorrent {
            data,
            save_dir,
            file_priorities,
            tx,
        } => {
            let res = engine.add_torrent(&data, &save_dir).map(|h| h.to_hex());
            let res = match res {
                Ok(hex) => {
                    if let Ok(t) = typebit::metainfo::Torrent::from_bytes(&data) {
                        meta.register(&t);
                        meta.set_save_dir(&hex, &save_dir);
                    }
                    // Selective download (0.1.1): apply per-file priorities
                    // immediately so skipped files are never requested.
                    if let Ok(h) = InfoHash::from_hex(&hex) {
                        for (i, p) in file_priorities.iter().enumerate() {
                            if *p != 1 {
                                let _ = engine.set_file_priority(
                                    &h,
                                    i as u32,
                                    file_priority_from_u8(*p),
                                );
                            }
                        }
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
                    meta.set_save_dir(&hex, &save_dir);
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
                Ok(h) => {
                    // Capture the engine's actual file paths BEFORE removing
                    // — these are the true paths the engine wrote (each
                    // staged as `<path>.part`), valid for file torrents and
                    // magnets whose metadata arrived. A cancelled download
                    // must not leave incomplete data behind, so the staged
                    // files are dropped afterwards.
                    let engine_paths: Vec<String> = engine
                        .metainfo(&h)
                        .map(|t| t.files.iter().map(|f| f.display_path()).collect())
                        .unwrap_or_default();
                    let r = engine.remove_torrent(&h).map_err(|e| e.tag().to_string());
                    delete_staged_files(&mut engine.host, &meta, &hash, &engine_paths);
                    r
                }
                Err(_) => Err("invalid hash".to_string()),
            };
            meta.remove(&hash);
            let _ = tx.send(res);
        }
        Cmd::RenameFile {
            hash,
            file,
            name,
            tx,
        } => {
            let res = meta
                .rename_file(&hash, file, name.trim())
                .map_err(|e| e.to_string());
            let _ = tx.send(res);
        }
        Cmd::Peers { hash, tx } => {
            let json = InfoHash::from_hex(&hash)
                .map(|h| peers_to_json(engine.peer_snapshot(&h)))
                .unwrap_or_else(|_| "[]".to_string());
            let _ = tx.send(json);
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
            let trackers = engine.active_trackers();
            let (d_total, u_total) = engine.host.totals();
            // NAT-detected external UDP endpoint (BEP-42 cross-confirmation).
            let (ext_ip, ext_port) = engine
                .dht_external()
                .map(|(ip, p)| (fmt_ext_ip(&ip), p))
                .unwrap_or_else(|| (String::new(), 0u16));
            let mut w = JsonWriter::new();
            w.begin_object();
            w.kv_u64("dht", dht as u64);
            w.comma();
            w.kv_u64("trackers", trackers as u64);
            w.comma();
            w.kv_string("ext_ip", &ext_ip);
            w.comma();
            w.kv_u64("ext_port", ext_port as u64);
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
            // typebit 0.1.1 has built-in global token-bucket limits.
            engine.set_global_limits(down, up);
        }
        Cmd::SetSessionConfig { cfg } => {
            // Applies to torrents added from now on.
            engine.cfg.session = cfg;
        }
        Cmd::SetFilePriority {
            hash,
            file,
            prio,
            tx,
        } => {
            let res = match InfoHash::from_hex(&hash) {
                Ok(h) => engine
                    .set_file_priority(&h, file, file_priority_from_u8(prio))
                    .map_err(|e| e.tag().to_string()),
                Err(_) => Err("invalid hash".to_string()),
            };
            let _ = tx.send(res);
        }
        Cmd::FilePriorities { hash, tx } => {
            let v = InfoHash::from_hex(&hash)
                .ok()
                .and_then(|h| engine.file_priorities(&h))
                .map(|ps| {
                    let mut w = JsonWriter::new();
                    w.begin_array();
                    for (i, p) in ps.iter().enumerate() {
                        if i > 0 {
                            w.comma();
                        }
                        w.u64(p.to_byte() as u64);
                    }
                    w.end_array();
                    w.into_string()
                });
            let _ = tx.send(v);
        }
        Cmd::AddTracker { hash, url, tx } => {
            let res = match InfoHash::from_hex(&hash) {
                Ok(h) => engine
                    .add_tracker(&h, &url)
                    .map(|_| ())
                    .map_err(|e| e.tag().to_string()),
                Err(_) => Err("invalid hash".to_string()),
            };
            let _ = tx.send(res);
        }
        Cmd::RemoveTracker { hash, url, tx } => {
            let res = match InfoHash::from_hex(&hash) {
                Ok(h) => engine
                    .remove_tracker(&h, &url)
                    .map(|_| ())
                    .map_err(|e| e.tag().to_string()),
                Err(_) => Err("invalid hash".to_string()),
            };
            let _ = tx.send(res);
        }
        Cmd::Trackers { hash, tx } => {
            let v = InfoHash::from_hex(&hash)
                .ok()
                .and_then(|h| engine.trackers(&h))
                .map(|ts| {
                    let mut w = JsonWriter::new();
                    w.begin_array();
                    for (i, u) in ts.iter().enumerate() {
                        if i > 0 {
                            w.comma();
                        }
                        w.string(u);
                    }
                    w.end_array();
                    w.into_string()
                });
            let _ = tx.send(v);
        }
        Cmd::Totals { tx } => {
            let totals = engine.host.totals();
            let _ = tx.send(totals);
        }
        Cmd::SaveState { tx } => {
            // Flush dirty pieces to disk first so the persisted `have`
            // bitfield only claims data that is truly on stable storage
            // (a crash between cache-write and flush must NOT "restore"
            // pieces whose bytes are missing from the .part files).
            engine.flush_cache();
            let st = engine.save_state();
            let bytes = st.to_binary().ok();
            let _ = tx.send(bytes);
        }
        Cmd::LoadState { data } => {
            if let Ok(st) = typebit::state::SessionState::from_binary(&data) {
                let now = engine.host.now_ms();
                // 0.1.1: torrents are re-added first (add_torrent), then
                // restore_torrent re-applies verified pieces, per-file
                // priorities, per-task limits and the reputation ledger;
                // load_state restores the DHT routing table.
                for t in &st.torrents {
                    let h = bytes_to_infohash(&t.info_hash);
                    if let Some(h) = h {
                        if let Err(e) = engine.restore_torrent(&h, t) {
                            engine.host.log(
                                typebit::platform::LogLevel::Warn,
                                &format!(
                                    "restore_torrent {} failed: {} ({} pieces have)",
                                    h.to_hex(),
                                    e.tag(),
                                    t.have.len()
                                ),
                            );
                        }
                    }
                }
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

/// Format a 16-byte BEP-42 address: IPv4 when stored in the leading 4
/// bytes (or IPv4-mapped), else hex IPv6.
fn fmt_ext_ip(ip: &[u8; 16]) -> String {
    let mapped = ip[0..10].iter().all(|&b| b == 0) && ip[10] == 0xFF && ip[11] == 0xFF;
    let v4 = if mapped {
        Some([ip[12], ip[13], ip[14], ip[15]])
    } else if ip[4..].iter().all(|&b| b == 0) {
        Some([ip[0], ip[1], ip[2], ip[3]])
    } else {
        None
    };
    match v4 {
        Some(a) => format!("{}.{}.{}.{}", a[0], a[1], a[2], a[3]),
        None => (0..8)
            .map(|i| format!("{:x}", u16::from_be_bytes([ip[i * 2], ip[i * 2 + 1]])))
            .collect::<Vec<_>>()
            .join(":"),
    }
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
        // A peer was banned by the built-in anti-leech engine (0.1.1).
        EngineEvent::PeerBanned {
            info_hash,
            addr,
            reason,
        } => {
            w.kv_u64("t", 10);
            w.comma();
            w.kv_string("h", &info_hash.to_hex());
            w.comma();
            w.kv_string("a", &addr_string(addr));
            w.comma();
            w.kv_string("r", &ban_reason_str(reason));
        }
        EngineEvent::Error { code, detail } => {
            w.kv_u64("t", 11);
            w.comma();
            w.kv_u64("code", *code as u64);
            w.comma();
            w.kv_string("detail", detail);
        }
        // `EngineEvent` is `#[non_exhaustive]` (forward-compatible enum).
        _ => {}
    }
    w.end_object();
    w.into_string()
}

/// Resolve a file's effective relative path: the user rename (if any) wins,
/// otherwise the torrent's original path.
fn effective_relative_path(m: &TorrentMeta, index: usize, f: &FileMeta) -> String {
    m.renames
        .iter()
        .find(|(idx, _)| *idx == index as u32)
        .map(|(_, name)| name.clone())
        .unwrap_or_else(|| f.path.join("/"))
}

/// Serialize a live peer snapshot to a JSON array for the Peers tab.
fn peers_to_json(peers: Vec<typebit::session::PeerSnapshot>) -> String {
    let mut w = JsonWriter::new();
    w.begin_array();
    for (i, p) in peers.iter().enumerate() {
        if i > 0 {
            w.comma();
        }
        w.begin_object();
        w.kv_string("addr", &p.addr);
        w.comma();
        w.kv_string("client", &p.client);
        w.comma();
        w.kv_u64("phase", p.phase as u64);
        w.comma();
        w.kv_bool("seed", p.is_seed);
        w.comma();
        w.kv_u64("down", p.down_rate as u64);
        w.comma();
        w.kv_u64("up", p.up_rate as u64);
        w.comma();
        w.kv_u64("inflight", p.in_flight as u64);
        w.end_object();
    }
    w.end_array();
    w.into_string()
}

/// Promote a completed torrent's staged (`.part`) files to their final
/// names. `TorrentComplete` only fires once every selected piece has been
/// hash-verified, so the promoted files are complete and safe to expose.
/// User renames are honoured here: the engine wrote `<original>.part` all
/// along, and the final name becomes the renamed one.
fn finalize_torrent_files(host: &mut NativeHost, meta: &MetaRegistry, hash: &str) {
    let Some(m) = meta.get(hash) else {
        return;
    };
    if m.files.is_empty() || m.save_dir.is_empty() {
        return;
    }
    let mut finalized = 0usize;
    for (i, f) in m.files.iter().enumerate() {
        let rel = effective_relative_path(m, i, f);
        let mut p = String::from(&m.save_dir);
        if !p.ends_with('/') && !p.ends_with('\\') {
            p.push('/');
        }
        p.push_str(&rel);
        host.finalize_file(&p);
        finalized += 1;
    }
    host.log(
        typebit::platform::LogLevel::Info,
        &format!("finalized {finalized} file(s) for {hash}"),
    );
}

/// Delete the staged (`.part`) files of a torrent — used when a torrent is
/// removed so a cancelled download leaves no incomplete data on disk.
///
/// The engine ALWAYS writes through the *original metainfo path* + `.part`
/// (renames only affect the final promotion on completion), so deletion must
/// target the original paths — never the renamed ones. Two sources feed those
/// paths:
///   * `engine_paths` — the engine's own metainfo (authoritative; covers
///     magnets once their metadata arrived);
///   * the bridge mirror (`meta`) — registered for file torrents at add time.
fn delete_staged_files(
    host: &mut NativeHost,
    meta: &MetaRegistry,
    hash: &str,
    engine_paths: &[String],
) {
    let save_dir = meta
        .get(hash)
        .map(|m| m.save_dir.clone())
        .unwrap_or_default();
    if save_dir.is_empty() {
        return;
    }
    let mut base = String::from(&save_dir);
    if !base.ends_with('/') && !base.ends_with('\\') {
        base.push('/');
    }
    let mut targets: Vec<String> = Vec::new();
    for rel in engine_paths {
        let p = format!("{base}{rel}");
        if !targets.contains(&p) {
            targets.push(p);
        }
    }
    if let Some(m) = meta.get(hash) {
        for f in &m.files {
            let p = format!("{base}{}", f.path.join("/"));
            if !targets.contains(&p) {
                targets.push(p);
            }
        }
    }
    let mut removed = 0usize;
    for p in &targets {
        let stage = format!("{p}.part");
        if std::path::Path::new(&stage).exists() && std::fs::remove_file(&stage).is_ok() {
            removed += 1;
        }
    }
    if removed > 0 {
        host.log(
            typebit::platform::LogLevel::Info,
            &format!("removed {removed} staged file(s) for {hash}"),
        );
    }
}

fn addr_string(a: &NetAddr) -> String {
    a.to_alloc_string()
}

/// Stable JSON reason code for a [`typebit::leech::BanReason`].
fn ban_reason_str(r: &typebit::leech::BanReason) -> String {
    match r {
        typebit::leech::BanReason::Corrupt => "corrupt".to_string(),
        typebit::leech::BanReason::Protocol => "protocol".to_string(),
        typebit::leech::BanReason::FreeRide => "free-ride".to_string(),
    }
}

/// Decode a persisted priority byte (0=Skip, 1=Normal, 2=High); unknown
/// values degrade to Normal so a corrupt blob can never skip a file.
fn file_priority_from_u8(b: u8) -> FilePriority {
    match b {
        0 => FilePriority::Skip,
        2 => FilePriority::High,
        _ => FilePriority::Normal,
    }
}

/// Rebuild an `InfoHash` from raw state bytes (20=v1, 32=v2).
fn bytes_to_infohash(bytes: &[u8]) -> Option<InfoHash> {
    match bytes.len() {
        20 => {
            let mut h = [0u8; 20];
            h.copy_from_slice(bytes);
            Some(InfoHash::v1(h))
        }
        32 => {
            let mut h = [0u8; 32];
            h.copy_from_slice(bytes);
            Some(InfoHash::v2(h))
        }
        _ => None,
    }
}

/// Anti-leech soft-detection event (t=9): a known leeching client connected.
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
    let global_up = num("global_upload_limit_bps", 0);
    let global_down = num("global_download_limit_bps", 0);
    let global_max_connections = num("global_max_connections", 512) as usize;
    let max_connections_per_ip = num("max_connections_per_ip", 8) as u32;
    let port_mapping = flag("port_mapping", false);
    let verify_workers = num("verify_workers", 0) as usize;
    let connect_timeout_ms = num("connect_timeout_ms", 30_000);
    let proxy = parse_proxy(&root);

    let mut session = parse_session_fields(&root, save_dir)?;
    session.save_dir = save_dir.to_string();
    session.proxy = proxy.clone();

    let cfg = EngineConfig {
        listen_port,
        cache_bytes,
        dht_enabled,
        global_upload_limit_bps: global_up,
        global_download_limit_bps: global_down,
        global_max_connections,
        max_connections_per_ip,
        port_mapping,
        verify_workers,
        proxy,
        connect_timeout_ms,
        session: session.clone(),
    };

    Ok((cfg, session))
}

/// Parse a SOCKS5 proxy blob: `{"enabled":bool,"host":"..","port":n,
/// "username":"..","password":".."}` (anonymous when no credentials).
fn parse_proxy(root: &nextjson::Value) -> Option<ProxyConfig> {
    use nextjson::Value;
    let obj = root.get("proxy")?;
    let enabled = obj.get("enabled").and_then(Value::as_bool).unwrap_or(false);
    if !enabled {
        return None;
    }
    let host = obj.get("host").and_then(Value::as_str)?;
    let port = obj.get("port").and_then(Value::as_u64).unwrap_or(9050) as u16;
    let addr = parse_addr(host, port)?;
    let username = obj
        .get("username")
        .and_then(Value::as_str)
        .map(str::to_string);
    let password = obj
        .get("password")
        .and_then(Value::as_str)
        .map(str::to_string);
    Some(ProxyConfig {
        socks5: addr,
        username: username.filter(|s| !s.is_empty()),
        password: password.filter(|s| !s.is_empty()),
        handshake_timeout_ms: 15_000,
    })
}

/// Parse a `host[:port]` string into a [`NetAddr`] (IPv4/IPv6, or a
/// hostname resolved via the OS). Returns `None` when unresolvable.
fn parse_addr(host: &str, port: u16) -> Option<NetAddr> {
    if let Ok(ip) = host.parse::<std::net::Ipv4Addr>() {
        return Some(NetAddr::V4(ip.octets(), port));
    }
    if let Ok(ip) = host.parse::<std::net::Ipv6Addr>() {
        return Some(NetAddr::V6(ip.octets(), port));
    }
    // hostname → first A record
    let ip = std::net::ToSocketAddrs::to_socket_addrs(&(host.to_string(), port))
        .ok()?
        .next()?
        .ip();
    match ip {
        std::net::IpAddr::V4(v4) => Some(NetAddr::V4(v4.octets(), port)),
        std::net::IpAddr::V6(v6) => Some(NetAddr::V6(v6.octets(), port)),
    }
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
    let listen_port = num("listen_port", typebit::consts::DEFAULT_PORT as u64) as u16;

    // typebit 0.1.1 folded the old ChokeConfig into the anti-leech engine's
    // LeechConfig (slot management + choke + ban policy in one struct).
    let leech = typebit::leech::LeechConfig {
        seeding_slots: num("seeding_slots", 8) as u32,
        leeching_slots: num("leeching_slots", 8) as u32,
        optimistic_interval_ms: num("optimistic_interval_ms", 30_000),
        snub_timeout_ms: num("snub_timeout_ms", 60_000),
        rechoke_interval_ms: num("choke_interval_ms", 10_000),
        ..Default::default()
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

    let upload_limit_bps = num("upload_limit_bps", 0);
    let download_limit_bps = num("download_limit_bps", 0);

    Ok(SessionConfig {
        save_dir: save_dir.to_string(),
        max_peers,
        request_pipeline,
        endgame_pieces,
        smart_scheduling,
        leech,
        scheduler,
        node_secret,
        trackers,
        use_default_trackers,
        listen_port,
        upload_limit_bps,
        download_limit_bps,
        file_priorities: Vec::new(),
        proxy: None,
        webseed: WebSeedConfig::default(),
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// A download writes `<final>.part` (never the final name); after
    /// `finalize_file` the file appears under the final name and the staging
    /// copy is gone. Resume re-opens the staging file.
    #[test]
    fn disk_staging_and_finalize() {
        let dir = std::env::temp_dir().join(format!("typebit_native_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let final_path = dir.join("x.bin");
        let stage = dir.join("x.bin.part");

        let logs: LogBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let mut host = NativeHost::new(logs);

        // Fresh download: opens the .part staging file.
        let id = host.disk_open(final_path.to_str().unwrap()).unwrap();
        host.disk_write(id, 0, b"abcd").unwrap();
        host.disk_flush(id).unwrap();
        host.disk_close(id);
        assert!(
            !final_path.exists(),
            "final file must not exist mid-download"
        );
        assert!(stage.exists(), "staging file must exist mid-download");

        // Resume: opening again must pick the staging file (data intact).
        let id2 = host.disk_open(final_path.to_str().unwrap()).unwrap();
        let mut buf = [0u8; 4];
        host.disk_read(id2, 0, &mut buf).unwrap();
        host.disk_close(id2);
        assert_eq!(&buf, b"abcd", "resume must read the staged data");

        // Completion: promote the staged file to the final name.
        host.finalize_file(final_path.to_str().unwrap());
        assert!(final_path.exists(), "final file must exist after finalize");
        assert!(!stage.exists(), "staging file must be gone after finalize");
        assert_eq!(fs::read(&final_path).unwrap(), b"abcd");

        let _ = fs::remove_dir_all(&dir);
    }
}
