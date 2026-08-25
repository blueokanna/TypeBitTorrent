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
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

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
/// Cap on concurrent in-flight HTTP jobs on the async worker (bounds
/// abandoned threads when a server hangs past its timeouts).
const MAX_HTTP_ACTIVE: usize = 8;

/// Shared log ring: `(level, message)` pairs, oldest first.
pub type LogBuffer = Arc<Mutex<VecDeque<(u8, String)>>>;

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
    timeout_ms: u64,
}

/// Handle to the shared async HTTP worker thread.
struct HttpWorkerHandle {
    jobs_tx: Sender<HttpJob>,
    done_rx: Receiver<(u64, Result<Vec<u8>>)>,
}

/// One queued DNS resolution job for the async resolver.
struct ResolveJob {
    host: String,
    port: u16,
}

/// Handle to the shared async DNS resolver thread.
struct ResolveWorkerHandle {
    jobs_tx: Sender<ResolveJob>,
    done_rx: Receiver<(String, u16, Option<NetAddr>)>,
}

/// The complete std-backed host.
pub struct NativeHost {
    listener: Option<TcpListener>,
    udp: Option<UdpSocket>,
    conns: HashMap<ConnId, ConnSlot>,
    next_conn: ConnId,
    next_disk: DiskId,
    established_rx: Receiver<ConnectResult>,
    established_tx: Sender<ConnectResult>,
    pending_connects: usize,
    files: HashMap<DiskId, std::fs::File>,
    http: typebit::host_std::StdHost,
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
    /// Cumulative wire bytes (downloaded, uploaded) for the status bar.
    down_total: u64,
    up_total: u64,
    logs: LogBuffer,
}

impl NativeHost {
    pub fn new(logs: LogBuffer) -> Self {
        let (established_tx, established_rx) = channel();
        NativeHost {
            listener: None,
            udp: None,
            conns: HashMap::new(),
            next_conn: 1,
            next_disk: 1,
            established_rx,
            established_tx,
            pending_connects: 0,
            files: HashMap::new(),
            http: typebit::host_std::StdHost::new(),
            http_worker: None,
            resolve_worker: None,
            http_pending_results: VecDeque::new(),
            next_http_job: 0,
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
        if self.listener.is_some() {
            return self
                .listener
                .as_ref()
                .unwrap()
                .local_addr()
                .map(|a| a.port())
                .unwrap_or(port);
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
                    // Failed: remove the slot so tcp_connect_done reports Io.
                    self.conns.remove(&id);
                }
            }
        }
    }

    /// Close everything (engine teardown).
    pub fn shutdown(&mut self) {
        self.listener = None;
        self.udp = None;
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

    /// Lazily spawn the shared HTTP worker thread (one per host, never per request). The worker owns a
    /// bounded-timeout courierust client and runs jobs on capped inner threads so a hung server can never
    /// stall the engine or the queue for more than its timeouts.
    fn ensure_http_worker(&mut self) {
        if self.http_worker.is_some() {
            return;
        }
        let (jobs_tx, jobs_rx) = channel();
        let (done_tx, done_rx) = channel();
        std::thread::Builder::new()
            .name("typebit-http".to_string())
            .spawn(move || http_worker_loop(jobs_rx, done_tx))
            .ok();
        self.http_worker = Some(HttpWorkerHandle { jobs_tx, done_rx });
    }

    fn next_http_job_id(&mut self) -> u64 {
        self.next_http_job = self.next_http_job.wrapping_add(1).max(1);
        self.next_http_job
    }

    /// Enqueue an async HTTP job; returns the job id or 0 on failure.
    fn enqueue_http_job(&mut self, url: &str, range: Option<(u64, u64)>, timeout_ms: u64) -> u64 {
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
            timeout_ms,
        };
        if h.jobs_tx.send(job).is_err() {
            return 0;
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
    /// its body to `out`. Used by the synchronous fallback paths (proxy-mode
    /// web seeds); other jobs' results stay buffered for the engine.
    fn wait_http_job(&mut self, id: u64, timeout_ms: u64, out: &mut Vec<u8>) -> Result<()> {
        let deadline = self.now_ms().saturating_add(timeout_ms);
        loop {
            let jobs = self.http_drain_done();
            for (jid, res) in jobs {
                if jid == id {
                    out.extend_from_slice(&res?);
                    return Ok(());
                }
                self.http_pending_results.push_back((jid, res));
            }
            if self.now_ms() >= deadline {
                return Err(Error::Timeout);
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    /// Lazily spawn the shared async DNS resolver thread (one per host).
    fn ensure_resolve_worker(&mut self) {
        if self.resolve_worker.is_some() {
            return;
        }
        let (jobs_tx, jobs_rx) = channel();
        let (done_tx, done_rx) = channel();
        std::thread::Builder::new()
            .name("typebit-resolver".to_string())
            .spawn(move || resolve_worker_loop(jobs_rx, done_tx))
            .ok();
        self.resolve_worker = Some(ResolveWorkerHandle { jobs_tx, done_rx });
    }
}

/// The async DNS resolver thread: resolves hostnames on its own thread so
/// a slow/broken resolver can never stall the engine loop.
fn resolve_worker_loop(
    jobs_rx: Receiver<ResolveJob>,
    done_tx: Sender<(String, u16, Option<NetAddr>)>,
) {
    while let Ok(job) = jobs_rx.recv() {
        let resolved = typebit::host_std::StdHost::new().resolve_host(&job.host, job.port);
        if done_tx.send((job.host, job.port, resolved)).is_err() {
            break;
        }
    }
}

/// The async HTTP worker thread: receives jobs, executes each on a capped inner thread (so one hung
/// server cannot serialize the whole queue beyond its own timeouts), and reports results back.
fn http_worker_loop(jobs_rx: Receiver<HttpJob>, done_tx: Sender<(u64, Result<Vec<u8>>)>) {
    use courierust::courierust_client::ClientConfig;
    let client = courierust::courierust_client::Client::with_config(ClientConfig {
        connect_timeout: Some(Duration::from_secs(6)),
        read_timeout: Some(Duration::from_secs(10)),
        handshake_timeout: Some(Duration::from_secs(6)),
        ..Default::default()
    });
    let active = Arc::new(AtomicUsize::new(0));
    while let Ok(job) = jobs_rx.recv() {
        // Back-pressure: cap concurrent inner threads.
        while active.load(Ordering::SeqCst) >= MAX_HTTP_ACTIVE {
            std::thread::sleep(Duration::from_millis(5));
        }
        active.fetch_add(1, Ordering::SeqCst);
        let client = client.clone();
        let done_tx = done_tx.clone();
        let active = active.clone();
        std::thread::spawn(move || {
            let res = http_job_execute(&client, &job);
            let _ = done_tx.send((job.id, res));
            active.fetch_sub(1, Ordering::SeqCst);
        });
    }
}

/// Execute one HTTP job (plain GET or byte-range GET).
fn http_job_execute(
    client: &courierust::courierust_client::Client,
    job: &HttpJob,
) -> Result<Vec<u8>> {
    let _ = job.timeout_ms; // courierust's own timeouts bound each request
    match job.range {
        None => {
            let resp = client.get(&job.url).map_err(|_| Error::Io)?;
            if resp.status.as_u16() != 200 {
                return Err(Error::Tracker);
            }
            resp.body
                .collect()
                .map(|b| b.to_vec())
                .map_err(|_| Error::Io)
        }
        Some((start, end)) => {
            use courierust::courierust_body::Body;
            use courierust::courierust_http::header::{HeaderName, HeaderValue};
            use courierust::courierust_http::method::Method;
            use courierust::courierust_http::request::Request;
            let mut req = Request::<Body>::new(Method::GET, "/");
            let value = format!("bytes={}-{}", start, end);
            req.headers.insert(
                HeaderName::from_lowercase("range"),
                HeaderValue::from_bytes(value.as_bytes()).map_err(|_| Error::InvalidInput)?,
            );
            let resp = client.execute(&job.url, req).map_err(|_| Error::Io)?;
            let status = resp.status.as_u16();
            if status != 200 && status != 206 {
                return Err(Error::Tracker);
            }
            resp.body
                .collect()
                .map(|b| b.to_vec())
                .map_err(|_| Error::Io)
        }
    }
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
            // Last-resort fallback (never for key material): clock hash.
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
        // Route through the async worker with a bounded wait so even the
        // synchronous fallback paths can never block the engine indefinitely.
        let id = self.http_get_async(url, timeout_ms);
        if id == 0 {
            return self.http.http_get(url, timeout_ms, out);
        }
        self.wait_http_job(id, timeout_ms, out)
    }

    /// BEP-19 web seeds: delegate the Range request to the async worker
    /// (which rejects a body that is not exactly the requested window).
    fn http_get_range(
        &mut self,
        url: &str,
        range_start: u64,
        range_end: u64,
        timeout_ms: u64,
        out: &mut Vec<u8>,
    ) -> Result<()> {
        let id = self.http_get_range_async(url, range_start, range_end, timeout_ms);
        if id == 0 {
            return self
                .http
                .http_get_range(url, range_start, range_end, timeout_ms, out);
        }
        self.wait_http_job(id, timeout_ms, out)
    }

    fn http_get_async(&mut self, url: &str, timeout_ms: u64) -> u64 {
        self.enqueue_http_job(url, None, timeout_ms)
    }

    fn http_get_range_async(
        &mut self,
        url: &str,
        range_start: u64,
        range_end: u64,
        timeout_ms: u64,
    ) -> u64 {
        self.enqueue_http_job(url, Some((range_start, range_end)), timeout_ms)
    }

    fn http_take_done(&mut self) -> std::vec::Vec<(u64, Result<Vec<u8>>)> {
        self.http_drain_done().into_iter().collect()
    }

    /// A LAN address of this host, required by UPnP IGD AddPortMapping.
    /// Discovered with the classic UDP-connect trick (no packets are sent).
    fn local_ip(&self) -> Option<NetAddr> {
        let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
        sock.connect("8.8.8.8:53").ok()?;
        let local = sock.local_addr().ok()?;
        Some(sock_to_netaddr(local))
    }

    /// Resolve a hostname to an IP endpoint — used by the engine to
    /// bootstrap the DHT from the BEP-5 router hostnames
    /// (`router.bittorrent.com` & co.). Delegates to the std host's OS
    /// resolver; `None` when DNS fails, which leaves the DHT dormant while
    /// HTTP/UDP trackers keep working (typebit treats it as a soft failure
    /// and emits an `EngineEvent::Error` instead of failing the torrent).
    fn resolve_host(&self, host: &str, port: u16) -> Option<NetAddr> {
        self.http.resolve_host(host, port)
    }

    fn resolve_host_all(&self, host: &str, port: u16) -> std::vec::Vec<NetAddr> {
        self.http.resolve_host_all(host, port)
    }

    fn resolve_host_async(&mut self, host: &str, port: u16) -> bool {
        self.ensure_resolve_worker();
        let h = match self.resolve_worker.as_ref() {
            Some(h) => h,
            None => return false,
        };
        h.jobs_tx
            .send(ResolveJob {
                host: host.to_string(),
                port,
            })
            .is_ok()
    }

    fn take_resolved_hosts(&mut self) -> std::vec::Vec<(String, u16, NetAddr)> {
        let mut out = Vec::new();
        if let Some(h) = self.resolve_worker.as_ref() {
            while let Ok((host, port, addr)) = h.done_rx.try_recv() {
                if let Some(a) = addr {
                    out.push((host, port, a));
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
        std::thread::spawn(move || {
            let res = TcpStream::connect_timeout(&target, CONNECT_TIMEOUT);
            let _ = tx.send((id, res));
        });
        Ok(id)
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
        let addr = format!("0.0.0.0:{port}")
            .parse::<SocketAddr>()
            .map_err(|_| Error::InvalidInput)?;
        match UdpSocket::bind(addr) {
            Ok(s) => {
                let _ = s.set_nonblocking(true);
                self.log_internal(
                    LogLevel::Info,
                    &format!(
                        "UDP bound on port {}",
                        s.local_addr().map(|a| a.port()).unwrap_or(port)
                    ),
                );
                self.udp = Some(s);
                Ok(())
            }
            Err(e) => {
                // Fall back to an ephemeral port so DHT still functions.
                self.log_internal(
                    LogLevel::Warn,
                    &format!("bind udp {port} failed ({e}); using ephemeral"),
                );
                match UdpSocket::bind("0.0.0.0:0") {
                    Ok(s) => {
                        let _ = s.set_nonblocking(true);
                        self.udp = Some(s);
                        Ok(())
                    }
                    Err(_) => Err(Error::Io),
                }
            }
        }
    }

    fn udp_send(&mut self, addr: &NetAddr, data: &[u8]) -> Result<()> {
        let Some(sock) = self.udp.as_ref() else {
            return Err(Error::NotSupported);
        };
        let target = netaddr_to_sockaddr(*addr).ok_or(Error::InvalidInput)?;
        match sock.send_to(data, target) {
            Ok(_) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Ok(()),
            Err(_) => Err(Error::Io),
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
        let target = netaddr_to_sockaddr(*addr).ok_or(Error::InvalidInput)?;
        match sock.send_to(data, target) {
            Ok(_) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Ok(()),
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
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Err(Error::WouldBlock),
            Err(_) => Err(Error::Io),
        }
    }

    fn disk_open(&mut self, path: &str) -> Result<DiskId> {
        if self.files.len() >= MAX_OPEN_FILES {
            return Err(Error::Full);
        }
        let actual = Self::resolve_disk_path(path);
        // Multi-file torrents carry subdirectories that may not exist yet.
        if let Some(parent) = std::path::Path::new(&actual).parent() {
            if !parent.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(parent);
            }
        }
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
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

    fn disk_prealloc(&mut self, id: DiskId, size: u64) -> Result<()> {
        let file = self.files.get_mut(&id).ok_or(Error::NotFound)?;
        file.set_len(size).map_err(|_| Error::Io)
    }

    fn disk_flush(&mut self, id: DiskId) -> Result<()> {
        let file = self.files.get_mut(&id).ok_or(Error::NotFound)?;
        file.sync_data().map_err(|_| Error::Io)
    }

    fn disk_close(&mut self, id: DiskId) {
        self.files.remove(&id);
    }
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

fn sock_to_netaddr(a: SocketAddr) -> NetAddr {
    match a {
        SocketAddr::V4(v4) => {
            let ip = v4.ip().octets();
            NetAddr::V4(ip, v4.port())
        }
        SocketAddr::V6(v6) => {
            let mut ip = [0u8; 16];
            for (i, seg) in v6.ip().segments().iter().enumerate() {
                ip[i * 2] = (seg >> 8) as u8;
                ip[i * 2 + 1] = (seg & 0xff) as u8;
            }
            NetAddr::V6(ip, v6.port())
        }
    }
}
