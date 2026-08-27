//! Engine worker thread and JVM-facing handle.
//!
//! The `typebit::Engine` is not thread-safe and must be driven from a single
//! thread. This module owns that thread: the JNI layer sends [`Cmd`] messages
//! over an mpsc channel, and the worker replies through one-shot channels
//! embedded in each command. Events are drained every tick, serialized to
//! JSON and pushed into a shared queue that Kotlin polls.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use crate::android_log::log as alog;

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
///
/// The enum mixes tiny no-reply commands with reply-bearing ones (which carry
/// a `Sender`), so the variants differ a lot in size. Boxing the senders would
/// churn every match arm in `handle_cmd` for no real gain — this enum is
/// internal to the worker thread and created once per JNI call.
#[allow(clippy::large_enum_variant)]
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
    /// Rename the torrent itself (display name only — staged file paths are
    /// untouched, exactly like qBittorrent's per-torrent rename).
    RenameTorrent {
        hash: String,
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
    /// The raw bencoded `info` dict of a torrent (for persistence so a
    /// magnet never re-fetches metadata after a restart).
    TorrentInfoRaw {
        hash: String,
        tx: Sender<Option<Vec<u8>>>,
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
    /// Atomically replace all per-file priorities and release any
    /// two-phase magnet hold (single engine-thread commit).
    SetFilePriorities {
        hash: String,
        priorities: Vec<u8>,
        tx: Sender<Result<(), String>>,
    },
    /// Two-phase magnet support: hold off data downloads until priorities
    /// are committed.
    SetHoldData {
        hash: String,
        hold: bool,
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
    /// Engine-wide statistics (wire totals, cache counters, connected peers,
    /// discarded bytes) for the qBittorrent-style stats dialog.
    Stats {
        tx: Sender<String>,
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
    /// Set by [`EngineHandle::shutdown`]; the worker checks it every tick so
    /// teardown works even if the command queue is momentarily wedged.
    stop_flag: Arc<AtomicBool>,
    /// The worker thread, joined on shutdown so a destroyed handle is
    /// guaranteed to leave NO orphan engine writing to the same files/ports.
    join: Mutex<Option<JoinHandle<()>>>,
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

    /// Stop the engine worker and WAIT until its thread has actually
    /// exited. Both the stop flag (checked every tick) and the `Shutdown`
    /// command are used; the join makes the destroy path unconditional, so
    /// a subsequent `spawn_engine` can never run two engines at once
    /// (two engines would bind the same ports and write the same `.part`
    /// files, corrupting downloads).
    pub fn shutdown(&self) {
        alog("EngineHandle::shutdown() called");
        self.stop_flag.store(true, Ordering::Relaxed);
        let (tx, rx) = channel();
        let _ = self.cmd_tx.send(Cmd::Shutdown { tx });
        let _ = rx.recv_timeout(Duration::from_secs(2));
        if let Some(jh) = self.join.lock().ok().and_then(|mut g| g.take()) {
            let _ = jh.join();
            alog("EngineHandle::shutdown(): worker joined");
        }
    }
}

/// Single-instance guard: exactly ONE engine worker per process. A second
/// `spawn_engine` (from a leaked store after a slow teardown) must be
/// refused — two engines would bind the same ports and write the same
/// `.part` files, corrupting downloads. The guard is released when the
/// worker thread exits.
static ENGINE_LIVE: AtomicBool = AtomicBool::new(false);

/// Diagnostic: monotonically increasing spawn sequence for logcat tracing.
static ENGINE_SPAWN_SEQ: AtomicU32 = AtomicU32::new(0);

/// The most recent panic's `file:line` (set by the panic hook BEFORE the
/// stack unwinds). `catch_unwind` only exposes the payload, not the source
/// location, so the hook records it here and `log_panic` surfaces it in the
/// UI event — otherwise a recovered panic is impossible to localise.
static LAST_PANIC_LOC: Mutex<Option<String>> = Mutex::new(None);

/// Spawn the engine worker thread. `config_json` is the JSON blob produced by
/// the Kotlin side (see `parse_config`). Returns a handle or a string error.
pub fn spawn_engine(
    config_json: &str,
    save_dir: &str,
    logs: LogBuffer,
) -> Result<EngineHandle, String> {
    std::panic::set_hook(Box::new(|info| {
        let msg = if let Some(s) = info.payload().downcast_ref::<&str>() {
            (*s).to_string()
        } else if let Some(s) = info.payload().downcast_ref::<String>() {
            s.clone()
        } else {
            String::from("unknown panic")
        };
        let loc = info
            .location()
            .map(|l| format!("{}:{}", l.file(), l.line()))
            .unwrap_or_default();
        if let Ok(mut g) = LAST_PANIC_LOC.lock() {
            *g = Some(loc.clone());
        }
        alog(&format!("PANIC at {loc}: {msg}"));
    }));
    let seq = ENGINE_SPAWN_SEQ.fetch_add(1, Ordering::Relaxed);
    alog(&format!(
        "spawn_engine #{seq} entering (live={})",
        ENGINE_LIVE.load(Ordering::SeqCst)
    ));
    if ENGINE_LIVE
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        alog(&format!("spawn_engine #{seq} REFUSED (already running)"));
        return Err("an engine is already running in this process".to_string());
    }
    let (cfg, _session_cfg) = match parse_config(config_json, save_dir) {
        Ok(v) => v,
        Err(e) => {
            ENGINE_LIVE.store(false, Ordering::SeqCst);
            alog(&format!("spawn_engine #{seq} config error: {e}"));
            return Err(e);
        }
    };
    let (cmd_tx, cmd_rx) = channel::<Cmd>();
    let events: EventQueue = Arc::new(Mutex::new(VecDeque::new()));
    let events_worker = events.clone();
    let logs_worker = logs.clone();
    let stop_flag = Arc::new(AtomicBool::new(false));
    let flag_worker = stop_flag.clone();

    let jh = match std::thread::Builder::new()
        .name("typebit-engine".to_string())
        .spawn(move || {
            run_loop(cfg, logs_worker, cmd_rx, events_worker, flag_worker);
            ENGINE_LIVE.store(false, Ordering::SeqCst);
            alog(&format!(
                "spawn_engine #{seq}: worker exited, guard released"
            ));
        }) {
        Ok(jh) => jh,
        Err(e) => {
            ENGINE_LIVE.store(false, Ordering::SeqCst);
            alog(&format!("spawn_engine #{seq} thread spawn failed: {e}"));
            return Err(format!("failed to spawn engine thread: {e}"));
        }
    };
    alog(&format!("spawn_engine #{seq} OK"));

    Ok(EngineHandle {
        cmd_tx,
        events,
        logs,
        stop_flag,
        join: Mutex::new(Some(jh)),
    })
}

/// The engine thread's main loop.
fn run_loop(
    engine_cfg: EngineConfig,
    logs: LogBuffer,
    cmd_rx: Receiver<Cmd>,
    events: EventQueue,
    stop_flag: Arc<AtomicBool>,
) {
    let mut host = NativeHost::new(logs.clone());
    host.bind_tcp(engine_cfg.listen_port);

    let mut engine = Engine::new(host, engine_cfg);
    let mut meta = MetaRegistry::new();
    alog("run_loop: started");

    // Exit via `break` on the stop flag / shutdown command; the loop simply
    // runs until the engine is stopped (a `while running` with an immutable
    // condition trips clippy::while_immutable_condition).
    loop {
        if stop_flag.load(Ordering::Relaxed) {
            break;
        }

        let mut stop = false;
        while let Ok(cmd) = cmd_rx.try_recv() {
            let handled = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                handle_cmd(&mut engine, &mut meta, cmd, &events)
            }));
            match handled {
                Ok(Some(true)) => {
                    stop = true;
                    break;
                }
                Ok(_) => {}
                Err(payload) => log_panic(&logs, &events, payload.as_ref()),
            }
        }
        if stop {
            break;
        }

        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
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
                        if let Some(t) = engine.metainfo(info_hash) {
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
        }))
        .unwrap_or_else(|payload| {
            log_panic(&logs, &events, payload.as_ref());
        });

        std::thread::sleep(Duration::from_millis(TICK_MS));
    }

    alog("run_loop: exiting");
    engine.host.shutdown();
}

/// Record a recovered panic: one log line plus one UI event, so a crash is
/// never silent. The message is JSON-escaped so it can always be parsed by
/// the Kotlin event consumer, and it is also printed to logcat (with a
/// backtrace) so adb can see it even if the UI never surfaces it.
fn log_panic(logs: &LogBuffer, events: &EventQueue, payload: &(dyn std::any::Any + Send)) {
    let msg = panic_message(payload);
    // The panic hook recorded the exact `file:line` before unwinding; carry
    // it into every consumer so a recovered panic is always localisable.
    let loc = LAST_PANIC_LOC
        .lock()
        .ok()
        .and_then(|g| g.clone())
        .unwrap_or_default();
    // A backtrace pinpoints the exact call site; it only materialises in
    // release with debug info, but the debug/diagnostic builds carry it.
    let bt = std::backtrace::Backtrace::force_capture();
    let full = format!("engine PANIC recovered: {msg} (at {loc})\n{bt}");
    alog(&full);
    let label = if loc.is_empty() {
        format!("engine panic recovered: {msg}")
    } else {
        format!("engine panic recovered: {msg} (at {loc})")
    };
    if let Ok(mut q) = logs.lock() {
        q.push_back((3, label.clone()));
    }
    if let Ok(mut q) = events.lock() {
        let mut w = JsonWriter::new();
        w.begin_object();
        w.kv_u64("t", 11);
        w.comma();
        w.kv_u64("code", 2);
        w.comma();
        w.kv_string("detail", &label);
        w.end_object();
        q.push_back(w.into_string());
    }
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
                    let engine_paths: Vec<String> = engine
                        .metainfo(&h)
                        .map(|t| t.files.iter().map(|f| f.display_path()).collect())
                        .unwrap_or_default();
                    let r = engine.remove_torrent(&h).map_err(|e| e.tag().to_string());
                    delete_staged_files(&mut engine.host, meta, &hash, &engine_paths);
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
        Cmd::RenameTorrent { hash, name, tx } => {
            let res = meta
                .rename_torrent(&hash, name.trim())
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
        Cmd::TorrentInfoRaw { hash, tx } => {
            let v = InfoHash::from_hex(&hash)
                .ok()
                .and_then(|h| engine.metainfo(&h))
                .map(|t| t.info_raw.clone());
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
            // UPnP/NAT-PMP port-mapping lifecycle (0=idle … 6=mapped, 9=failed).
            let pm = engine.port_mapping_status();
            let pm_phase = pm.as_ref().map(|s| s.phase.code() as u64).unwrap_or(0);
            let pm_port = pm.as_ref().and_then(|s| s.external_port).unwrap_or(0);
            w.kv_u64("pm_phase", pm_phase);
            w.comma();
            w.kv_u64("pm_port", pm_port as u64);
            w.comma();
            // Actual bound TCP port (drives the firewall rule target; equals
            // the configured port except in random-port mode).
            w.kv_u64("listen_port", engine.host.listen_port() as u64);
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
                let uploaded = ih.as_ref().map(|h| engine.uploaded(h)).unwrap_or(0);
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
                w.kv_u64("u", uploaded);
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
            engine.set_global_limits(down, up);
        }
        Cmd::SetSessionConfig { cfg } => {
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
        Cmd::SetFilePriorities {
            hash,
            priorities,
            tx,
        } => {
            let prios: Vec<FilePriority> = priorities
                .iter()
                .map(|b| file_priority_from_u8(*b))
                .collect();
            let res = match InfoHash::from_hex(&hash) {
                Ok(h) => engine
                    .set_file_priorities(&h, &prios)
                    .map_err(|e| e.tag().to_string()),
                Err(_) => Err("invalid hash".to_string()),
            };
            let _ = tx.send(res);
        }
        Cmd::SetHoldData { hash, hold, tx } => {
            let res = match InfoHash::from_hex(&hash) {
                Ok(h) => engine
                    .set_hold_data(&h, hold)
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
        Cmd::Stats { tx } => {
            let st = engine.stats();
            let (d_total, u_total) = engine.host.totals();
            let _ = tx.send(stats_to_json(&st, d_total, u_total));
        }
        Cmd::SaveState { tx } => {
            engine.flush_cache();
            let st = engine.save_state();
            let bytes = st.to_binary().ok();
            let _ = tx.send(bytes);
        }
        Cmd::LoadState { data } => {
            if let Ok(st) = typebit::state::SessionState::from_binary(&data) {
                let now = engine.host.now_ms();
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
                        // Persisted metadata: upgrade a magnet session so it
                        // never re-fetches metadata after a restart.
                        if !t.info_raw.is_empty() {
                            if let Err(e) = engine.install_metadata(&h, &t.info_raw) {
                                engine.host.log(
                                    typebit::platform::LogLevel::Warn,
                                    &format!("install_metadata {} failed: {}", h.to_hex(), e.tag()),
                                );
                            }
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
        // UPnP/NAT-PMP port mapping phase changed (0.1.7 dual-protocol).
        EngineEvent::PortMapping {
            phase,
            external_port,
        } => {
            w.kv_u64("t", 12);
            w.comma();
            w.kv_u64("phase", phase.code() as u64);
            if let Some(p) = external_port {
                w.comma();
                w.kv_u64("port", *p as u64);
            }
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
        // ISO-3166 alpha-2 country code ("" when unknown/private) — the UI
        // renders the national flag before the address from this.
        w.kv_string("cc", &country_code(&p.cc));
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

/// A `[u8; 2]` country code as a 2-char string ("" when all-zero).
fn country_code(cc: &[u8; 2]) -> String {
    if cc[0] == 0 && cc[1] == 0 {
        String::new()
    } else {
        String::from_utf8_lossy(cc).into_owned()
    }
}

/// Serialize the engine-wide statistics for the stats dialog.
///
/// `d_total`/`u_total` are the cumulative wire counters from the host; the
/// rest come from the engine's [`typebit::engine::EngineStats`]. Every field
/// is a real counter.
fn stats_to_json(st: &typebit::engine::EngineStats, d_total: u64, u_total: u64) -> String {
    let mut w = JsonWriter::new();
    w.begin_object();
    w.kv_u64("d_total", d_total);
    w.comma();
    w.kv_u64("u_total", u_total);
    w.comma();
    w.kv_u64("d_discarded", st.discarded_bytes);
    w.comma();
    w.kv_u64("d_peers", st.connected_peers as u64);
    w.comma();
    w.kv_u64("c_read_ops", st.cache.read_ops);
    w.comma();
    w.kv_u64("c_read_hits", st.cache.read_hits);
    w.comma();
    w.kv_u64("c_read_bytes", st.cache.read_bytes);
    w.comma();
    w.kv_u64("c_write_ops", st.cache.write_ops);
    w.comma();
    w.kv_u64("c_write_bytes", st.cache.write_bytes);
    w.comma();
    w.kv_u64("c_coalesced", st.cache.bytes_coalesced);
    w.comma();
    w.kv_u64("c_ops_saved", st.cache.ops_saved);
    w.comma();
    w.kv_u64("c_evictions", st.cache.evictions);
    w.comma();
    w.kv_u64("c_buf", st.cache_bytes);
    w.comma();
    w.kv_u64("c_budget", st.cache_budget);
    w.comma();
    w.kv_u64("c_clean", st.cache_clean);
    w.comma();
    w.kv_u64("c_clean_budget", st.cache_clean_budget);
    w.comma();
    w.kv_u64("c_dirty_entries", st.cache_dirty_entries as u64);
    w.end_object();
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
        typebit::leech::BanReason::Timeout => "timeout".to_string(),
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
    let lsd_enabled = flag("lsd_enabled", true);
    // LSD announce interval (mechanism 4): one infohash per interval,
    // round-robin over active torrents. The engine clamps it to a hard
    // floor so the LAN multicast can never become a storm.
    let lsd_interval_ms = num("lsd_interval_ms", typebit::lsd::LSD_INTERVAL_MS);
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
        lsd_enabled,
        lsd_interval_ms,
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
    // Disk allocation strategy: 0=off 1=sparse(set_len) 2=full(set_len+fill).
    let preallocation = typebit::session::Preallocation::from_code(num("preallocation", 1) as u8);
    let request_pipeline = num("request_pipeline", typebit::consts::REQUEST_PIPELINE as u64) as u32;
    // Mechanism 2: per-request timeout + consecutive-timeout ban limit.
    let request_timeout_ms = num("request_timeout_ms", typebit::consts::REQUEST_TIMEOUT_MS);
    let max_request_timeouts = num(
        "max_request_timeouts",
        typebit::consts::MAX_REQUEST_TIMEOUTS as u64,
    ) as u32;
    let endgame_pieces = num("endgame_pieces", 32) as u32;
    let smart_scheduling = flag("smart_scheduling", true);
    let use_default_trackers = flag("use_default_trackers", true);
    let listen_port = num("listen_port", typebit::consts::DEFAULT_PORT as u64) as u16;

    let leech = typebit::leech::LeechConfig {
        seeding_slots: num("seeding_slots", 8) as u32,
        leeching_slots: num("leeching_slots", 8) as u32,
        optimistic_interval_ms: num("optimistic_interval_ms", 30_000),
        snub_timeout_ms: num("snub_timeout_ms", 60_000),
        rechoke_interval_ms: num("choke_interval_ms", 10_000),
        block_leech_clients: flag("block_leech_clients", true),
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
        request_timeout_ms,
        max_request_timeouts,
        file_priorities: Vec::new(),
        proxy: None,
        webseed: WebSeedConfig::default(),
        preallocation,
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

    /// Serializes tests that spawn the REAL engine worker: the process-wide
    /// singleton guard (`ENGINE_LIVE`) only allows one engine at a time, and
    /// the default test runner runs tests in parallel threads.
    fn engine_test_lock() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
        LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Diagnostic: resolve the BEP-5 DHT bootstrap routers through the exact
    /// same `StdHost::resolve_host` path the engine uses, printing each
    /// result. Isolates whether a "DHT stuck at 1 node" report is a DNS
    /// failure or an engine-flow bug. Prints evidence; never asserts.
    #[test]
    #[ignore = "network diagnostic; run with `cargo test -- --ignored`"]
    fn diag_dns_bootstrap_routers() {
        use typebit::host_std::StdHost;
        let routers: &[(&str, u16)] = &[
            ("dht.transmissionbt.com", 6881),
            ("router.bittorrent.com", 6881),
            ("router.utorrent.com", 6881),
            ("router.transmissionbt.com", 6881),
            ("dht.libtorrent.org", 25401),
        ];
        for (host, port) in routers {
            let r = StdHost::new().resolve_host(host, *port);
            match r {
                Some(addr) => eprintln!("diag-dns: {host}:{port} -> {addr}"),
                None => eprintln!("diag-dns: {host}:{port} -> UNRESOLVED"),
            }
        }
    }

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

    /// The engine worker must be a strict singleton and `shutdown` must
    /// REALLY terminate the thread (releasing the singleton guard) — a
    /// leaked worker would race a second engine on the same port and the
    /// same staged files, silently corrupting downloads.
    #[test]
    fn engine_lifecycle_singleton_and_shutdown() {
        let _guard = engine_test_lock();
        let logs: LogBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let h1 = spawn_engine("{}", ".", logs.clone()).expect("first engine spawns");

        // A second engine must be refused while the first is alive.
        match spawn_engine("{}", ".", logs.clone()) {
            Ok(_) => panic!("second engine must be refused"),
            Err(e) => {
                assert!(e.contains("already running"), "unexpected error: {e}")
            }
        }

        // Shutdown must terminate the worker (join) and release the guard.
        h1.shutdown();

        // The guard is released, so a fresh engine can start again.
        let h2 = spawn_engine("{}", ".", logs.clone()).expect("engine restarts after shutdown");
        h2.shutdown();
    }

    /// Removing a torrent MUST delete its staged (`.part`) files — a
    /// cancelled download must never leave incomplete data behind. This runs
    /// the real engine worker and the real `Cmd::AddTorrent` / `Cmd::Start` /
    /// `Cmd::Remove` flow, so it also exercises the engine-path capture and
    /// the staged-path computation end to end.
    #[test]
    fn remove_deletes_staged_files() {
        let _guard = engine_test_lock();
        let dir = std::env::temp_dir().join(format!("typebit_remove_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        // A small single-file payload → torrent whose staged file will be
        // `<dir>/payload.bin.part`.
        let payload = dir.join("payload.bin");
        fs::write(&payload, vec![0x42u8; 300_000]).unwrap();
        let torrent = crate::make_torrent::create_torrent_v1(
            &[crate::make_torrent::FileSpec {
                abs_path: payload.clone(),
                rel_path: vec!["payload.bin".to_string()],
            }],
            64 * 1024,
            "payload",
            None,
            None,
        )
        .expect("create torrent");

        let logs: LogBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let engine = spawn_engine("{}", dir.to_str().unwrap(), logs.clone()).expect("spawn");

        // Add the torrent (engine registers the mirror + save dir).
        let (tx, rx) = channel();
        let added: Option<Result<String, String>> = engine.request(
            Cmd::AddTorrent {
                data: torrent,
                save_dir: dir.to_str().unwrap().to_string(),
                file_priorities: Vec::new(),
                tx,
            },
            rx,
            Duration::from_secs(10),
        );
        let hash = added.expect("add timed out").expect("add failed");
        let (tx2, rx2) = channel();
        let start_res: Option<Result<(), String>> = engine.request(
            Cmd::Start {
                hash: hash.clone(),
                tx: tx2,
            },
            rx2,
            Duration::from_secs(10),
        );
        assert!(start_res.expect("start timed out").is_ok(), "start failed");
        std::thread::sleep(Duration::from_millis(500));

        let stage = dir.join("payload.part");
        assert!(stage.exists(), "staged file should exist after start");

        let (tx3, rx3) = channel();
        let removed: Option<Result<(), String>> =
            engine.request(Cmd::Remove { hash, tx: tx3 }, rx3, Duration::from_secs(10));
        assert!(removed.expect("remove timed out").is_ok(), "remove failed");
        std::thread::sleep(Duration::from_millis(300));
        assert!(
            !stage.exists(),
            "staged file must be deleted when the torrent is removed"
        );
        let leftovers: Vec<_> = fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_name().to_string_lossy().ends_with(".part"))
            .collect();
        assert!(
            leftovers.is_empty(),
            "no .part files may remain after removal: {leftovers:?}"
        );

        engine.shutdown();
        let _ = fs::remove_dir_all(&dir);
    }

    /// Diagnostic (not a normal assertion test): run the REAL engine worker +
    /// REAL NativeHost against the REAL network with a real torrent file for
    /// `minutes` and report (a) any recovered panic (t=11 code=2 events),
    /// (b) DHT node count over time, (c) download progress. Used to hunt the
    /// intermittent engine panic / "DHT to zero / download stalls" bug.
    ///
    /// Torrent file: `$env:TBT_DIAG_TORRENT` or a known Kali ISO torrent.
    /// Skip when the file is absent (e.g. CI).
    #[test]
    #[ignore = "network diagnostic; run with `cargo test -- --ignored`"]
    fn diag_real_download_panic_hunt() {
        let _guard = engine_test_lock();
        let torrent_path = std::env::var("TBT_DIAG_TORRENT")
            .unwrap_or_else(|_| "D:\\RustProject\\TypeBitTorrent\\build\\tmp\\kali.torrent".into());
        let data = match fs::read(&torrent_path) {
            Ok(d) => d,
            Err(_) => {
                eprintln!("diag_real_download_panic_hunt: no torrent at {torrent_path}, skipping");
                return;
            }
        };
        let minutes = std::env::var("TBT_DIAG_MINUTES")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(3);

        let dir = std::env::temp_dir().join(format!("typebit_diag_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let logs: LogBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let engine = spawn_engine("{}", dir.to_str().unwrap(), logs.clone()).expect("spawn");

        // Add the real torrent and start it.
        let (tx, rx) = channel();
        let added = engine.request(
            Cmd::AddTorrent {
                data,
                save_dir: dir.to_str().unwrap().to_string(),
                file_priorities: Vec::new(),
                tx,
            },
            rx,
            Duration::from_secs(20),
        );
        let hash = match added {
            Some(Ok(h)) => h,
            Some(Err(e)) => {
                eprintln!("diag: add failed: {e}");
                engine.shutdown();
                return;
            }
            None => {
                eprintln!("diag: add timed out");
                engine.shutdown();
                return;
            }
        };
        let (tx, rx) = channel();
        let _ = engine.request(
            Cmd::Start {
                hash: hash.clone(),
                tx,
            },
            rx,
            Duration::from_secs(20),
        );

        let deadline = std::time::Instant::now() + Duration::from_secs(minutes * 60);
        let mut panics: Vec<String> = Vec::new();
        let mut last_dht: i64 = -1;
        let mut report_at = std::time::Instant::now();
        eprintln!("diag: started engine, hunting panics for {minutes} min (hash {hash})");

        while std::time::Instant::now() < deadline {
            let evs: Vec<String> = {
                let mut q = engine.events.lock().unwrap();
                q.drain(..).collect()
            };
            for e in &evs {
                if e.contains("\"t\":11") && e.contains("\"code\":2") {
                    panics.push(e.clone());
                    eprintln!("diag: *** PANIC EVENT: {e}");
                }
            }
            // Snapshot every ~2s: DHT count + progress.
            if report_at.elapsed() >= Duration::from_secs(2) {
                report_at = std::time::Instant::now();
                let (tx, rx) = channel();
                if let Some(snap) =
                    engine.request(Cmd::Snapshot { tx }, rx, Duration::from_secs(10))
                {
                    let dht = snap
                        .split("\"dht\":")
                        .nth(1)
                        .and_then(|s| s.split(',').next())
                        .and_then(|s| s.trim().parse::<i64>().ok())
                        .unwrap_or(-1);
                    if dht != last_dht {
                        last_dht = dht;
                        eprintln!("diag: DHT nodes = {dht}");
                    }
                    if panics.len() >= 3 {
                        break;
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(200));
        }

        // Final event drain.
        let evs: Vec<String> = {
            let mut q = engine.events.lock().unwrap();
            q.drain(..).collect()
        };
        for e in &evs {
            if e.contains("\"t\":11") && e.contains("\"code\":2") {
                panics.push(e.clone());
                eprintln!("diag: *** PANIC EVENT: {e}");
            }
        }
        eprintln!("diag: done. recovered panics seen: {}", panics.len());
        for p in &panics {
            eprintln!("diag:   {p}");
        }

        engine.shutdown();
        let _ = fs::remove_dir_all(&dir);
        // This is a diagnostic: it never asserts; it only prints evidence.
    }

    /// Diagnostic: add a REAL MAGNET and watch whether metadata ever arrives.
    /// Prints DHT count, peer-connection events, engine notices and the
    /// snapshot's meta flag every 2 s. This is the reproduction path for the
    /// "stuck at metadata while qBittorrent succeeds" report — the swarm is
    /// mostly uTP/XunLei peers, so it also exercises the uTP dial path.
    ///
    /// Magnet: `$env:TBT_DIAG_MAGNET` or the Kali netinst magnet with
    /// several trackers. Duration: `$env:TBT_DIAG_MINUTES` (default 2).
    #[test]
    #[ignore = "network diagnostic; run with `cargo test -- --ignored`"]
    fn diag_magnet_metadata() {
        let _guard = engine_test_lock();
        let magnet = std::env::var("TBT_DIAG_MAGNET").unwrap_or_else(|_| {
            "magnet:?xt=urn:btih:2f3e884b9f97b376e4c8abbdf1e446889da6bcbc\
             &dn=kali-linux-2026.2-installer-netinst-amd64.iso\
             &tr=http%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce\
             &tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce\
             &tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce\
             &tr=udp%3A%2F%2Fexodus.desync.com%3A6969%2Fannounce"
                .to_string()
        });
        let minutes = std::env::var("TBT_DIAG_MINUTES")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(2);

        let dir = std::env::temp_dir().join(format!("typebit_magnet_diag_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let logs: LogBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let engine = spawn_engine("{}", dir.to_str().unwrap(), logs.clone()).expect("spawn");

        let (tx, rx) = channel();
        let added = engine.request(
            Cmd::AddMagnet {
                uri: magnet.clone(),
                save_dir: dir.to_str().unwrap().to_string(),
                tx,
            },
            rx,
            Duration::from_secs(20),
        );
        let hash = match added {
            Some(Ok(h)) => h,
            Some(Err(e)) => {
                eprintln!("diag-magnet: add failed: {e}");
                engine.shutdown();
                return;
            }
            None => {
                eprintln!("diag-magnet: add timed out");
                engine.shutdown();
                return;
            }
        };
        let (tx, rx) = channel();
        let _ = engine.request(
            Cmd::Start {
                hash: hash.clone(),
                tx,
            },
            rx,
            Duration::from_secs(20),
        );

        let start = std::time::Instant::now();
        let deadline = start + Duration::from_secs(minutes * 60);
        let mut report_at = std::time::Instant::now();
        let mut last_dht: i64 = -1;
        let mut metadata_done = false;
        eprintln!("diag-magnet: hash {hash} — hunting metadata for {minutes} min");

        while std::time::Instant::now() < deadline {
            let evs: Vec<String> = {
                let mut q = engine.events.lock().unwrap();
                q.drain(..).collect()
            };
            for e in &evs {
                if e.contains("\"t\":1") {
                    eprintln!("diag-magnet: PEER CONNECTED: {e}");
                }
                if e.contains("\"t\":5") {
                    metadata_done = true;
                    eprintln!("diag-magnet: *** METADATA COMPLETE: {e}");
                }
                if e.contains("\"t\":7") {
                    eprintln!("diag-magnet: PEER COUNT: {e}");
                }
                if e.contains("\"t\":11") {
                    eprintln!("diag-magnet: ENGINE NOTICE: {e}");
                }
            }
            if report_at.elapsed() >= Duration::from_secs(2) {
                report_at = std::time::Instant::now();
                let mut lq = logs.lock().unwrap();
                while let Some((_lvl, msg)) = lq.pop_front() {
                    if msg.contains("DIAG") || msg.contains("utp") || msg.contains("drop") {
                        eprintln!("diag-magnet: LOG {msg}");
                    }
                }
                drop(lq);
                let (tx, rx) = channel();
                if let Some(snap) =
                    engine.request(Cmd::Snapshot { tx }, rx, Duration::from_secs(10))
                {
                    let dht = snap
                        .split("\"dht\":")
                        .nth(1)
                        .and_then(|s| s.split(',').next())
                        .and_then(|s| s.trim().parse::<i64>().ok())
                        .unwrap_or(-1);
                    if dht != last_dht {
                        last_dht = dht;
                        eprintln!("diag-magnet: DHT nodes = {dht}");
                    }
                    let meta = snap.contains("\"meta\":true");
                    let has_name = snap.contains("\"name\":\"kali");
                    if !metadata_done && meta {
                        metadata_done = true;
                        eprintln!("diag-magnet: snapshot reports meta=true");
                    }
                    let (tx2, rx2) = channel();
                    let peers = engine
                        .request(
                            Cmd::Peers {
                                hash: hash.clone(),
                                tx: tx2,
                            },
                            rx2,
                            Duration::from_secs(10),
                        )
                        .unwrap_or_default();
                    let peer_n = peers.matches("\"addr\"").count();
                    let secs = start.elapsed().as_secs();
                    eprintln!(
                        "diag-magnet: t+{secs:>4}s dht={dht:>3} peers={peer_n} meta={meta} name_ok={has_name}"
                    );
                }
                if metadata_done {
                    eprintln!("diag-magnet: metadata arrived — stopping early");
                    break;
                }
            }
            std::thread::sleep(Duration::from_millis(200));
        }

        let (tx, rx) = channel();
        let raw = engine.request(
            Cmd::TorrentInfoRaw {
                hash: hash.clone(),
                tx,
            },
            rx,
            Duration::from_secs(10),
        );
        eprintln!(
            "diag-magnet: done. metadata={metadata_done} dht_final={last_dht} info_raw_len={}",
            raw.and_then(|v| v).map(|v| v.len()).unwrap_or(0)
        );

        engine.shutdown();
        let _ = fs::remove_dir_all(&dir);
        // Diagnostic only — never asserts.
    }

    /// Diagnostic: watch the DHT routing table grow from a bare engine
    /// (no torrents) over `TBT_DIAG_SECONDS` (default 60). Validates the
    /// bootstrap flow end-to-end: async DNS → `dht.bootstrap(seeds)` →
    /// ping responses → the table snowballs past `DHT_REBOOTSTRAP_THRESHOLD`.
    /// Prints evidence; never asserts.
    #[test]
    #[ignore = "network diagnostic; run with `cargo test -- --ignored`"]
    fn diag_dht_bootstrap_growth() {
        let _guard = engine_test_lock();
        let seconds = std::env::var("TBT_DIAG_SECONDS")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(60);

        let dir = std::env::temp_dir().join(format!("typebit_dht_diag_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let logs: LogBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let engine = spawn_engine("{}", dir.to_str().unwrap(), logs.clone()).expect("spawn");
        let start = std::time::Instant::now();
        let deadline = start + Duration::from_secs(seconds);
        let mut report_at = std::time::Instant::now();
        let mut peak: i64 = 0;
        eprintln!("diag-dht: observing DHT table growth for {seconds}s (no torrents)");

        while std::time::Instant::now() < deadline {
            // Drain engine notices (dht_no_seeds etc.).
            let evs: Vec<String> = {
                let mut q = engine.events.lock().unwrap();
                q.drain(..).collect()
            };
            for e in &evs {
                if e.contains("\"t\":11") {
                    eprintln!("diag-dht: ENGINE NOTICE: {e}");
                }
            }
            if report_at.elapsed() >= Duration::from_secs(5) {
                report_at = std::time::Instant::now();
                let (tx, rx) = channel();
                if let Some(snap) =
                    engine.request(Cmd::Snapshot { tx }, rx, Duration::from_secs(10))
                {
                    let dht = snap
                        .split("\"dht\":")
                        .nth(1)
                        .and_then(|s| s.split(',').next())
                        .and_then(|s| s.trim().parse::<i64>().ok())
                        .unwrap_or(-1);
                    peak = peak.max(dht);
                    eprintln!(
                        "diag-dht: t+{:>4}s dht={dht:>3} (peak {peak})",
                        start.elapsed().as_secs()
                    );
                }
            }
            std::thread::sleep(Duration::from_millis(250));
        }

        eprintln!("diag-dht: done. final dht peak={peak}");
        engine.shutdown();
        let _ = fs::remove_dir_all(&dir);
    }

    /// Diagnostic: raw UDP DHT round-trip through `NativeHost` — bind the
    /// UDP socket, send a valid `ping` query to `router.bittorrent.com`
    /// (and `dht.transmissionbt.com`), then wait up to 5 s for a reply.
    /// Decides whether a "DHT stuck at 1 node" report is a network/UDP
    /// problem or an engine-flow problem. Prints evidence; never asserts.
    #[test]
    #[ignore = "network diagnostic; run with `cargo test -- --ignored`"]
    fn diag_udp_dht_roundtrip() {
        use crate::host::NativeHost as NH;
        use typebit::platform::Host;
        let logs: LogBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let mut host = NH::new(logs.clone());
        match host.udp_open(0) {
            Ok(()) => eprintln!("diag-udp: udp_open OK"),
            Err(e) => {
                eprintln!("diag-udp: udp_open FAILED: {e}");
                return;
            }
        }
        // Drain host logs for "UDP bound".
        {
            let mut q = logs.lock().unwrap();
            while let Some((_l, m)) = q.pop_front() {
                if m.contains("UDP") {
                    eprintln!("diag-udp: hostlog {m}");
                }
            }
        }
        let routers: &[(&str, u16)] = &[
            ("router.bittorrent.com", 6881),
            ("dht.transmissionbt.com", 6881),
            ("router.utorrent.com", 6881),
            ("dht.libtorrent.org", 25401),
        ];
        let mut id = [0u8; 20];
        id.fill(0x11);
        for (hostname, port) in routers {
            {
                let mut q = logs.lock().unwrap();
                while let Some((_l, m)) = q.pop_front() {
                    if m.contains("udp") || m.contains("UDP") {
                        eprintln!("diag-udp: hostlog {m}");
                    }
                }
            }
            let Some(addr) = host.resolve_host(hostname, *port) else {
                eprintln!("diag-udp: {hostname}:{port} UNRESOLVED");
                continue;
            };
            let mut req = Vec::with_capacity(64);
            req.extend_from_slice(b"d1:ad2:id20:");
            req.extend_from_slice(&id);
            req.extend_from_slice(b"e1:q4:ping1:t2:aa1:y1:qe");
            match host.udp_send(&addr, &req) {
                Ok(()) => eprintln!("diag-udp: ping sent to {hostname}:{port} ({addr})"),
                Err(e) => {
                    eprintln!("diag-udp: ping send to {hostname}:{port} FAILED: {e:?}");
                    continue;
                }
            }
            // Wait up to 5 s for a reply.
            let mut reply = [0u8; 2048];
            let mut got = None;
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            while std::time::Instant::now() < deadline {
                match host.udp_recv(&mut reply) {
                    Ok((src, n)) => {
                        eprintln!(
                            "diag-udp: reply from {src} len={n} prefix={:?}",
                            &reply[..n.min(8)]
                        );
                        got = Some(n);
                        break;
                    }
                    Err(typebit::error::Error::WouldBlock) => {
                        std::thread::sleep(Duration::from_millis(200));
                    }
                    Err(e) => {
                        eprintln!("diag-udp: recv err {e}");
                        break;
                    }
                }
            }
            if got.is_none() {
                eprintln!("diag-udp: {hostname}:{port} NO REPLY in 5s");
            }
        }
    }
}
