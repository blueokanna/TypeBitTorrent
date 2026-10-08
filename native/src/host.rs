//! NativeHost — a complete `typebit::Host` implementation backed by std.
//!
//! Everything the engine needs from the OS is implemented here:
//!
//! * **TCP** — outbound connects run on helper threads with a bounded
//!   timeout and are handed back through a channel (so `tcp_connect` never
//!   blocks the engine); established streams are non-blocking. Inbound
//!   connections are accepted by the engine thread via [`NativeHost::accept_pending`].
//! * **UDP** — one non-blocking socket for DHT + UDP trackers.
//! * **HTTP(S)** — delegated to `typebit::host_std::StdHost`, which wraps the
//!   in-tree `courierust` client (its TLS is built-in, no system deps).
//! * **Disk** — `std::fs` with `set_len` preallocation and `sync_data` flush.
//! * **Wire counters** — total downloaded/uploaded bytes for the status bar.
//!
//! Global speed limits are enforced **by the engine itself** since
//! `typebit 0.1.1` ships built-in token-bucket rate limiting
//! (`EngineConfig::global_*_limit_bps`), so the host no longer shapes
//! traffic — it only counts it.
//!
//! The whole struct is owned by the single engine thread; the only shared
//! state is the log ring buffer.

use std::collections::HashMap;
use std::collections::VecDeque;
use std::io::{Read, Seek, SeekFrom, Write};
use std::net::{
    Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6, TcpListener, TcpStream, UdpSocket,
};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant, SystemTime};

use crate::dns::{self, DnsService};
use crate::netpolicy::NetworkPolicy;
use typebit::platform::{ConnId, DiskId, Host, LogLevel, NetAddr};
use typebit::{Error, Result};

/// Cap on outbound connects still resolving (back-pressure for reconnect).
const MAX_PENDING_CONNECTS: usize = 512;
/// Cap on open peer connections (flood bound).
const MAX_OPEN_CONNS: usize = 16 * 1024;
/// Cap on open files (defensive against hostile torrents).
const MAX_OPEN_FILES: usize = 4096;
/// Helper-thread connect timeout.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// Log ring capacity.
const LOG_CAPACITY: usize = 2048;
/// Cap on concurrent in-flight HTTP jobs (bounds abandoned threads when a
/// server hangs past its timeouts).
const MAX_HTTP_ACTIVE: usize = 8;
/// HTTP worker threads. Four is enough to hide provider latency: tracker
/// announces are a handful of requests per torrent per interval, and web-seed
/// ranges to the same host multiplex over one HTTP/2 connection anyway.
const HTTP_WORKERS: usize = 4;

/// How long `local_ip` is trusted before the interface list is consulted again.
const LOCAL_IP_TTL: Duration = Duration::from_secs(30);

/// Shared log ring: `(level, message)` pairs, oldest first.
pub type LogBuffer = Arc<Mutex<VecDeque<(u8, String)>>>;

/// Appends one line to the log ring.
///
/// A free function because the host needs it *before* it exists: the trust
/// store and the DNS configuration are resolved while `NativeHost` is being
/// built, and a host with no way to report why HTTPS is unavailable is a host
/// whose user has nothing to act on.
fn push_log(logs: &LogBuffer, level: LogLevel, msg: &str) {
    if let Ok(mut q) = logs.lock() {
        if q.len() >= LOG_CAPACITY {
            q.pop_front();
        }
        q.push_back((level as u8, msg.to_string()));
    }
}

/// Connection bookkeeping on the engine thread.
enum ConnSlot {
    /// Established, non-blocking stream.
    Established(TcpStream),
    /// Outbound connect still running on a helper thread.
    Connecting,
}

/// A completed outbound connect handed back from a helper thread.
type ConnectResult = (ConnId, std::io::Result<TcpStream>);

/// One queued HTTP job for the async worker.
struct HttpJob {
    id: u64,
    url: String,
    range: Option<(u64, u64)>,
    /// POST body (UPnP SOAP). `None` = GET.
    post_body: Option<Vec<u8>>,
    timeout_ms: u64,
}

/// Handle to the shared async HTTP worker pool.
struct HttpWorkerHandle {
    queue: Arc<JobQueue<HttpJob>>,
    done_rx: Receiver<(u64, Result<Vec<u8>>)>,
}

/// A two-priority job queue shared with a worker pool.
///
/// Two FIFOs instead of one: a tracker announce is a few hundred bytes that
/// must go out now (it is what makes peers appear), while a web-seed range is
/// bulk data. With a single FIFO a swarm of range fetches starves announces,
/// which looks exactly like "my torrents stopped finding peers".
struct JobQueue<T> {
    state: Mutex<JobQueueState<T>>,
    signal: Condvar,
}

struct JobQueueState<T> {
    interactive: VecDeque<T>,
    bulk: VecDeque<T>,
    /// Set when the owner is tearing down; workers then drain and exit.
    closing: bool,
}

impl<T> JobQueue<T> {
    fn new() -> Self {
        JobQueue {
            state: Mutex::new(JobQueueState {
                interactive: VecDeque::new(),
                bulk: VecDeque::new(),
                closing: false,
            }),
            signal: Condvar::new(),
        }
    }

    fn push_interactive(&self, job: T) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.closing {
            return;
        }
        state.interactive.push_back(job);
        self.signal.notify_one();
    }

    fn push_bulk(&self, job: T) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.closing {
            return;
        }
        state.bulk.push_back(job);
        self.signal.notify_one();
    }

    /// Blocks until a job is available, the queue closes, or `timeout` passes.
    fn pop(&self, timeout: Duration) -> Option<T> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if let Some(job) = state.interactive.pop_front() {
                return Some(job);
            }
            if let Some(job) = state.bulk.pop_front() {
                return Some(job);
            }
            if state.closing {
                return None;
            }
            let (guard, wait) = self
                .signal
                .wait_timeout(state, timeout)
                .unwrap_or_else(|e| e.into_inner());
            state = guard;
            if wait.timed_out() {
                return None;
            }
        }
    }

    fn close(&self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.closing = true;
        state.interactive.clear();
        state.bulk.clear();
        self.signal.notify_all();
    }
}

/// One queued DNS resolution job for the async resolver.
struct ResolveJob {
    host: String,
    port: u16,
}

/// Handle to the shared async DNS resolver pool.
struct ResolveWorkerHandle {
    queue: Arc<JobQueue<ResolveJob>>,
    done_rx: Receiver<(String, u16, Vec<NetAddr>)>,
}

/// The async DNS resolver.
///
/// Each job runs on its OWN bounded thread, so a single hung or blocked domain
/// (common on restricted networks — several BEP-5 router hostnames hang for the
/// full OS DNS timeout) can never stall the resolution of the other bootstrap
/// routers behind it. Without that, a six-host bootstrap could take minutes
/// serially even though the one reachable router resolves in milliseconds.
///
/// Unlike the HTTP pool, these threads are per-request: DNS resolution is
/// blocking by nature and the resolver has no queue to starve. They are capped
/// so a hung resolver cannot multiply into unbounded threads, and DoH lookups
/// (which are what actually need the parallelism) run inside the resolver,
/// not here.
fn resolve_worker_loop(
    queue: &JobQueue<ResolveJob>,
    done_tx: &Sender<(String, u16, Vec<NetAddr>)>,
    dns: &DnsService,
) {
    let active = Arc::new(AtomicUsize::new(0));
    while let Some(job) = queue.pop(Duration::from_millis(500)) {
        while active.load(Ordering::SeqCst) >= MAX_HTTP_ACTIVE {
            std::thread::sleep(Duration::from_millis(5));
        }
        active.fetch_add(1, Ordering::SeqCst);
        let host_for_err = job.host.clone();
        let port_for_err = job.port;
        let worker_done_tx = done_tx.clone();
        let worker_active = active.clone();
        let dns = dns.clone();
        let spawned = std::thread::Builder::new()
            .name("typebit-resolve-job".to_string())
            .spawn(move || {
                // The full resolution path — resolver, then OS resolver, then
                // memo — on a thread of its own.
                let resolved = dns.resolve(&job.host, job.port, Instant::now()).addrs;
                let _ = worker_done_tx.send((job.host, job.port, resolved));
                worker_active.fetch_sub(1, Ordering::SeqCst);
            });
        if spawned.is_err() {
            // Report the job as unresolved (soft failure) without leaking the
            // active slot: the DHT bootstrap then simply tries again later.
            active.fetch_sub(1, Ordering::SeqCst);
            let _ = done_tx.send((host_for_err, port_for_err, Vec::new()));
        }
    }
}

/// The complete std-backed host.
pub struct NativeHost {
    listener: Option<TcpListener>,
    udp: Option<UdpSocket>,
    /// Dedicated LSD (BEP-14) socket bound to port 6771 so LAN multicast
    /// announces are actually received (the shared UDP socket is bound to
    /// the BT listen port and can never hear them).
    lsd_udp: Option<UdpSocket>,
    conns: HashMap<ConnId, ConnSlot>,
    next_conn: ConnId,
    next_disk: DiskId,
    established_rx: Receiver<ConnectResult>,
    established_tx: Sender<ConnectResult>,
    pending_connects: usize,
    files: HashMap<DiskId, std::fs::File>,
    /// Per-file allocation mode set by `disk_set_alloc`: 0=off, 1=sparse,
    /// 2=full. Consulted by `disk_prealloc` so the native side can commit
    /// the full extent only when the user asked for it.
    alloc_mode: HashMap<DiskId, u8>,
    /// Async HTTP worker (lazily spawned); lets the engine submit tracker
    /// announces and web-seed fetches without ever blocking on HTTP.
    http_worker: Option<HttpWorkerHandle>,
    /// Async DNS resolver (lazily spawned); lets the engine bootstrap the
    /// DHT from the BEP-5 router hostnames without blocking on DNS.
    resolve_worker: Option<ResolveWorkerHandle>,
    /// Completed HTTP jobs not yet handed to the engine.
    http_pending_results: VecDeque<(u64, Result<Vec<u8>>)>,
    /// Monotonic job id allocator (1-based).
    next_http_job: u64,
    /// What this host is allowed to fetch, and how names are resolved.
    policy: NetworkPolicy,
    /// DoH + cache + single-flight. Shared with the resolver workers and the
    /// HTTP workers, so one lookup serves all of them.
    dns: DnsService,
    /// The last LAN address we reported, and when. `local_ip` is called by the
    /// port mapper on every attempt; a UDP socket pair per call is pure
    /// syscall overhead.
    local_ip_cache: Option<(NetAddr, std::time::Instant)>,
    /// Cumulative wire bytes (downloaded, uploaded) for the status bar.
    down_total: u64,
    up_total: u64,
    logs: LogBuffer,
}

impl NativeHost {
    pub fn new(logs: LogBuffer) -> Self {
        Self::with_policy(logs, NetworkPolicy::default())
    }

    /// Builds a host under an explicit [`NetworkPolicy`].
    ///
    /// The outbound TLS stack is `courierust`'s own (in-tree TLS, no system
    /// dependency), but the *trust anchors* come from
    /// [`crate::tlsroots`] — `courierust` refuses to guess, and an `https://`
    /// URL under an unconfigured client is rejected outright rather than
    /// silently downgraded. The DNS service is built from the same policy, so
    /// one settings screen decides both.
    pub fn with_policy(logs: LogBuffer, policy: NetworkPolicy) -> Self {
        let (established_tx, established_rx) = channel();
        let family = if policy.ipv6 && dns::has_ipv6() {
            dns::IpFamily::Any
        } else {
            dns::IpFamily::V4Only
        };
        let dns = DnsService::new(&policy, family);
        match crate::tlsroots::anchors() {
            Ok(anchors) => push_log(
                &logs,
                LogLevel::Info,
                &format!(
                    "TLS 信任库: {} 个根证书（{}）",
                    anchors.count, anchors.source
                ),
            ),
            Err(why) => push_log(
                &logs,
                LogLevel::Warn,
                &format!("无可用 TLS 信任库，HTTPS 不可用: {why}"),
            ),
        }
        for problem in dns.problems() {
            push_log(&logs, LogLevel::Warn, &format!("DNS 配置: {problem}"));
        }
        push_log(
            &logs,
            LogLevel::Info,
            &format!("DNS 模式: {}", dns.stats().summary()),
        );
        NativeHost {
            listener: None,
            udp: None,
            lsd_udp: None,
            conns: HashMap::new(),
            next_conn: 1,
            next_disk: 1,
            established_rx,
            established_tx,
            pending_connects: 0,
            files: HashMap::new(),
            alloc_mode: HashMap::new(),
            http_worker: None,
            resolve_worker: None,
            http_pending_results: VecDeque::new(),
            next_http_job: 0,
            policy,
            dns,
            local_ip_cache: None,
            down_total: 0,
            up_total: 0,
            logs,
        }
    }

    /// Bind the TCP listener for inbound peer connections.
    ///
    /// Returns the actual bound port (falls back to an OS-assigned port when
    /// the requested one is taken — logged as a warning).
    pub fn bind_tcp(&mut self, port: u16) -> u16 {
        if let Some(listener) = &self.listener {
            return listener.local_addr().map(|a| a.port()).unwrap_or(port);
        }
        let bind = || -> std::io::Result<TcpListener> {
            let addr = format!("0.0.0.0:{port}")
                .parse::<SocketAddr>()
                .map_err(std::io::Error::other)?;
            TcpListener::bind(addr)
        };
        match bind() {
            Ok(l) => {
                let _ = l.set_nonblocking(true);
                let actual = l.local_addr().map(|a| a.port()).unwrap_or(port);
                self.listener = Some(l);
                self.log_internal(LogLevel::Info, &format!("TCP listening on {actual}"));
                actual
            }
            Err(e) => {
                self.log_internal(
                    LogLevel::Warn,
                    &format!("bind tcp {port} failed ({e}); falling back to ephemeral"),
                );
                match TcpListener::bind("0.0.0.0:0") {
                    Ok(l) => {
                        let _ = l.set_nonblocking(true);
                        let actual = l.local_addr().map(|a| a.port()).unwrap_or(0);
                        self.listener = Some(l);
                        self.log_internal(
                            LogLevel::Warn,
                            &format!("TCP listening on ephemeral port {actual}"),
                        );
                        actual
                    }
                    Err(_) => {
                        self.log_internal(LogLevel::Error, "unable to bind any TCP listener");
                        0
                    }
                }
            }
        }
    }

    /// The actual TCP port we are bound to (0 = not listening). This is the
    /// port inbound peers connect to and therefore the one a firewall rule
    /// must open — it differs from the configured port in random-port mode.
    pub fn listen_port(&self) -> u16 {
        self.listener
            .as_ref()
            .and_then(|l| l.local_addr().ok())
            .map(|a| a.port())
            .unwrap_or(0)
    }

    /// Accept all pending inbound connections (non-blocking drain).
    /// Returns `(conn_id, addr)` pairs for `Engine::on_inbound_connection`.
    pub fn accept_pending(&mut self) -> Vec<(ConnId, NetAddr)> {
        let mut out = Vec::new();
        let Some(listener) = self.listener.as_ref() else {
            return out;
        };
        loop {
            if self.conns.len() >= MAX_OPEN_CONNS {
                // Flood bound reached: stop accepting (kernel buffers the rest).
                break;
            }
            match listener.accept() {
                Ok((stream, addr)) => {
                    let _ = stream.set_nonblocking(true);
                    let id = self.next_conn;
                    self.next_conn = self.next_conn.wrapping_add(1);
                    self.conns.insert(id, ConnSlot::Established(stream));
                    out.push((id, sock_to_netaddr(addr)));
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(_) => break,
            }
        }
        out
    }

    /// Collect completed outbound connects from the helper threads.
    pub fn drain_established(&mut self) {
        while let Ok((id, res)) = self.established_rx.try_recv() {
            self.pending_connects = self.pending_connects.saturating_sub(1);
            match res {
                Ok(stream) => {
                    let _ = stream.set_nonblocking(true);
                    self.conns.insert(id, ConnSlot::Established(stream));
                }
                Err(_) => {
                    self.conns.remove(&id);
                }
            }
        }
    }

    /// Close everything (engine teardown).
    ///
    /// The queues are closed first: the pools then drop their queued work and
    /// exit instead of holding sockets open after the engine has stopped. The
    /// resolver's maintenance thread is joined last — it is the one background
    /// thread that would otherwise survive an engine restart.
    pub fn shutdown(&mut self) {
        if let Some(h) = self.http_worker.as_ref() {
            h.queue.close();
        }
        if let Some(h) = self.resolve_worker.as_ref() {
            h.queue.close();
        }
        self.dns.shutdown();
        self.listener = None;
        self.udp = None;
        self.lsd_udp = None;
        self.conns.clear();
        self.files.clear();
    }

    /// Global counters for the status bar: (down_total, up_total).
    pub fn totals(&self) -> (u64, u64) {
        (self.down_total, self.up_total)
    }

    fn log_internal(&mut self, level: LogLevel, msg: &str) {
        let lvl = level as u8;
        if let Ok(mut q) = self.logs.lock() {
            if q.len() >= LOG_CAPACITY {
                q.pop_front();
            }
            q.push_back((lvl, msg.to_string()));
        }
    }

    fn conn(&mut self, id: ConnId) -> Option<&mut TcpStream> {
        match self.conns.get_mut(&id) {
            Some(ConnSlot::Established(s)) => Some(s),
            _ => None,
        }
    }
}

impl NativeHost {
    /// Map an engine file path to its staging path. The engine always writes
    /// through `<final>.part` so a half-downloaded file is never visible
    /// under its final name; only after every piece has been hash-verified
    /// (`TorrentComplete`) does the bridge promote it with
    /// [`Self::finalize_file`].
    fn stage_path(path: &str) -> String {
        format!("{path}.part")
    }

    /// Resolve the real on-disk path for an engine file:
    ///   1. an in-progress staging file (`<final>.part`) — resume continues there;
    ///   2. a completed file (`<final>`) left by a previous run — seed from it;
    ///   3. otherwise a fresh staging file is created.
    fn resolve_disk_path(final_path: &str) -> String {
        let stage = Self::stage_path(final_path);
        if std::path::Path::new(&stage).exists() {
            stage
        } else if std::path::Path::new(final_path).exists() {
            final_path.to_string()
        } else {
            stage
        }
    }

    /// Promote a fully-verified staging file (`<final>.part`) to its final
    /// name. Called by the bridge on `TorrentComplete`, when every piece of
    /// that torrent has been hash-checked. No-op and idempotent when the
    /// staging file is absent (e.g. a file the user skipped). Windows note:
    /// Rust opens files with `FILE_SHARE_DELETE`, so renaming an open
    /// (seeding) file is allowed.
    pub fn finalize_file(&mut self, final_path: &str) {
        let stage = Self::stage_path(final_path);
        if !std::path::Path::new(&stage).exists() {
            return;
        }
        match std::fs::rename(&stage, final_path) {
            Ok(()) => self.log_internal(LogLevel::Info, &format!("finalized {final_path}")),
            Err(e) => self.log_internal(
                LogLevel::Warn,
                &format!("finalize {final_path} failed: {e}"),
            ),
        }
    }

    // ---------- async HTTP worker ----------

    /// Lazily spawn the shared HTTP worker pool (one per host, never per
    /// request). The pool owns one bounded-timeout `courierust` client, so
    /// connections and HTTP/2 streams are reused across every tracker announce
    /// and web-seed fetch in the session.
    fn ensure_http_worker(&mut self) {
        if self.http_worker.is_some() {
            return;
        }
        let queue = Arc::new(JobQueue::<HttpJob>::new());
        let (done_tx, done_rx) = channel();
        let clients = Arc::new(HttpClients::new(&self.policy));
        let dns = self.dns.clone();
        let policy = self.policy.clone();
        for i in 0..HTTP_WORKERS {
            let queue = queue.clone();
            let done_tx = done_tx.clone();
            let client = clients.clone();
            let dns = dns.clone();
            let policy = policy.clone();
            let spawned = std::thread::Builder::new()
                .name(format!("typebit-http{i}"))
                .spawn(move || http_worker_loop(&queue, &done_tx, &client, &dns, &policy));
            if spawned.is_err() {
                break;
            }
        }
        self.http_worker = Some(HttpWorkerHandle { queue, done_rx });
    }

    fn next_http_job_id(&mut self) -> u64 {
        self.next_http_job = self.next_http_job.wrapping_add(1).max(1);
        self.next_http_job
    }

    /// Enqueue an async HTTP job; returns the job id or 0 when no pool exists.
    fn enqueue_http_job(
        &mut self,
        url: &str,
        range: Option<(u64, u64)>,
        post_body: Option<Vec<u8>>,
        timeout_ms: u64,
        interactive: bool,
    ) -> u64 {
        self.ensure_http_worker();
        let id = self.next_http_job_id();
        let h = match self.http_worker.as_ref() {
            Some(h) => h,
            None => return 0,
        };
        let job = HttpJob {
            id,
            url: url.to_string(),
            range,
            post_body,
            timeout_ms,
        };
        if interactive {
            h.queue.push_interactive(job);
        } else {
            h.queue.push_bulk(job);
        }
        id
    }

    /// Move completed jobs off the worker channel into the pending buffer
    /// and return everything pending (the engine routes them by id).
    fn http_drain_done(&mut self) -> VecDeque<(u64, Result<Vec<u8>>)> {
        if let Some(h) = self.http_worker.as_ref() {
            while let Ok(item) = h.done_rx.try_recv() {
                self.http_pending_results.push_back(item);
            }
        }
        std::mem::take(&mut self.http_pending_results)
    }

    /// Wait (bounded by `timeout_ms`) for one specific async job and append
    /// its body to `out`. Used by the synchronous hooks the engine calls on
    /// its own thread (`http_get` / `http_get_range` fallbacks).
    ///
    /// Results for other jobs are kept in the pending buffer, so the engine's
    /// `http_take_done` still delivers them.
    fn wait_http_job(&mut self, id: u64, timeout_ms: u64, out: &mut Vec<u8>) -> Result<()> {
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.max(1));
        let mut stolen: Vec<(u64, Result<Vec<u8>>)> = Vec::new();
        let mut result: Option<Result<Vec<u8>>> = None;
        if let Some(h) = self.http_worker.as_ref() {
            loop {
                // Our job may already be sitting in the pending buffer.
                if let Some(pos) = self
                    .http_pending_results
                    .iter()
                    .position(|(jid, _)| *jid == id)
                {
                    if let Some(item) = remove_at(&mut self.http_pending_results, pos) {
                        result = Some(item.1);
                        break;
                    }
                }
                let now = Instant::now();
                if now >= deadline {
                    break;
                }
                match h.done_rx.recv_timeout(deadline - now) {
                    Ok((jid, res)) => {
                        if jid == id {
                            result = Some(res);
                            break;
                        }
                        stolen.push((jid, res));
                    }
                    Err(RecvTimeoutError::Timeout) => break,
                    // Every worker is gone: the engine is shutting down.
                    Err(RecvTimeoutError::Disconnected) => break,
                }
            }
        }
        // Whatever we borrowed is handed back to the engine's own drain.
        for item in stolen {
            self.http_pending_results.push_back(item);
        }
        match result {
            Some(Ok(body)) => {
                out.extend_from_slice(&body);
                Ok(())
            }
            Some(Err(e)) => Err(e),
            None => Err(Error::Timeout),
        }
    }

    /// Runs a guarded request on the calling thread.
    ///
    /// Only used when no pool exists (a spawn failure); the pool path is the
    /// normal one. Blocking here is correct: the caller is the engine thread,
    /// which is exactly what the pool exists to keep out of.
    fn http_run_blocking(
        &mut self,
        url: &str,
        range: Option<(u64, u64)>,
        post_body: Option<&[u8]>,
        timeout_ms: u64,
        out: &mut Vec<u8>,
    ) -> Result<()> {
        let clients = HttpClients::new(&self.policy);
        let job = HttpJob {
            id: 0,
            url: url.to_string(),
            range,
            post_body: post_body.map(|b| b.to_vec()),
            timeout_ms,
        };
        match execute_job(clients.for_url(&job.url), &job, &self.dns, &self.policy) {
            Ok(body) => {
                out.extend_from_slice(&body);
                Ok(())
            }
            Err(e) => Err(e),
        }
    }

    /// Lazily spawn the shared async DNS resolver pool (one per host).
    fn ensure_resolve_worker(&mut self) {
        if self.resolve_worker.is_some() {
            return;
        }
        let queue = Arc::new(JobQueue::<ResolveJob>::new());
        let (done_tx, done_rx) = channel();
        let dns = self.dns.clone();
        for i in 0..2 {
            let queue = queue.clone();
            let done_tx = done_tx.clone();
            let dns = dns.clone();
            let spawned = std::thread::Builder::new()
                .name(format!("typebit-resolver{i}"))
                .spawn(move || resolve_worker_loop(&queue, &done_tx, &dns));
            if spawned.is_err() {
                break;
            }
        }
        self.resolve_worker = Some(ResolveWorkerHandle { queue, done_rx });
    }

    /// DNS counters for the log/stats surface.
    pub fn dns_stats(&self) -> crate::dns::DnsStats {
        self.dns.stats()
    }
}
/// The async DNS resolver: resolves each hostname on its OWN bounded thread
/// The HTTP clients this host uses, one per transport shape.
///
/// **Why two.** `courierust`'s `http2` flag selects HTTP/2 for *every* URL,
/// and for a cleartext one that means prior-knowledge h2c — which an ordinary
/// HTTP/1.1 tracker or web seed answers by dropping the connection. HTTP/2
/// therefore stays on for `https://` (where ALPN negotiates it and falls back
/// to 1.1 automatically) and off for `http://`, where 1.1 is the only wire
/// format the other side is guaranteed to speak. The connection pool, keep-alive
/// and stream reuse that matter for web-seed throughput all live in the h2
/// client, which is the one every HTTPS seed uses.
pub struct HttpClients {
    /// ALPN-negotiated HTTP/2 for `https://`: many range requests over one
    /// connection, with per-stream RFC 9218 priorities.
    secure: courierust::courierust_client::Client,
    /// HTTP/1.1 for `http://` (cleartext), keep-alive pooled.
    plain: courierust::courierust_client::Client,
}

impl HttpClients {
    fn new(policy: &NetworkPolicy) -> Self {
        HttpClients {
            secure: build_client(policy, true),
            plain: build_client(policy, false),
        }
    }

    /// The client for a URL's scheme.
    fn for_url(&self, url: &str) -> &courierust::courierust_client::Client {
        let secure = url
            .get(..8)
            .map(|s| s.eq_ignore_ascii_case("https://"))
            .unwrap_or(false);
        if secure {
            &self.secure
        } else {
            &self.plain
        }
    }
}

/// TLS settings for a client, from the platform trust store.
///
/// Split out from [`build_client`] because it is the security-relevant part and
/// the only part a test can pin without a certificate to hand: the roots are
/// the platform's, verification is on, and an unreadable store yields `None`
/// (the `https://`-refused path) rather than a client that trusts anything.
fn tls_settings(
    anchors: std::result::Result<&crate::tlsroots::TrustAnchors, &str>,
    http2: bool,
) -> Option<courierust::courierust_client::TlsSettings> {
    match anchors {
        Ok(anchors) => Some(courierust::courierust_client::TlsSettings {
            roots: anchors.roots.clone(),
            verify: true,
            alpn: if http2 {
                vec![b"h2".to_vec(), b"http/1.1".to_vec()]
            } else {
                vec![b"http/1.1".to_vec()]
            },
            ..Default::default()
        }),
        Err(_) => None,
    }
}

/// Builds an HTTP client: TLS settings, timeouts, retry policy and identity.
///
/// One client per transport shape for the whole session on purpose: its
/// connection pool, HTTP/2 streams and TLS sessions are the entire reason 50
/// announces per minute and hundreds of range requests do not turn into
/// hundreds of handshakes.
///
/// **TLS is not optional.** `courierust`'s `ClientConfig::default()` carries
/// `tls: None`, and its client rejects an `https://` URL under `tls: None`
/// outright ("https requires TLS settings") rather than sending it in
/// cleartext — a deliberate choice that has to be met with an equally
/// deliberate one here. See [`tls_settings`].
fn build_client(policy: &NetworkPolicy, http2: bool) -> courierust::courierust_client::Client {
    use courierust::courierust_client::ClientConfig;
    let secure = http2 && policy.http2;
    let config = ClientConfig {
        // Only meaningful for https:// (ALPN); cleartext stays HTTP/1.1 —
        // see `HttpClients`.
        http2: secure,
        http3: false,
        max_connections_per_host: 4,
        connect_timeout: Some(Duration::from_secs(6)),
        handshake_timeout: Some(Duration::from_secs(6)),
        read_timeout: Some(Duration::from_secs(20)),
        // Every request carries its own deadline from the engine
        // (`timeout_ms`), so this is only the ceiling for the ones that do not.
        max_redirects: policy.max_redirects,
        // Identify honestly: some private trackers reject unknown agents, and
        // a tracker operator deserves to know who is hammering them.
        user_agent: Some(format!("TypeBitTorrent/{}", VERSION)),
        // A tracker body is bencode and a web-seed body is blocks
        max_body: 32 * 1024 * 1024,
        // Idempotent GETs are retried on transport failure
        retry: Some(courierust::courierust_client::RetryPolicy {
            attempts: 2,
            base_backoff: Duration::from_millis(250),
            max_backoff: Duration::from_secs(1),
            retry_non_idempotent: false,
        }),
        tls: tls_settings(crate::tlsroots::anchors(), secure),
        ..Default::default()
    };
    courierust::courierust_client::Client::with_config(config)
}

/// The version this build reports in its User-Agent.
///
/// Kept next to the client builder because it is part of the wire contract:
/// `JNI_ABI` is the Kotlin↔Rust contract, this is the one trackers see.
const VERSION: &str = env!("CARGO_PKG_VERSION");

/// One worker of the HTTP pool: take the highest-priority job, run it, report.
fn http_worker_loop(
    queue: &JobQueue<HttpJob>,
    done_tx: &Sender<(u64, Result<Vec<u8>>)>,
    clients: &HttpClients,
    dns: &DnsService,
    policy: &NetworkPolicy,
) {
    while let Some(job) = queue.pop(Duration::from_millis(500)) {
        let res = execute_job(clients.for_url(&job.url), &job, dns, policy);
        if done_tx.send((job.id, res)).is_err() {
            // The host is gone; stop working through the backlog.
            break;
        }
    }
}

/// Guards a URL, then performs exactly one HTTP exchange.
///
/// Order matters: the URL is checked before anything is dialled, and the
/// address the name actually resolves to is checked before the connection is
/// made — a hostile `.torrent` naming `http://localtest.me/` (which resolves
/// to `127.0.0.1`) is refused here, not after the response.
fn execute_job(
    client: &courierust::courierust_client::Client,
    job: &HttpJob,
    dns: &DnsService,
    policy: &NetworkPolicy,
) -> Result<Vec<u8>> {
    use courierust::courierust_http::method::Method;

    let deadline = if job.timeout_ms == 0 {
        None
    } else {
        Some(Duration::from_millis(job.timeout_ms))
    };

    if let Err(reject) = policy.check_url(&job.url) {
        return Err(log_guard_reject(job, reject));
    }
    let host = crate::netpolicy::url_host_port(&job.url)
        .map(|(h, _)| h.to_string())
        .unwrap_or_default();
    let port = crate::netpolicy::url_port(&job.url).unwrap_or(80);

    if host.parse::<std::net::IpAddr>().is_err() {
        let now = Instant::now();
        let addrs = match dns.cached(&host, port, now) {
            Some(a) => a,
            None => dns.resolve_blocking_os(&host, port, now).addrs,
        };
        for addr in &addrs {
            if let Some(ip) = netaddr_ip(*addr) {
                if !policy.allows_address(ip) {
                    return Err(log_guard_reject(
                        job,
                        crate::netpolicy::UrlReject::BlockedAddress(crate::netpolicy::classify(ip)),
                    ));
                }
            }
        }
    }

    let mut builder = client.request(
        &job.url,
        match &job.post_body {
            Some(_) => Method::POST,
            None => Method::GET,
        },
    );
    if let Some(t) = deadline {
        builder = builder.timeout(t);
    }

    let interactive = job.range.is_none();
    builder = builder.priority(if interactive {
        courierust::courierust_h2::priority::Priority {
            urgency: 0,
            incremental: false,
        }
    } else {
        courierust::courierust_h2::priority::Priority {
            urgency: 5,
            incremental: true,
        }
    });
    if let Some((start, end)) = job.range {
        builder = builder.header("range", format!("bytes={start}-{end}"));
    }
    if let Some(body) = &job.post_body {
        builder = builder
            .header("content-type", "text/xml; charset=\"utf-8\"")
            .header("soapaction", "\"#AddPortMapping\"")
            .body(body.clone());
    }

    let resp = builder.send().map_err(|_| Error::Io)?;
    let status = resp.status.as_u16();
    match job.range {
        None => {
            if status != 200 {
                return Err(Error::Tracker);
            }
            resp.body
                .collect()
                .map(|b| b.to_vec())
                .map_err(|_| Error::Io)
        }
        Some((start, end)) => {
            let window = (end - start + 1) as usize;
            if status == 206 {
                let body = resp
                    .body
                    .collect_limited(window)
                    .map_err(|_| Error::Protocol)?;
                if body.len() != window {
                    return Err(Error::Protocol);
                }
                return Ok(body.to_vec());
            }
            if status != 200 {
                return Err(Error::Tracker);
            }
            let declared = resp
                .headers
                .get("content-length")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.trim().parse::<u64>().ok());
            if start == 0 && declared == Some(end + 1) {
                let body = resp
                    .body
                    .collect_limited(window)
                    .map_err(|_| Error::Protocol)?;
                if body.len() == window {
                    return Ok(body.to_vec());
                }
            }
            Err(Error::Protocol)
        }
    }
}

/// Logs a refused request and turns it into the error the engine expects.
///
/// The URL is deliberately *not* logged in full: it is attacker-influenced
/// text and the host is the only part that matters for diagnosis.
fn log_guard_reject(job: &HttpJob, reject: crate::netpolicy::UrlReject) -> Error {
    let host = crate::netpolicy::url_host_port(&job.url)
        .map(|(h, _)| h)
        .unwrap_or("<unparsable>");
    crate::android_log::log(&format!(
        "http guard refused host {host}: {}",
        reject.as_str()
    ));
    Error::InvalidInput
}

/// The IP of a `NetAddr`, if it has one.
fn netaddr_ip(addr: NetAddr) -> Option<std::net::IpAddr> {
    match addr {
        NetAddr::V4(ip, _) => Some(std::net::IpAddr::V4(Ipv4Addr::new(
            ip[0], ip[1], ip[2], ip[3],
        ))),
        NetAddr::V6(ip, _) => Some(std::net::IpAddr::V6(Ipv6Addr::from(ip))),
    }
}

/// Removes and returns the element at `index` from a `VecDeque`.
fn remove_at<T>(q: &mut VecDeque<T>, index: usize) -> Option<T> {
    if index == 0 {
        return q.pop_front();
    }
    if index >= q.len() {
        return None;
    }
    let front: Vec<T> = q.drain(..index).collect();
    let item = q.pop_front();
    // Put the drained prefix back, keeping the order.
    for value in front.into_iter().rev() {
        q.push_front(value);
    }
    item
}

impl Host for NativeHost {
    fn now_ms(&self) -> u64 {
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }

    fn fill_random(&mut self, buf: &mut [u8]) {
        if getrandom::fill(buf).is_err() {
            let t = self.now_ms();
            for (i, b) in buf.iter_mut().enumerate() {
                *b = (t >> (i % 64)) as u8 ^ (i as u8).wrapping_mul(131);
            }
        }
    }

    fn log(&mut self, level: LogLevel, msg: &str) {
        self.log_internal(level, msg);
    }

    fn http_get(&mut self, url: &str, timeout_ms: u64, out: &mut Vec<u8>) -> Result<()> {
        let id = self.enqueue_http_job(url, None, None, timeout_ms, true);
        if id == 0 {
            return self.http_run_blocking(url, None, None, timeout_ms, out);
        }
        self.wait_http_job(id, timeout_ms, out)
    }

    /// UPnP IGD control (SSDP → device description → `AddPortMapping`).
    ///
    /// Without this the port mapper can only speak NAT-PMP, which most home
    /// routers do not implement, so inbound connections never get mapped and a
    /// seeding client stays unreachable. The URL always points at the gateway
    /// on the LAN, which the guard allows by default.
    fn http_post(
        &mut self,
        url: &str,
        body: &[u8],
        timeout_ms: u64,
        out: &mut Vec<u8>,
    ) -> Result<()> {
        let id = self.enqueue_http_job(url, None, Some(body.to_vec()), timeout_ms, true);
        if id == 0 {
            return self.http_run_blocking(url, None, Some(body), timeout_ms, out);
        }
        self.wait_http_job(id, timeout_ms, out)
    }

    /// BEP-19 web seeds: delegate the Range request to the pool, which
    /// validates the response (206 with the exact window, or a 200 that is
    /// sliced at the right offset).
    fn http_get_range(
        &mut self,
        url: &str,
        range_start: u64,
        range_end: u64,
        timeout_ms: u64,
        out: &mut Vec<u8>,
    ) -> Result<()> {
        let range = Some((range_start, range_end));
        let id = self.enqueue_http_job(url, range, None, timeout_ms, false);
        if id == 0 {
            return self.http_run_blocking(url, range, None, timeout_ms, out);
        }
        self.wait_http_job(id, timeout_ms, out)
    }

    fn http_get_async(&mut self, url: &str, timeout_ms: u64) -> u64 {
        self.enqueue_http_job(url, None, None, timeout_ms, true)
    }

    fn http_get_range_async(
        &mut self,
        url: &str,
        range_start: u64,
        range_end: u64,
        timeout_ms: u64,
    ) -> u64 {
        self.enqueue_http_job(url, Some((range_start, range_end)), None, timeout_ms, false)
    }

    fn http_take_done(&mut self) -> std::vec::Vec<(u64, Result<Vec<u8>>)> {
        self.http_drain_done().into_iter().collect()
    }

    /// A LAN address of this host, required by UPnP IGD AddPortMapping.
    /// Discovered with the classic UDP-connect trick (no packets are sent) and
    /// cached: the port mapper calls this on every attempt, and a socket pair
    /// per call is pure syscall overhead.
    fn local_ip(&self) -> Option<NetAddr> {
        if let Some((addr, at)) = self.local_ip_cache {
            if at.elapsed() < LOCAL_IP_TTL {
                return Some(addr);
            }
        }
        let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
        sock.connect("8.8.8.8:53").ok()?;
        let local = sock.local_addr().ok()?;
        Some(sock_to_netaddr(local))
    }

    /// The default gateway, needed by NAT-PMP (RFC 6886) and as the SSDP
    /// fallback. Discovered per platform (Linux/Android `/proc/net/route`,
    /// Windows `GetBestRoute`); `None` simply leaves UPnP as the only mapper.
    fn default_gateway(&self) -> Option<NetAddr> {
        crate::netinfo::default_gateway()
    }

    /// Resolve a hostname to an IP endpoint — used by the engine to
    /// bootstrap the DHT from the BEP-5 router hostnames
    /// (`router.bittorrent.com` & co.).
    ///
    /// Resolves only from the memo and refreshes a miss in the background.
    ///
    /// This hook is callable from the engine tick. Calling the OS resolver
    /// here is not safe: Android `getaddrinfo` can block for seconds, and one
    /// dead tracker must never stall every peer and disk event. Async-capable
    /// paths receive the refreshed result on their next attempt.
    fn resolve_host(&self, host: &str, port: u16) -> Option<NetAddr> {
        let out = match self.dns.cached(host, port, Instant::now()) {
            Some(addrs) => addrs,
            None => {
                self.dns.refresh_async(host);
                return None;
            }
        };
        // IPv4 first: the UDP socket prefers it, and a v6-only path must still
        // be usable when the network has no v4 route.
        out.iter()
            .find(|a| matches!(a, NetAddr::V4(..)))
            .or(out.first())
            .copied()
    }

    /// Every cached address record for a hostname.
    ///
    /// UDP tracker announces run from the engine tick as well. A cache miss is
    /// resolved asynchronously and reported as a recoverable tracker failure;
    /// the next announce picks up the memoized answer. Returning all records
    /// once ready lets a tracker with a dead A record work through its AAAA or
    /// second A without blocking the client on the first lookup.
    fn resolve_host_all(&self, host: &str, port: u16) -> std::vec::Vec<NetAddr> {
        match self.dns.cached(host, port, Instant::now()) {
            Some(addrs) => addrs,
            None => {
                self.dns.refresh_async(host);
                Vec::new()
            }
        }
    }

    fn resolve_host_async(&mut self, host: &str, port: u16) -> bool {
        self.ensure_resolve_worker();
        let Some(h) = self.resolve_worker.as_ref() else {
            return false;
        };
        h.queue.push_interactive(ResolveJob {
            host: host.to_string(),
            port,
        });
        true
    }

    fn take_resolved_hosts(&mut self) -> std::vec::Vec<(String, u16, NetAddr)> {
        let mut out = Vec::new();
        if let Some(h) = self.resolve_worker.as_ref() {
            while let Ok((host, port, addrs)) = h.done_rx.try_recv() {
                for addr in addrs {
                    out.push((host.clone(), port, addr));
                }
            }
        }
        out
    }

    fn tcp_connect(&mut self, addr: &NetAddr) -> Result<ConnId> {
        if self.pending_connects >= MAX_PENDING_CONNECTS {
            return Err(Error::Full);
        }
        if self.conns.len() + self.pending_connects >= MAX_OPEN_CONNS {
            return Err(Error::Full);
        }
        let id = self.next_conn;
        self.next_conn = self.next_conn.wrapping_add(1);
        let target = netaddr_to_sockaddr(*addr).ok_or(Error::InvalidInput)?;
        let tx = self.established_tx.clone();
        self.pending_connects += 1;
        self.conns.insert(id, ConnSlot::Connecting);

        match std::thread::Builder::new()
            .name("typebit-conn".to_string())
            .spawn(move || {
                let res = TcpStream::connect_timeout(&target, CONNECT_TIMEOUT);
                let _ = tx.send((id, res));
            }) {
            Ok(_) => Ok(id),
            Err(e) => {
                self.pending_connects = self.pending_connects.saturating_sub(1);
                self.conns.remove(&id);
                crate::android_log::log(&format!("tcp_connect: spawn failed: {e}"));
                Err(Error::Full)
            }
        }
    }

    fn tcp_connect_done(&mut self, id: ConnId) -> Result<()> {
        match self.conns.get(&id) {
            Some(ConnSlot::Established(_)) => Ok(()),
            Some(ConnSlot::Connecting) => Err(Error::WouldBlock),
            None => Err(Error::Io),
        }
    }

    fn tcp_send(&mut self, id: ConnId, data: &[u8]) -> Result<usize> {
        let stream = self.conn(id).ok_or(Error::NotFound)?;
        let _ = stream.set_nonblocking(true);
        match stream.write(data) {
            Ok(n) => {
                self.up_total = self.up_total.saturating_add(n as u64);
                Ok(n)
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Ok(0),
            Err(_) => Err(Error::Io),
        }
    }

    fn tcp_recv(&mut self, id: ConnId, buf: &mut [u8]) -> Result<usize> {
        let stream = self.conn(id).ok_or(Error::NotFound)?;
        let _ = stream.set_nonblocking(true);
        match stream.read(buf) {
            Ok(0) => Err(Error::Io), // EOF: peer closed; engine drops it.
            Ok(n) => {
                self.down_total = self.down_total.saturating_add(n as u64);
                Ok(n)
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Err(Error::WouldBlock),
            Err(_) => Err(Error::Io),
        }
    }

    fn tcp_close(&mut self, id: ConnId) {
        self.conns.remove(&id);
    }

    fn tcp_recv_buf_size(&self) -> usize {
        64 * 1024
    }

    fn udp_open(&mut self, port: u16) -> Result<()> {
        if self.udp.is_some() {
            return Ok(());
        }
        for (label, addr) in [
            ("dual-stack", format!("[::]:{port}")),
            ("ipv4", format!("0.0.0.0:{port}")),
            ("dual-stack (ephemeral)", "[::]:0".to_string()),
            ("ipv4 (ephemeral)", "0.0.0.0:0".to_string()),
        ] {
            let Ok(bind) = addr.parse::<SocketAddr>() else {
                continue;
            };
            match UdpSocket::bind(bind) {
                Ok(sock) => {
                    let _ = sock.set_nonblocking(true);
                    if sock.local_addr().map(|a| a.is_ipv6()).unwrap_or(false) {
                        let _ = crate::netinfo::set_dual_stack(&sock, true);
                    }
                    let actual = sock.local_addr().map(|a| a.port()).unwrap_or(port);
                    self.log_internal(
                        LogLevel::Info,
                        &format!("UDP bound on port {actual} ({label})"),
                    );
                    self.udp = Some(sock);
                    return Ok(());
                }
                Err(e) => {
                    self.log_internal(LogLevel::Debug, &format!("udp bind {addr} failed: {e}"));
                }
            }
        }
        self.log_internal(LogLevel::Error, "unable to bind any UDP socket");
        Err(Error::Io)
    }

    fn udp_send(&mut self, addr: &NetAddr, data: &[u8]) -> Result<()> {
        let Some(sock) = self.udp.as_ref() else {
            return Err(Error::NotSupported);
        };
        let target = netaddr_to_sockaddr_for(sock, *addr).ok_or(Error::InvalidInput)?;
        match sock.send_to(data, target) {
            Ok(_) => Ok(()),
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::ConnectionReset
                    || e.kind() == std::io::ErrorKind::ConnectionAborted =>
            {
                Ok(())
            }
            Err(e) => {
                self.log_internal(
                    LogLevel::Debug,
                    &format!("udp_send to {addr} failed: {e} ({:?})", e.kind()),
                );
                Err(Error::Io)
            }
        }
    }

    /// Send to a multicast group with a wide-enough TTL (LSD, BEP-14).
    fn udp_multicast_send(&mut self, addr: &NetAddr, data: &[u8]) -> Result<()> {
        let Some(sock) = self.udp.as_ref() else {
            return Err(Error::NotSupported);
        };
        if matches!(*addr, NetAddr::V4(..)) {
            let _ = sock.set_multicast_ttl_v4(16);
            let _ = sock.set_multicast_loop_v4(true);
        }
        let target = netaddr_to_sockaddr_for(sock, *addr).ok_or(Error::InvalidInput)?;
        match sock.send_to(data, target) {
            Ok(_) => Ok(()),
            // Transient Windows ICMP-reset noise; see `udp_send`.
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::ConnectionReset
                    || e.kind() == std::io::ErrorKind::ConnectionAborted =>
            {
                Ok(())
            }
            Err(_) => Err(Error::Io),
        }
    }

    /// Send to a multicast group **from the dedicated LSD (port-6771)
    /// socket** when one exists — the symmetric BEP-14 flow (a neighbour's
    /// unicast reply returns to 6771, the socket always drained for LSD).
    /// Falls back to the shared socket.
    fn udp_multicast_send_lsd(&mut self, addr: &NetAddr, data: &[u8]) -> Result<()> {
        let sock = match self.lsd_udp.as_ref() {
            Some(s) => s,
            None => return self.udp_multicast_send(addr, data),
        };
        if matches!(*addr, NetAddr::V4(..)) {
            let _ = sock.set_multicast_ttl_v4(16);
            let _ = sock.set_multicast_loop_v4(true);
        }
        let target = netaddr_to_sockaddr_for(sock, *addr).ok_or(Error::InvalidInput)?;
        match sock.send_to(data, target) {
            Ok(_) => Ok(()),
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::ConnectionReset
                    || e.kind() == std::io::ErrorKind::ConnectionAborted =>
            {
                Ok(())
            }
            Err(_) => Err(Error::Io),
        }
    }

    /// Join a multicast group on the bound UDP socket so LAN datagrams to
    /// the group (LSD announces, SSDP responses) reach `udp_recv`.
    fn udp_join_multicast(&mut self, addr: NetAddr) -> Result<()> {
        let Some(sock) = self.udp.as_ref() else {
            return Err(Error::NotSupported);
        };
        match addr {
            NetAddr::V4(ip, _) => {
                let group = std::net::Ipv4Addr::new(ip[0], ip[1], ip[2], ip[3]);
                match sock.join_multicast_v4(&group, &std::net::Ipv4Addr::UNSPECIFIED) {
                    Ok(()) => Ok(()),
                    Err(_) => Err(Error::Io),
                }
            }
            NetAddr::V6(ip, _) => {
                let group = std::net::Ipv6Addr::from(ip);
                match sock.join_multicast_v6(&group, 0) {
                    Ok(()) => Ok(()),
                    Err(_) => Err(Error::Io),
                }
            }
        }
    }

    fn udp_recv(&mut self, buf: &mut [u8]) -> Result<(NetAddr, usize)> {
        let Some(sock) = self.udp.as_ref() else {
            return Err(Error::NotSupported);
        };
        match sock.recv_from(buf) {
            Ok((n, addr)) => Ok((sock_to_netaddr(addr), n)),
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::ConnectionReset
                    || e.kind() == std::io::ErrorKind::ConnectionAborted =>
            {
                Err(Error::WouldBlock)
            }
            Err(_) => Err(Error::Io),
        }
    }

    fn udp_open_lsd(&mut self, port: u16) -> Result<()> {
        if self.lsd_udp.is_some() {
            return Ok(());
        }
        let addr = format!("0.0.0.0:{port}")
            .parse::<std::net::SocketAddr>()
            .map_err(|_| Error::InvalidInput)?;
        match std::net::UdpSocket::bind(addr) {
            Ok(s) => {
                let _ = s.set_nonblocking(true);
                let _ = s.set_multicast_ttl_v4(16);
                let _ = s.set_multicast_loop_v4(true);
                self.log_internal(
                    LogLevel::Info,
                    &format!(
                        "LSD socket bound on port {}",
                        s.local_addr().map(|a| a.port()).unwrap_or(port)
                    ),
                );
                self.lsd_udp = Some(s);
                Ok(())
            }
            Err(_) => Err(Error::Io),
        }
    }

    fn udp_join_multicast_lsd(&mut self, addr: NetAddr) -> Result<()> {
        let Some(sock) = self.lsd_udp.as_ref() else {
            return Err(Error::NotSupported);
        };
        match addr {
            NetAddr::V4(ip, _) => {
                let group = std::net::Ipv4Addr::new(ip[0], ip[1], ip[2], ip[3]);
                match sock.join_multicast_v4(&group, &std::net::Ipv4Addr::UNSPECIFIED) {
                    Ok(()) => Ok(()),
                    Err(_) => Err(Error::Io),
                }
            }
            NetAddr::V6(ip, _) => {
                let group = std::net::Ipv6Addr::from(ip);
                match sock.join_multicast_v6(&group, 0) {
                    Ok(()) => Ok(()),
                    Err(_) => Err(Error::Io),
                }
            }
        }
    }

    fn udp_recv_lsd(&mut self, buf: &mut [u8]) -> Result<(NetAddr, usize)> {
        let Some(sock) = self.lsd_udp.as_ref() else {
            return Err(Error::WouldBlock);
        };
        match sock.recv_from(buf) {
            Ok((n, addr)) => Ok((sock_to_netaddr(addr), n)),
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::ConnectionReset
                    || e.kind() == std::io::ErrorKind::ConnectionAborted =>
            {
                Err(Error::WouldBlock)
            }
            Err(_) => Err(Error::Io),
        }
    }

    fn udp_close_lsd(&mut self) {
        self.lsd_udp = None;
    }

    fn disk_open(&mut self, path: &str) -> Result<DiskId> {
        if self.files.len() >= MAX_OPEN_FILES {
            return Err(Error::Full);
        }
        let actual = Self::resolve_disk_path(path);
        if let Some(parent) = std::path::Path::new(&actual).parent() {
            if !parent.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(parent);
            }
        }
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&actual)
            .map_err(|_| Error::Io)?;
        self.log_internal(
            LogLevel::Debug,
            &format!("disk_open {actual} (final={path})"),
        );
        let id = self.next_disk;
        self.next_disk = self.next_disk.wrapping_add(1);
        self.files.insert(id, file);
        Ok(id)
    }

    fn disk_read(&mut self, id: DiskId, offset: u64, buf: &mut [u8]) -> Result<usize> {
        let file = self.files.get_mut(&id).ok_or(Error::NotFound)?;
        file.seek(SeekFrom::Start(offset)).map_err(|_| Error::Io)?;
        file.read(buf).map_err(|_| Error::Io)
    }

    fn disk_write(&mut self, id: DiskId, offset: u64, data: &[u8]) -> Result<()> {
        let file = self.files.get_mut(&id).ok_or(Error::NotFound)?;
        file.seek(SeekFrom::Start(offset)).map_err(|_| Error::Io)?;
        file.write_all(data).map_err(|_| Error::Io)
    }

    fn disk_set_alloc(&mut self, id: DiskId, mode: u8) -> Result<()> {
        self.alloc_mode.insert(id, mode);
        // Sparse (mode 1): mark the file sparse on Windows so the extent
        // reservation done by `disk_prealloc` (set_len) does not consume
        // real disk space for unwritten regions. This is the fragmentation
        // win without the disk-space cost.
        if mode == 1 {
            #[cfg(target_os = "windows")]
            {
                use std::os::windows::io::AsRawHandle;
                use windows_sys::Win32::Foundation::HANDLE;
                use windows_sys::Win32::System::Ioctl::FSCTL_SET_SPARSE;
                use windows_sys::Win32::System::IO::DeviceIoControl;
                if let Some(f) = self.files.get(&id) {
                    let mut bytes: u32 = 0;
                    // Best-effort: if the FS rejects it (e.g. FAT), the
                    // extent is still reserved by set_len.
                    let _ = unsafe {
                        DeviceIoControl(
                            f.as_raw_handle() as HANDLE,
                            FSCTL_SET_SPARSE,
                            std::ptr::null(),
                            0,
                            std::ptr::null_mut(),
                            0,
                            &mut bytes,
                            std::ptr::null_mut(),
                        )
                    };
                }
            }
        }
        Ok(())
    }

    fn disk_prealloc(&mut self, id: DiskId, size: u64) -> Result<()> {
        let mode = self.alloc_mode.get(&id).copied().unwrap_or(1);
        {
            let file = self.files.get_mut(&id).ok_or(Error::NotFound)?;
            file.set_len(size).map_err(|_| Error::Io)?;
        }
        if mode == 2 && size <= (1 << 30) {
            if let Some(file) = self.files.get(&id) {
                let _ = fill_file(file, size);
            }
        }
        Ok(())
    }

    fn disk_flush(&mut self, id: DiskId) -> Result<()> {
        let file = self.files.get_mut(&id).ok_or(Error::NotFound)?;
        file.sync_data().map_err(|_| Error::Io)
    }

    fn disk_close(&mut self, id: DiskId) {
        self.files.remove(&id);
        self.alloc_mode.remove(&id);
    }
}

/// Write zeros across the whole file in bounded chunks (full allocation).
/// Best-effort: a failure leaves the extent reserved by `set_len`, which is
/// still the fragmentation win.
fn fill_file(file: &std::fs::File, size: u64) -> Result<()> {
    use std::io::{Seek, SeekFrom, Write};
    let mut f = file.try_clone().map_err(|_| Error::Io)?;
    let buf = vec![0u8; 4 * 1024 * 1024];
    let mut written: u64 = 0;
    while written < size {
        let chunk = ((size - written) as usize).min(buf.len());
        f.seek(SeekFrom::Start(written)).map_err(|_| Error::Io)?;
        f.write_all(&buf[..chunk]).map_err(|_| Error::Io)?;
        written += chunk as u64;
    }
    Ok(())
}

// ---------- address conversion helpers ----------

fn netaddr_to_sockaddr(a: NetAddr) -> Option<SocketAddr> {
    match a {
        NetAddr::V4(ip, port) => {
            let ip = Ipv4Addr::new(ip[0], ip[1], ip[2], ip[3]);
            Some(SocketAddr::V4(SocketAddrV4::new(ip, port)))
        }
        NetAddr::V6(ip, port) => {
            let mut o = [0u16; 8];
            for (i, chunk) in ip.chunks(2).enumerate() {
                o[i] = u16::from_be_bytes([chunk[0], chunk[1]]);
            }
            let ip = Ipv6Addr::from(o);
            Some(SocketAddr::V6(SocketAddrV6::new(ip, port, 0, 0)))
        }
    }
}

/// Converts an endpoint for a socket bound to `[::]`.
///
/// A dual-stack socket is AF_INET6, so `send_to` with a `SocketAddr::V4` fails
/// with EAFNOSUPPORT. Wrapping the v4 address in its v4-mapped form
/// (`::ffff:a.b.c.d`) is what makes the one socket serve both families — and
/// the reason the shared UDP socket now reaches IPv6 DHT nodes and IPv6 UDP
/// trackers at all.
fn netaddr_to_sockaddr_for(sock: &UdpSocket, a: NetAddr) -> Option<SocketAddr> {
    let addr = netaddr_to_sockaddr(a)?;
    if is_dual_stack(sock) {
        if let SocketAddr::V4(v4) = addr {
            return Some(SocketAddr::V6(SocketAddrV6::new(
                v4.ip().to_ipv6_mapped(),
                v4.port(),
                0,
                0,
            )));
        }
    }
    Some(addr)
}

/// Whether this socket is AF_INET6 (and therefore needs v4-mapped peers).
fn is_dual_stack(sock: &UdpSocket) -> bool {
    sock.local_addr().map(|a| a.is_ipv6()).unwrap_or(false)
}

fn sock_to_netaddr(a: SocketAddr) -> NetAddr {
    match a {
        SocketAddr::V4(v4) => {
            let ip = v4.ip().octets();
            NetAddr::V4(ip, v4.port())
        }
        SocketAddr::V6(v6) => {
            if let Some(v4) = v6.ip().to_ipv4_mapped() {
                return NetAddr::V4(v4.octets(), v6.port());
            }
            let mut ip = [0u8; 16];
            for (i, seg) in v6.ip().segments().iter().enumerate() {
                ip[i * 2] = (seg >> 8) as u8;
                ip[i * 2 + 1] = (seg & 0xff) as u8;
            }
            NetAddr::V6(ip, v6.port())
        }
    }
}

// ---------------------------------------------------------------------------
// Tests: the HTTP rules that only mean something on the wire
// ---------------------------------------------------------------------------

/// The web-seed range rules, the URL guard and the queue priority.
///
/// These run against a real (loopback) HTTP server rather than a mock, because
/// what is being tested is byte-exact behaviour on the wire: which bytes come
/// back for `Range: bytes=10-19`, whether the virtual host survives a DoH
/// rewrite, and whether a refused URL reaches the socket at all.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::dns::DnsService;
    use std::io::BufRead;
    use std::net::TcpListener as StdTcpListener;

    /// A one-shot HTTP/1.1 server: records the request head of every
    /// connection and answers with the bytes the test supplies.
    ///
    /// The accept loop polls a shutdown flag instead of blocking forever, so a
    /// test whose request is *supposed* to be refused (the guard tests) still
    /// tears down promptly instead of leaving a thread stuck in `accept`.
    struct TestServer {
        port: u16,
        requests: Arc<Mutex<Vec<String>>>,
        shutdown: Arc<std::sync::atomic::AtomicBool>,
        handle: Option<std::thread::JoinHandle<()>>,
    }

    impl TestServer {
        /// Starts a server that answers at most `max_requests` connections.
        fn start(max_requests: usize, build: impl Fn(&str) -> Vec<u8> + Send + 'static) -> Self {
            let listener = StdTcpListener::bind("127.0.0.1:0").expect("bind test server");
            let port = listener.local_addr().expect("addr").port();
            listener
                .set_nonblocking(true)
                .expect("non-blocking listener");
            let requests = Arc::new(Mutex::new(Vec::new()));
            let requests_worker = requests.clone();
            let shutdown = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let shutdown_worker = shutdown.clone();
            let handle = std::thread::spawn(move || {
                let mut served = 0usize;
                while served < max_requests
                    && !shutdown_worker.load(std::sync::atomic::Ordering::SeqCst)
                {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            handle_connection(stream, &requests_worker, &build);
                            served += 1;
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(2));
                        }
                        Err(_) => return,
                    }
                }
            });
            TestServer {
                port,
                requests,
                shutdown,
                handle: Some(handle),
            }
        }

        fn requests(&self) -> Vec<String> {
            self.requests
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        }
    }

    /// Reads one request head, records it, writes the response and closes.
    fn handle_connection(
        mut stream: std::net::TcpStream,
        requests: &Arc<Mutex<Vec<String>>>,
        build: &(impl Fn(&str) -> Vec<u8> + Send + 'static),
    ) {
        let Ok(clone) = stream.try_clone() else {
            return;
        };
        let mut reader = std::io::BufReader::new(clone);
        let mut head = String::new();
        loop {
            let mut line = String::new();
            match reader.read_line(&mut line) {
                Ok(0) => break,
                Ok(_) => {
                    let done = line == "\r\n" || line == "\n";
                    head.push_str(&line);
                    if done {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        let content_length = head
            .lines()
            .find_map(|l| {
                let (name, value) = l.split_once(':')?;
                if name.eq_ignore_ascii_case("content-length") {
                    value.trim().parse::<usize>().ok()
                } else {
                    None
                }
            })
            .unwrap_or(0);
        if content_length > 0 {
            let mut body = vec![0u8; content_length.min(64 * 1024)];
            let _ = reader.read_exact(&mut body);
        }
        requests
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .push(head.clone());
        let response = build(&head);
        let _ = stream.write_all(&response);
        let _ = stream.flush();
    }

    impl Drop for TestServer {
        fn drop(&mut self) {
            self.shutdown
                .store(true, std::sync::atomic::Ordering::SeqCst);
            if let Some(h) = self.handle.take() {
                let _ = h.join();
            }
        }
    }

    /// Stands in for a host whose trust store cannot be read.
    fn no_store() -> std::result::Result<&'static crate::tlsroots::TrustAnchors, &'static str> {
        Err("no store")
    }

    /// TLS is configured from the platform store, verified, and never
    /// downgraded. This is the pin on a real defect: `ClientConfig::default()`
    /// carries `tls: None`, under which every `https://` tracker and web seed
    /// was refused ("https requires TLS settings") — so the client had no HTTPS
    /// at all, and the obvious wrong fix (`verify: false`) would have handed
    /// them to anyone on the path.
    #[test]
    fn the_https_client_verifies_against_the_platform_store() {
        assert!(tls_settings(no_store(), true).is_none());
        let Ok(anchors) = crate::tlsroots::anchors() else {
            return;
        };
        let tls = tls_settings(Ok(anchors), true).expect("configured");
        assert!(tls.verify, "certificate verification must never be off");
        assert_eq!(tls.roots.len(), anchors.count);
        assert!(
            !tls.roots.is_empty(),
            "a store with no roots verifies nothing"
        );
        assert_eq!(tls.alpn, vec![b"h2".to_vec(), b"http/1.1".to_vec()]);

        // ALPN has to match the wire format the client will speak.
        let h1 = tls_settings(Ok(anchors), false).expect("configured");
        assert_eq!(h1.alpn, vec![b"http/1.1".to_vec()]);
        assert_eq!(h1.roots.len(), anchors.count);
    }

    /// Whatever else an `https://` URL does, it must not be sent as cleartext:
    /// the request that reaches the server is a TLS handshake, not a request
    /// line. A plaintext server can only see the former.
    #[test]
    fn an_https_url_is_never_sent_in_cleartext() {
        let server = TestServer::start(1, |_| http_response("200 OK", "", b"ok"));
        let client = build_client(&loopback_policy(), true);
        let result = client
            .request(
                &format!("https://127.0.0.1:{}/announce", server.port),
                courierust::courierust_http::method::Method::GET,
            )
            .timeout(Duration::from_secs(3))
            .send();
        assert!(
            result.is_err(),
            "a plaintext server must not answer an HTTPS request"
        );
        for head in server.requests() {
            assert!(
                !head.starts_with("GET ") && !head.starts_with("POST "),
                "an https request was sent as cleartext: {head:?}"
            );
        }
    }

    fn http_response(status: &str, extra_headers: &str, body: &[u8]) -> Vec<u8> {
        let mut out = format!(
            "HTTP/1.1 {status}\r\n{extra_headers}content-length: {}\r\nconnection: close\r\n\r\n",
            body.len()
        )
        .into_bytes();
        out.extend_from_slice(body);
        out
    }

    /// A DNS service for the guard tests.
    ///
    /// No upstream is configured, so nothing in these tests can reach the
    /// network: the tests that need an answer prime the memo directly, and the
    /// ones that need a *real* answer use a loopback name (`localhost`), which
    /// the OS resolver owns.
    fn test_dns() -> DnsService {
        DnsService::new(
            &NetworkPolicy {
                doh_providers: Vec::new(),
                ..NetworkPolicy::default()
            },
            dns::IpFamily::V4Only,
        )
    }

    /// A policy that permits loopback, which is what makes a loopback test
    /// server reachable at all — and is the exact switch the SSRF guard turns
    /// off in production.
    fn loopback_policy() -> NetworkPolicy {
        NetworkPolicy {
            allow_loopback_fetch: true,
            ..NetworkPolicy::default()
        }
    }

    fn job(url: &str, range: Option<(u64, u64)>) -> HttpJob {
        HttpJob {
            id: 1,
            url: url.to_string(),
            range,
            post_body: None,
            timeout_ms: 5_000,
        }
    }

    fn entity(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    #[test]
    fn a_range_request_served_as_200_is_only_accepted_when_it_is_aligned() {
        let server = TestServer::start(4, |_| http_response("200 OK", "", &entity(20)));
        let client = build_client(&loopback_policy(), false);
        let out = execute_job(
            &client,
            &job(
                &format!("http://127.0.0.1:{}/f.bin", server.port),
                Some((0, 19)),
            ),
            &test_dns(),
            &loopback_policy(),
        )
        .expect("aligned 200");
        assert_eq!(out, entity(20));

        let server = TestServer::start(4, |_| http_response("200 OK", "", &entity(100)));
        let err = execute_job(
            &client,
            &job(
                &format!("http://127.0.0.1:{}/f.bin", server.port),
                Some((10, 19)),
            ),
            &test_dns(),
            &loopback_policy(),
        )
        .expect_err("misaligned 200 must be refused");
        assert!(matches!(err, Error::Protocol));

        let server = TestServer::start(4, |_| {
            let mut out = b"HTTP/1.1 200 OK\r\nconnection: close\r\n\r\n".to_vec();
            out.extend_from_slice(&entity(100));
            out
        });
        let err = execute_job(
            &client,
            &job(
                &format!("http://127.0.0.1:{}/f.bin", server.port),
                Some((0, 19)),
            ),
            &test_dns(),
            &loopback_policy(),
        )
        .expect_err("unknown-length 200 must be refused");
        assert!(matches!(err, Error::Protocol));
    }

    #[test]
    fn a_206_body_must_be_exactly_the_window() {
        let client = build_client(&loopback_policy(), false);
        let server = TestServer::start(4, |_| {
            http_response(
                "206 Partial Content",
                "content-range: bytes 10-19/100\r\n",
                &entity(4),
            )
        });
        let err = execute_job(
            &client,
            &job(
                &format!("http://127.0.0.1:{}/f.bin", server.port),
                Some((10, 19)),
            ),
            &test_dns(),
            &loopback_policy(),
        )
        .expect_err("short 206 must fail");
        assert!(matches!(err, Error::Protocol));

        // The exact window is accepted and returned unchanged.
        let server = TestServer::start(4, |_| {
            http_response(
                "206 Partial Content",
                "content-range: bytes 10-19/100\r\n",
                &entity(10),
            )
        });
        let out = execute_job(
            &client,
            &job(
                &format!("http://127.0.0.1:{}/f.bin", server.port),
                Some((10, 19)),
            ),
            &test_dns(),
            &loopback_policy(),
        )
        .expect("exact 206");
        assert_eq!(out, entity(10));
    }

    #[test]
    fn the_guard_refuses_loopback_before_anything_is_dialled() {
        let server = TestServer::start(1, |_| http_response("200 OK", "", b"nope"));
        let client = build_client(&NetworkPolicy::default(), false);
        let err = execute_job(
            &client,
            &job(&format!("http://127.0.0.1:{}/seed", server.port), None),
            &test_dns(),
            &NetworkPolicy::default(),
        )
        .expect_err("loopback must be refused by default");
        assert!(matches!(err, Error::InvalidInput));
        assert!(
            server.requests().is_empty(),
            "the guard must refuse before dialling"
        );
    }

    #[test]
    fn a_name_that_resolves_to_loopback_is_refused_before_dialling() {
        // DNS-based SSRF: a hostile `.torrent` names a domain it controls and
        // points it at the user's own machine. The URL guard alone would pass
        // it (the host is a name), so the resolved address is checked too — and
        // the check happens before the connection.
        let server = TestServer::start(4, |_| http_response("200 OK", "", b"secret"));
        let dns = test_dns();
        dns.prime_for_test("evil.test", std::net::Ipv4Addr::LOCALHOST);
        let err = execute_job(
            &build_client(&NetworkPolicy::default(), false),
            &job(&format!("http://evil.test:{}/admin", server.port), None),
            &dns,
            &NetworkPolicy::default(),
        )
        .expect_err("a name resolving to loopback must be refused");
        assert!(matches!(err, Error::InvalidInput));
        assert!(
            server.requests().is_empty(),
            "nothing may be dialled once the address is refused"
        );

        // With the LAN switch off, a name resolving to a private address is
        // refused too — the paranoid setting for a machine that never intends
        // to fetch from its local network.
        let dns = test_dns();
        dns.prime_for_test("nas.test", std::net::Ipv4Addr::new(192, 168, 1, 10));
        let strict = NetworkPolicy {
            allow_private_fetch: false,
            ..NetworkPolicy::default()
        };
        let err = execute_job(
            &build_client(&strict, false),
            &job(&format!("http://nas.test:{}/seed", server.port), None),
            &dns,
            &strict,
        )
        .expect_err("private addresses are refused when the LAN switch is off");
        assert!(matches!(err, Error::InvalidInput));
    }

    #[test]
    fn posts_carry_the_soap_headers_the_gateway_needs() {
        // UPnP control is a POST with `SOAPAction`; a gateway that does not see
        // it answers 500 and the port never opens.
        let server = TestServer::start(4, |head| {
            let lower = head.to_ascii_lowercase();
            assert!(lower.starts_with("post "), "expected POST, got:\n{head}");
            assert!(lower.contains("soapaction:"), "missing SOAPAction:\n{head}");
            assert!(
                lower.contains("content-type: text/xml"),
                "missing SOAP content type:\n{head}"
            );
            http_response("200 OK", "", b"<ok/>")
        });
        let mut request = job(&format!("http://127.0.0.1:{}/ctl", server.port), None);
        request.post_body = Some(b"<soap/>".to_vec());
        let out = execute_job(
            &build_client(&loopback_policy(), false),
            &request,
            &test_dns(),
            &loopback_policy(),
        )
        .expect("soap post");
        assert_eq!(out, b"<ok/>".to_vec());
    }

    #[test]
    fn interactive_jobs_are_served_before_bulk_ones() {
        // The rule that keeps a web-seed flood from starving tracker
        // announces, tested on the queue itself so it is deterministic.
        let queue: JobQueue<HttpJob> = JobQueue::new();
        queue.push_bulk(job("http://bulk/1", Some((0, 15))));
        queue.push_bulk(job("http://bulk/2", Some((16, 31))));
        queue.push_interactive(job("http://tracker/announce", None));
        let first = queue.pop(Duration::from_millis(10)).expect("first job");
        assert_eq!(first.url, "http://tracker/announce");
        let second = queue.pop(Duration::from_millis(10)).expect("second job");
        assert_eq!(second.url, "http://bulk/1");
        let third = queue.pop(Duration::from_millis(10)).expect("third job");
        assert_eq!(third.url, "http://bulk/2");
        assert!(queue.pop(Duration::from_millis(10)).is_none());
        // A closed queue hands out nothing and lets its workers exit.
        queue.close();
        assert!(queue.pop(Duration::from_millis(10)).is_none());
    }

    #[test]
    #[ignore = "network diagnostic; run with `cargo test -- --ignored`"]
    fn diag_live_doh_round_trip() {
        // Exercises the real path (trust anchors, TLS, DoH, DNSSEC request)
        // against a public resolver. Skipped by default because CI and
        // locked-down networks have no HTTPS egress — in that case the
        // assertion below still proves the failure is *reported*, and that a
        // missing trust store is named rather than silently downgraded.
        match crate::tlsroots::anchors() {
            Ok(a) => println!("trust anchors: {} from {}", a.count, a.source),
            Err(why) => println!("no trust anchors: {why}"),
        }
        let policy = NetworkPolicy::default();
        let svc = DnsService::new(&policy, dns::IpFamily::V4Only);
        let resolved = svc.resolve("cloudflare-dns.com", 443, std::time::Instant::now());
        println!(
            "live resolve: {:?} -> {:?}",
            resolved.source, resolved.addrs
        );
        println!("dns stats: {}", svc.stats().summary());
        assert!(
            !resolved.addrs.is_empty(),
            "a working network must resolve a well-known name (through the OS resolver at worst)"
        );
        svc.shutdown();
    }

    #[test]
    fn bind_tcp_reports_the_port_it_actually_bound() {
        // The engine, the firewall rule and the UPnP mapping all target this
        // port, so "configured port" and "bound port" must never be confused.
        let mut host = NativeHost::new(LogBuffer::default());
        let port = host.bind_tcp(0);
        assert!(port > 0, "an ephemeral bind must report its real port");
        assert_eq!(host.listen_port(), port);

        // A port somebody else already holds must fall back to an ephemeral
        // one, and the fallback must be reported just the same — otherwise the
        // client silently accepts no inbound peers at all.
        let squatter = StdTcpListener::bind("0.0.0.0:0").expect("squatter");
        let taken = squatter.local_addr().expect("addr").port();
        let mut host = NativeHost::new(LogBuffer::default());
        let fallback = host.bind_tcp(taken);
        assert!(
            fallback > 0 && fallback != taken,
            "expected a fallback port, got {fallback}"
        );
        assert_eq!(host.listen_port(), fallback);
    }

    #[test]
    fn udp_open_accepts_both_address_families() {
        let mut host = NativeHost::new(LogBuffer::default());
        host.udp_open(0).expect("udp bind");
        // Whether the platform ends up dual-stack or v4-only, the socket must
        // exist and both families must be accepted without panicking: on a
        // dual-stack socket the v4 peer is wrapped in its mapped form, and on a
        // v4-only one the engine simply never receives a v6 peer.
        let _ = host.udp_send(&NetAddr::V4([127, 0, 0, 1], 1), b"probe");
        let _ = host.udp_send(
            &NetAddr::V6(std::net::Ipv6Addr::LOCALHOST.octets(), 1),
            b"probe",
        );
        assert!(matches!(
            host.udp_recv(&mut [0u8; 16]),
            Err(Error::WouldBlock)
        ));
    }

    #[test]
    fn sockaddr_conversion_unwraps_v4_mapped_peers() {
        // A dual-stack socket reports v4 peers as `::ffff:a.b.c.d`; the engine
        // must see the plain v4 address (compact peer lists depend on it).
        let mapped = SocketAddr::V6(SocketAddrV6::new(
            Ipv4Addr::new(203, 0, 113, 7).to_ipv6_mapped(),
            6881,
            0,
            0,
        ));
        assert_eq!(sock_to_netaddr(mapped), NetAddr::V4([203, 0, 113, 7], 6881));
        // A real v6 peer stays v6.
        let v6 = SocketAddr::V6(SocketAddrV6::new("2001:db8::1".parse().unwrap(), 1, 0, 0));
        assert!(matches!(sock_to_netaddr(v6), NetAddr::V6(..)));
    }
}
