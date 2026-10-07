//! The name-resolution subsystem: RFC 8484 DNS-over-HTTPS, a TTL-aware cache
//! and hedged providers.
//!
//! Why this exists at all: the engine resolves hostnames through the OS
//! resolver, and on a hostile or broken network that resolver is the weakest
//! link. Two failure modes matter for a BitTorrent client:
//!
//! * **Poisoning / hijacking.** An ISP resolver that answers with a captive
//!   portal or an ad server leaves every `udp://` tracker and every BEP-5 DHT
//!   router unreachable, so the swarm never forms even though the network is
//!   fine. DoH answers come from a signed, encrypted channel instead.
//! * **Stalls.** One blocked domain (a bootstrap router, a tracker host) that
//!   hangs for the full OS timeout serialises everything behind it. Here every
//!   lookup has a hard budget, dead providers are circuit-broken, and a slow
//!   provider is raced against the next one (hedging) instead of waited for.
//!
//! The subsystem is deliberately transport-agnostic: [`DohTransport`] is the
//! only way a query reaches the network, which keeps every rule above unit
//! testable without a socket. The real implementation is one HTTPS POST in
//! [`crate::host`], using the same `courierust` client as tracker traffic.
//!
//! Scope, stated honestly: this feeds [`typebit::platform::Host::resolve_host`]
//! (DHT bootstrap) and `resolve_host_all` (UDP trackers) — the two places the
//! engine asks the host to resolve something — and it backs the address check
//! in [`crate::netpolicy`]. HTTPS tracker and web-seed requests are dialled
//! inside `courierust`, which resolves them itself and must, because the name
//! is also the TLS identity and the virtual host; the cache still warms them and
//! the guard still refuses an answer that points somewhere it must not.

use std::collections::HashMap;
use std::collections::VecDeque;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{channel, RecvTimeoutError};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use typebit::platform::NetAddr;

/// Largest response we accept from a DoH endpoint. DNS answers are tiny; the
/// cap only exists so a hostile or broken endpoint cannot feed us a stream.
const MAX_DOH_RESPONSE: usize = 64 * 1024;

/// Wall-clock budget for one DoH exchange.
pub const DOH_TIMEOUT: Duration = Duration::from_millis(2500);

/// Delay before the second provider is also asked. The first answer wins, so
/// this is the tail-latency ceiling on a healthy network, not a cost paid on
/// every lookup — a provider that answers in 20 ms is never raced.
pub const HEDGE_DELAY: Duration = Duration::from_millis(250);

/// A provider that keeps failing is skipped for this long (scaled by its
/// consecutive failure count, capped). Long enough that a blocked endpoint
/// costs one timeout per window, short enough that recovery (captive portal
/// gone, VPN up) is quick.
const BREAKER_OPEN: Duration = Duration::from_secs(600);
/// Upper bound on the breaker backoff multiplier.
const BREAKER_MAX_BACKOFF_STEPS: u32 = 6;

/// Cache bounds. 512 names × (a handful of addresses + a TTL) is tens of
/// kilobytes — cheap next to the cost of a poisoned answer.
const CACHE_CAPACITY: usize = 512;
/// Floor on a cached TTL: a record that expires instantly would query on every
/// announce, which is exactly the traffic DoH exists to reduce.
const CACHE_MIN_TTL: Duration = Duration::from_secs(30);
const CACHE_MAX_TTL: Duration = Duration::from_secs(3600);
/// Negative answers (NXDOMAIN / no records) are kept briefly. A dead tracker
/// host must not be re-queried every 100 ms tick.
const CACHE_NEGATIVE_TTL: Duration = Duration::from_secs(60);
/// TTL for an answer that came from the OS resolver rather than DoH.
///
/// Shorter on purpose: it is the answer we trust least (it may be poisoned),
/// so it only exists to stop a per-tick re-resolve while the background DoH
/// refresh replaces it.
const CACHE_OS_TTL: Duration = Duration::from_secs(60);
/// Wall-clock bound on waiting for another thread's in-flight lookup.
const SINGLE_FLIGHT_WAIT: Duration = Duration::from_millis(1200);

// ---------------------------------------------------------------------------
// Wire format (RFC 1035 §4) — only what A/AAAA needs
// ---------------------------------------------------------------------------

/// DNS record types this subsystem asks for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Rtype {
    A,
    Aaaa,
}

impl Rtype {
    fn code(self) -> u16 {
        match self {
            Rtype::A => 1,
            Rtype::Aaaa => 28,
        }
    }
}

/// DNS response codes we care about.
pub const RCODE_NOERROR: u8 = 0;
pub const RCODE_NXDOMAIN: u8 = 3;

/// One decoded answer record.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Record {
    /// Owner name, lowercased.
    pub name: String,
    pub rtype: u16,
    /// Seconds the answer may be cached.
    pub ttl: u32,
    pub data: Vec<u8>,
}

/// A decoded DNS response (the parts an A/AAAA lookup needs).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub id: u16,
    pub rcode: u8,
    pub truncated: bool,
    /// `true` when the server set AA (authoritative).
    pub authoritative: bool,
    pub answers: Vec<Record>,
}

impl Message {
    /// IPv4 addresses in the answer section (following one CNAME chain).
    pub fn ipv4(&self, qname: &str) -> Vec<Ipv4Addr> {
        self.chain(qname, 1)
            .into_iter()
            .filter_map(|r| match r.data.as_slice() {
                [a, b, c, d] => Some(Ipv4Addr::new(*a, *b, *c, *d)),
                _ => None,
            })
            .collect()
    }

    /// IPv6 addresses in the answer section (following one CNAME chain).
    pub fn ipv6(&self, qname: &str) -> Vec<Ipv6Addr> {
        self.chain(qname, 28)
            .into_iter()
            .filter_map(|r| {
                let mut b = [0u8; 16];
                if r.data.len() == 16 {
                    b.copy_from_slice(&r.data);
                    Some(Ipv6Addr::from(b))
                } else {
                    None
                }
            })
            .collect()
    }

    /// Records of `rtype` reachable from `qname` through CNAMEs.
    ///
    /// Some public resolvers answer a lookup with the CNAME *and* the A/AAAA
    /// record of the target under the target's own name; others repeat the
    /// original owner name. Both shapes are handled by walking names.
    fn chain(&self, qname: &str, rtype: u16) -> Vec<&Record> {
        let mut current = qname.to_ascii_lowercase();
        let mut out = Vec::new();
        let mut hops = 0;
        loop {
            for r in &self.answers {
                if r.rtype == rtype && r.name == current {
                    out.push(r);
                }
            }
            if !out.is_empty() || hops >= 8 {
                return out;
            }
            // No address at this name: follow the next CNAME, if any.
            let Some(cname) = self
                .answers
                .iter()
                .find(|r| r.rtype == 5 && r.name == current)
            else {
                return out;
            };
            match decode_name(&cname.data) {
                Some(next) if next != current => {
                    current = next;
                    hops += 1;
                }
                _ => return out,
            }
        }
    }
}

/// Encodes a query for `name`/`rtype` with the standard one-question layout.
///
/// Returns `None` for a name that cannot be encoded (empty label, label over
/// 63 bytes, or a name over 255 bytes) — a malformed tracker hostname must
/// never reach the wire.
pub fn encode_query(id: u16, name: &str, rtype: Rtype) -> Option<Vec<u8>> {
    let trimmed = name.trim_end_matches('.');
    if trimmed.is_empty() || trimmed.len() > 253 {
        return None;
    }
    let mut q = Vec::with_capacity(trimmed.len() + 24);
    q.extend_from_slice(&id.to_be_bytes());
    // RD=1 (recursion desired) — we are talking to a recursive resolver.
    q.extend_from_slice(&[0x01, 0x00]);
    q.extend_from_slice(&1u16.to_be_bytes()); // QDCOUNT
    q.extend_from_slice(&[0, 0, 0, 0, 0, 0]); // AN/NS/AR counts
    for label in trimmed.split('.') {
        if label.is_empty() || label.len() > 63 {
            return None;
        }
        q.push(label.len() as u8);
        // Hostnames are ASCII on the wire; the engine only ever hands us
        // ASCII hosts, and anything else is rejected rather than mis-encoded.
        if !label.is_ascii() {
            return None;
        }
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0); // root label
    q.extend_from_slice(&rtype.code().to_be_bytes());
    q.extend_from_slice(&1u16.to_be_bytes()); // QCLASS = IN
    Some(q)
}

/// Decodes a response message. `query_id` must match, which is what stops a
/// stale or spoofed answer from being accepted as the answer to this query.
pub fn decode_response(buf: &[u8], query_id: u16) -> Option<Message> {
    if buf.len() < 12 {
        return None;
    }
    let id = u16::from_be_bytes([buf[0], buf[1]]);
    if id != query_id {
        return None;
    }
    // QR bit must be set: this is a response, not a reflected query.
    if buf[2] & 0x80 == 0 {
        return None;
    }
    let rcode = buf[3] & 0x0f;
    let truncated = buf[2] & 0x02 != 0;
    let authoritative = buf[2] & 0x04 != 0;
    let qdcount = u16::from_be_bytes([buf[4], buf[5]]) as usize;
    let ancount = u16::from_be_bytes([buf[6], buf[7]]) as usize;

    let mut off = 12usize;
    for _ in 0..qdcount {
        let _ = parse_name(buf, &mut off)?;
        // QTYPE + QCLASS
        if off + 4 > buf.len() {
            return None;
        }
        off += 4;
    }

    let mut answers = Vec::new();
    for _ in 0..ancount {
        let name = parse_name(buf, &mut off)?;
        if off + 10 > buf.len() {
            return None;
        }
        let rtype = u16::from_be_bytes([buf[off], buf[off + 1]]);
        let ttl = u32::from_be_bytes([buf[off + 4], buf[off + 5], buf[off + 6], buf[off + 7]]);
        let rdlen = u16::from_be_bytes([buf[off + 8], buf[off + 9]]) as usize;
        off += 10;
        if off + rdlen > buf.len() {
            return None;
        }
        let data = buf[off..off + rdlen].to_vec();
        off += rdlen;
        answers.push(Record {
            name,
            rtype,
            ttl,
            data,
        });
    }

    Some(Message {
        id,
        rcode,
        truncated,
        authoritative,
        answers,
    })
}

/// Parses a (possibly compressed) domain name starting at `*off`.
///
/// `seen` carries the byte offsets followed so far, which is what turns a
/// pointer loop (a classic hostile-response trick) into a decoded `None`
/// instead of an infinite loop.
fn parse_name(buf: &[u8], off: &mut usize) -> Option<String> {
    let mut labels: Vec<String> = Vec::new();
    let mut cursor = *off;
    let mut advanced_end: Option<usize> = None;
    let mut jumps = 0usize;

    loop {
        if cursor >= buf.len() {
            return None;
        }
        let len = buf[cursor] as usize;
        if len == 0 {
            cursor += 1;
            break;
        }
        // Top two bits set = compression pointer.
        if len & 0xc0 == 0xc0 {
            if cursor + 1 >= buf.len() {
                return None;
            }
            let target = ((len & 0x3f) << 8) | buf[cursor + 1] as usize;
            if advanced_end.is_none() {
                advanced_end = Some(cursor + 2);
            }
            jumps += 1;
            // 64 jumps is far beyond any legitimate encoding; a loop would
            // otherwise stall inside the engine thread.
            if jumps > 64 || target >= buf.len() {
                return None;
            }
            cursor = target;
            continue;
        }
        if len > 63 || cursor + 1 + len > buf.len() {
            return None;
        }
        let label = &buf[cursor + 1..cursor + 1 + len];
        labels.push(String::from_utf8_lossy(label).to_ascii_lowercase());
        cursor += 1 + len;
        if labels.len() > 127 {
            return None;
        }
    }

    *off = advanced_end.unwrap_or(cursor);
    Some(labels.join("."))
}

/// Decodes a name from record data (a CNAME target).
///
/// A pointer inside the record data is relative to the enclosing message, not
/// to the record, so a compressed target fails the decode here. That is safe:
/// resolvers also repeat the A/AAAA record under its own owner name, which the
/// answer-section scan finds without needing the CNAME at all.
fn decode_name(data: &[u8]) -> Option<String> {
    if data.is_empty() || data[0] & 0xc0 == 0xc0 {
        return None;
    }
    let mut off = 0usize;
    parse_name(data, &mut off)
}

// ---------------------------------------------------------------------------
// Cache
// ---------------------------------------------------------------------------

/// Addresses remembered for one name, per family and per expiry.
#[derive(Debug, Clone, Default)]
struct NameEntry {
    v4: Option<(Vec<Ipv4Addr>, Instant)>,
    v6: Option<(Vec<Ipv6Addr>, Instant)>,
    /// When set, the name resolved to nothing until this instant.
    negative_until: Option<Instant>,
}

/// What the cache knows about a name right now.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Lookup {
    /// Live addresses (may be a mix of families).
    Fresh(Vec<IpAddr>),
    /// A live negative entry: the name resolved to nothing recently, and
    /// asking again inside the window would be pointless traffic.
    Negative,
    /// Nothing usable — the caller resolves and writes the result back.
    Expired,
}

/// TTL-aware, bounded resolve cache with negative caching.
#[derive(Debug)]
pub struct DnsCache {
    entries: HashMap<String, NameEntry>,
    /// Most-recently-used first. Capacity is small enough that the linear
    /// touch is cheaper than a linked-list node per entry.
    recency: VecDeque<String>,
    capacity: usize,
    /// Counters for the stats/log surface.
    pub hits: u64,
    pub misses: u64,
}

impl DnsCache {
    pub fn new(capacity: usize) -> Self {
        DnsCache {
            entries: HashMap::new(),
            recency: VecDeque::new(),
            capacity: capacity.max(1),
            hits: 0,
            misses: 0,
        }
    }

    fn touch(&mut self, name: &str) {
        if let Some(pos) = self.recency.iter().position(|n| n == name) {
            self.recency.remove(pos);
        }
        self.recency.push_front(name.to_string());
    }

    fn evict_if_needed(&mut self) {
        while self.entries.len() > self.capacity {
            match self.recency.pop_back() {
                Some(victim) => {
                    self.entries.remove(&victim);
                }
                None => break,
            }
        }
    }

    /// Live addresses for `name`, never blocking and never touching a socket.
    ///
    /// `now` is passed in so the cache is deterministic under test.
    pub fn lookup(&mut self, name: &str, now: Instant, want_v6: bool) -> Lookup {
        let key = name.to_ascii_lowercase();
        let Some(entry) = self.entries.get(&key) else {
            self.misses += 1;
            return Lookup::Expired;
        };
        if let Some(until) = entry.negative_until {
            if until > now {
                self.hits += 1;
                return Lookup::Negative;
            }
        }
        let mut out: Vec<IpAddr> = Vec::new();
        if let Some((v4, exp)) = &entry.v4 {
            if *exp > now {
                out.extend(v4.iter().copied().map(IpAddr::V4));
            }
        }
        if want_v6 {
            if let Some((v6, exp)) = &entry.v6 {
                if *exp > now {
                    out.extend(v6.iter().copied().map(IpAddr::V6));
                }
            }
        }
        if out.is_empty() {
            // Expired (or an empty positive entry): a miss, so the caller
            // refreshes. The entry is kept — it costs nothing and the next
            // write replaces it.
            self.misses += 1;
            return Lookup::Expired;
        }
        self.hits += 1;
        self.touch(&key);
        Lookup::Fresh(out)
    }

    /// Records a positive answer. `ttl` is clamped to a sane window.
    pub fn put(
        &mut self,
        name: &str,
        v4: &[Ipv4Addr],
        v6: &[Ipv6Addr],
        ttl: Duration,
        now: Instant,
    ) {
        let key = name.to_ascii_lowercase();
        let ttl = ttl.clamp(CACHE_MIN_TTL, CACHE_MAX_TTL);
        let entry = self.entries.entry(key.clone()).or_default();
        entry.negative_until = None;
        if !v4.is_empty() {
            entry.v4 = Some((v4.to_vec(), now + ttl));
        }
        if !v6.is_empty() {
            entry.v6 = Some((v6.to_vec(), now + ttl));
        }
        self.touch(&key);
        self.evict_if_needed();
    }

    /// Records "this name has nothing right now", so a dead host is not
    /// re-queried on every engine tick.
    pub fn put_negative(&mut self, name: &str, now: Instant) {
        let key = name.to_ascii_lowercase();
        let entry = self.entries.entry(key.clone()).or_default();
        entry.negative_until = Some(now + CACHE_NEGATIVE_TTL);
        self.touch(&key);
        self.evict_if_needed();
    }

    /// Drops everything (used when the provider set changes).
    pub fn clear(&mut self) {
        self.entries.clear();
        self.recency.clear();
    }

    /// Entries currently held (for the log/stats surface).
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// True when nothing is cached. Present because `len` exists (clippy's
    /// convention) and because callers checking "is this cold?" read better.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/// Why a DoH exchange failed. Kept small: the caller only ever decides
/// "try the next provider" or "count a failure against this provider".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DohError {
    /// Connection/TLS/HTTP failure — the provider is unusable right now.
    Transport,
    /// The provider answered with something that is not a DNS message.
    BadResponse,
    /// The provider answered `rcode` != NOERROR that is not NXDOMAIN.
    ServFail,
}

/// One DoH POST. The real implementation lives in [`crate::host`]; tests
/// inject a canned one, which is why every rule in this module is testable
/// without a socket.
pub trait DohTransport: Send + Sync {
    /// POSTs `query` (wire format) to `url` and returns the wire response.
    fn exchange(&self, url: &str, query: &[u8], timeout: Duration) -> Result<Vec<u8>, DohError>;
}

/// What one provider currently looks like.
#[derive(Debug, Clone)]
struct Provider {
    url: String,
    state: ProviderState,
}

#[derive(Debug, Clone)]
enum ProviderState {
    Ready,
    /// Skipped until this instant (a failure was recorded).
    Open {
        until: Instant,
        failures: u32,
    },
}

impl Provider {
    fn usable(&self, now: Instant) -> bool {
        match &self.state {
            ProviderState::Ready => true,
            // One probe per window: a recovered provider is found within a
            // single extra attempt instead of staying dark forever.
            ProviderState::Open { until, .. } => now >= *until,
        }
    }
}

/// Records provider outcomes so a blocked endpoint costs one timeout per
/// window instead of one per lookup.
#[derive(Clone)]
struct Breaker {
    inner: Arc<Inner>,
}

/// The outcome of one provider attempt, as reported by the attempt thread.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Outcome {
    Ok,
    Fail,
}

impl Breaker {
    fn record(&self, url: &str, outcome: Outcome) {
        let mut providers = self.inner.providers.lock().unwrap_or_else(|e| e.into_inner());
        let Some(provider) = providers.iter_mut().find(|p| p.url == url) else {
            return;
        };
        match outcome {
            Outcome::Ok => {
                provider.state = ProviderState::Ready;
                self.inner
                    .provider_ok
                    .fetch_add(1, Ordering::Relaxed);
            }
            Outcome::Fail => {
                let failures = match provider.state {
                    ProviderState::Open { failures, .. } => failures.saturating_add(1),
                    ProviderState::Ready => 1,
                };
                // Backoff grows with consecutive failures: a provider that is
                // blocked (not merely flaky) stops costing a timeout on every
                // window, and a provider that failed once is retried soon.
                let steps = failures.clamp(1, BREAKER_MAX_BACKOFF_STEPS);
                provider.state = ProviderState::Open {
                    until: Instant::now() + BREAKER_OPEN * steps,
                    failures,
                };
                self.inner
                    .provider_failures
                    .fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/// Which address families to ask for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IpFamily {
    /// Both, IPv4 first (the socket layer decides what it can send).
    Any,
    /// IPv4 only: skips the AAAA query entirely on networks without IPv6.
    V4Only,
}

/// Shared resolver state. Cheap to clone (one `Arc`), safe to use from the
/// engine thread and the resolver workers at the same time.
pub struct DnsService {
    inner: Arc<Inner>,
}

struct Inner {
    transport: Arc<dyn DohTransport>,
    providers: Mutex<Vec<Provider>>,
    cache: Mutex<DnsCache>,
    /// Names with a lookup in flight, so N callers become one query.
    inflight: Mutex<HashMap<String, Arc<InflightSlot>>>,
    family: IpFamily,
    /// Counter of DoH queries actually issued (stats/log surface).
    queries: AtomicU64,
    /// Counter of answers served from the cache.
    cache_hits: AtomicU64,
    /// Counter of OS-resolver fallbacks.
    os_fallbacks: AtomicU64,
    /// Counter of successful provider attempts.
    provider_ok: AtomicU64,
    /// Counter of provider attempts that tripped a breaker.
    provider_failures: AtomicU64,
}

#[derive(Default)]
struct InflightSlot {
    state: Mutex<Option<()>>,
    done: Condvar,
}

/// One resolution outcome, with the provenance the log line reports.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    Cache,
    Doh,
    OsResolver,
    Negative,
}

/// A resolution result plus where it came from.
#[derive(Debug, Clone)]
pub struct Resolved {
    pub addrs: Vec<NetAddr>,
    pub source: Source,
}

/// One provider's answer: both address families plus the TTL to cache them for.
type DohAnswer = (Vec<Ipv4Addr>, Vec<Ipv6Addr>, Duration);

impl DnsService {
    /// Builds a service over `providers` (in priority order).
    ///
    /// An empty provider list disables DoH entirely: every lookup then goes
    /// straight to the OS resolver, which is the correct behaviour for a user
    /// who does not want a third party in the resolution path.
    pub fn new(transport: Arc<dyn DohTransport>, providers: Vec<String>, family: IpFamily) -> Self {
        let providers = providers
            .into_iter()
            .map(|url| Provider {
                url: url.trim().to_string(),
                state: ProviderState::Ready,
            })
            .filter(|p| !p.url.is_empty())
            .collect();
        DnsService {
            inner: Arc::new(Inner {
                transport,
                providers: Mutex::new(providers),
                cache: Mutex::new(DnsCache::new(CACHE_CAPACITY)),
                inflight: Mutex::new(HashMap::new()),
                family,
                queries: AtomicU64::new(0),
                cache_hits: AtomicU64::new(0),
                os_fallbacks: AtomicU64::new(0),
                provider_ok: AtomicU64::new(0),
                provider_failures: AtomicU64::new(0),
            }),
        }
    }

    /// True when a DoH provider is configured (even if all are backing off —
    /// the cache still needs to be consulted before any OS lookup).
    pub fn doh_enabled(&self) -> bool {
        !self
            .inner
            .providers
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_empty()
    }

    pub fn stats(&self) -> DnsStats {
        DnsStats {
            queries: self.inner.queries.load(Ordering::Relaxed),
            cache_hits: self.inner.cache_hits.load(Ordering::Relaxed),
            os_fallbacks: self.inner.os_fallbacks.load(Ordering::Relaxed),
            provider_ok: self.inner.provider_ok.load(Ordering::Relaxed),
            provider_failures: self.inner.provider_failures.load(Ordering::Relaxed),
            providers: self
                .inner
                .providers
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .iter()
                .map(|p| (p.url.clone(), matches!(p.state, ProviderState::Ready)))
                .collect(),
        }
    }

    /// Cached answer only — never blocks, never opens a socket.
    ///
    /// This is what the synchronous `Host` hooks use on the engine thread: a
    /// resolve that would take a second must not stall the tick loop. A live
    /// negative entry and an empty answer both report `None`, because that is
    /// exactly what the engine then does with them.
    pub fn cached(&self, host: &str, port: u16, now: Instant) -> Option<Vec<NetAddr>> {
        let want_v6 = self.inner.family == IpFamily::Any;
        match self
            .inner
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .lookup(host, now, want_v6)
        {
            Lookup::Fresh(addrs) if !addrs.is_empty() => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                Some(addrs.into_iter().map(|a| with_port(a, port)).collect())
            }
            _ => None,
        }
    }

    /// Resolves `host`, preferring the cache and falling back to the OS
    /// resolver — but **never** to a blocking DoH query.
    ///
    /// A miss triggers a background DoH refresh (see
    /// [`DnsService::refresh_async`]) so the *next* lookup is authoritative.
    /// That ordering matters: DoH on the engine thread would stall the tick
    /// loop for as long as a provider takes to time out.
    ///
    /// An OS answer is cached too, with a deliberately short TTL: it is the
    /// answer we trust least, and a DoH result replaces it as soon as the
    /// background refresh lands.
    pub fn resolve_blocking_os(&self, host: &str, port: u16, now: Instant) -> Resolved {
        let want_v6 = self.inner.family == IpFamily::Any;
        match self
            .inner
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .lookup(host, now, want_v6)
        {
            Lookup::Fresh(addrs) => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                return Resolved {
                    addrs: addrs.into_iter().map(|a| with_port(a, port)).collect(),
                    source: Source::Cache,
                };
            }
            Lookup::Negative => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                return Resolved {
                    addrs: Vec::new(),
                    source: Source::Negative,
                };
            }
            Lookup::Expired => {}
        }

        let addrs = os_resolve(host, port, self.inner.family);
        self.inner.os_fallbacks.fetch_add(1, Ordering::Relaxed);
        if addrs.is_empty() {
            // Remember the failure briefly, but still ask DoH in the
            // background: "no such name" from the OS resolver is exactly the
            // case DoH exists to correct.
            self.inner
                .cache
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .put_negative(host, now);
            self.refresh_async(host);
            return Resolved {
                addrs,
                source: Source::Negative,
            };
        }
        let (v4, v6) = split_families(&addrs);
        self.inner
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put(host, &v4, &v6, CACHE_OS_TTL, now);
        self.refresh_async(host);
        Resolved {
            addrs,
            source: Source::OsResolver,
        }
    }

    /// Full resolution: negative cache → single-flight → DoH → OS resolver.
    ///
    /// Call this from a resolver worker, never from the engine thread.
    pub fn resolve(&self, host: &str, port: u16, now: Instant) -> Resolved {
        let want_v6 = self.inner.family == IpFamily::Any;
        match self
            .inner
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .lookup(host, now, want_v6)
        {
            Lookup::Fresh(addrs) => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                return Resolved {
                    addrs: addrs.into_iter().map(|a| with_port(a, port)).collect(),
                    source: Source::Cache,
                };
            }
            Lookup::Negative => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                // A name that just resolved to nothing is not asked again;
                // that is the entire value of the negative entry.
                return Resolved {
                    addrs: Vec::new(),
                    source: Source::Negative,
                };
            }
            Lookup::Expired => {}
        }

        // Single-flight: join an in-flight lookup for the same name instead of
        // issuing a second query. The wait is bounded — a wedged lookup must
        // not wedge this caller — and a timeout simply falls through to its
        // own attempt.
        let inflight_key = host.to_ascii_lowercase();
        let slot = {
            let mut inflight = self
                .inner
                .inflight
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            match inflight.get(&inflight_key) {
                Some(existing) => Some(existing.clone()),
                None => {
                    let fresh = Arc::new(InflightSlot::default());
                    inflight.insert(inflight_key.clone(), fresh);
                    None
                }
            }
        };
        if let Some(slot) = slot {
            let guard = slot.state.lock().unwrap_or_else(|e| e.into_inner());
            let (guard, _) = slot
                .done
                .wait_timeout(guard, SINGLE_FLIGHT_WAIT)
                .unwrap_or_else(|e| e.into_inner());
            drop(guard);
            if let Some(addrs) = self.cached(host, port, Instant::now()) {
                return Resolved {
                    addrs,
                    source: Source::Cache,
                };
            }
        }

        let result = self.query_doh(host);
        let now = Instant::now();
        // Write the cache *before* waking the single-flight waiters: they
        // re-read the cache on wake, and waking them first is exactly how a
        // thundering herd reappears (eight callers, eight queries).
        let resolved = match result {
            Ok(Some((v4, v6, ttl))) => {
                self.inner
                    .cache
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .put(host, &v4, &v6, ttl, now);
                let mut addrs: Vec<NetAddr> =
                    v4.iter().map(|ip| NetAddr::V4(ip.octets(), port)).collect();
                if self.inner.family == IpFamily::Any {
                    addrs.extend(v6.iter().map(|ip| NetAddr::V6(ip.octets(), port)));
                }
                if addrs.is_empty() {
                    Resolved {
                        addrs,
                        source: Source::Negative,
                    }
                } else {
                    Resolved {
                        addrs,
                        source: Source::Doh,
                    }
                }
            }
            Ok(None) => {
                self.inner
                    .cache
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .put_negative(host, now);
                Resolved {
                    addrs: Vec::new(),
                    source: Source::Negative,
                }
            }
            Err(_) => {
                let addrs = os_resolve(host, port, self.inner.family);
                self.inner.os_fallbacks.fetch_add(1, Ordering::Relaxed);
                self.inner.cache.lock().unwrap_or_else(|e| e.into_inner()).put(
                    host,
                    &v4_of(&addrs),
                    &v6_of(&addrs),
                    CACHE_OS_TTL,
                    now,
                );
                Resolved {
                    addrs,
                    source: Source::OsResolver,
                }
            }
        };

        // Now release the waiters: what they need is already in the cache.
        {
            let mut inflight = self.inner.inflight.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(slot) = inflight.remove(&inflight_key) {
                let guard = slot.state.lock().unwrap_or_else(|e| e.into_inner());
                drop(guard);
                slot.done.notify_all();
            }
        }
        resolved
    }

    /// Asks the providers for `host`, hedging the second one behind the first.
    ///
    /// Returns `Ok(Some(..))` for an answer, `Ok(None)` for a definitive
    /// "no such name" (cached negative), and `Err(())` when every provider
    /// failed — the caller then uses the OS resolver.
    fn query_doh(&self, host: &str) -> Result<Option<DohAnswer>, ()> {
        let now = Instant::now();
        let usable: Vec<String> = {
            let providers = self
                .inner
                .providers
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            providers
                .iter()
                .filter(|p| p.usable(now))
                .map(|p| p.url.clone())
                .collect()
        };
        if usable.is_empty() {
            return Err(());
        }

        let want: Vec<Rtype> = match self.inner.family {
            IpFamily::Any => vec![Rtype::A, Rtype::Aaaa],
            IpFamily::V4Only => vec![Rtype::A],
        };

        // Both families are asked concurrently: a single-family client pays
        // one query, a dual-stack client pays the slower of the two.
        let mut handles = Vec::new();
        let breaker = Breaker {
            inner: self.inner.clone(),
        };
        for rtype in want {
            let host = host.to_string();
            let transport = self.inner.transport.clone();
            let providers = usable.clone();
            let breaker = breaker.clone();
            let id = next_query_id();
            let handle = std::thread::Builder::new()
                .name("typebit-doh".to_string())
                .spawn(move || {
                    doh_lookup_hedged(&transport, &breaker, &providers, &host, rtype, id)
                });
            if let Ok(h) = handle {
                handles.push((rtype, h));
            }
        }
        self.inner.queries.fetch_add(1, Ordering::Relaxed);

        let mut v4 = Vec::new();
        let mut v6 = Vec::new();
        let mut ttl = CACHE_MAX_TTL;
        let mut answered = false;
        let mut nxdomain = false;

        for (rtype, handle) in handles {
            match handle.join() {
                Ok(Ok(Answer::Addrs { addrs, ttl: t })) => {
                    answered = true;
                    ttl = ttl.min(t);
                    for ip in addrs {
                        match ip {
                            IpAddr::V4(a) if rtype == Rtype::A => v4.push(a),
                            IpAddr::V6(a) if rtype == Rtype::Aaaa => v6.push(a),
                            _ => {}
                        }
                    }
                }
                Ok(Ok(Answer::NoSuchName)) => nxdomain = true,
                Ok(Err(())) | Err(_) => {}
            }
        }

        if answered {
            return Ok(Some((v4, v6, ttl)));
        }
        if nxdomain {
            return Ok(None);
        }
        Err(())
    }

    /// Warms the cache for `host` on a background thread.
    ///
    /// Used by the synchronous `Host` hooks: they must not block, but the
    /// *next* lookup should be authoritative even if the OS resolver just lied.
    pub fn refresh_async(&self, host: &str) {
        let key = host.to_ascii_lowercase();
        {
            let mut inflight = self
                .inner
                .inflight
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            if inflight.contains_key(&key) {
                return;
            }
            inflight.insert(key.clone(), Arc::new(InflightSlot::default()));
        }
        if !self.doh_enabled() {
            let mut inflight = self
                .inner
                .inflight
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            inflight.remove(&key);
            return;
        }
        // Drop the placeholder: `resolve` re-registers it. The point of this
        // early insert is only to collapse repeated warm-up requests from
        // consecutive engine ticks into one query.
        let svc = self.clone();
        let host_owned = host.to_string();
        let slot_key = key.clone();
        let spawned = std::thread::Builder::new()
            .name("typebit-doh-warm".to_string())
            .spawn(move || {
                {
                    let mut inflight = svc
                        .inner
                        .inflight
                        .lock()
                        .unwrap_or_else(|e| e.into_inner());
                    inflight.remove(&slot_key);
                }
                let _ = svc.resolve(&host_owned, 0, Instant::now());
            });
        if spawned.is_err() {
            let mut inflight = self
                .inner
                .inflight
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            inflight.remove(&key);
        }
    }
}

impl Clone for DnsService {
    fn clone(&self) -> Self {
        DnsService {
            inner: self.inner.clone(),
        }
    }
}

#[cfg(test)]
impl DnsService {
    /// Fills the cache directly.
    ///
    /// A test that cares about *what happens to an answer* (a rewrite with the
    /// original `Host` header preserved, a poisoned name that must not be
    /// dialled) should not have to stand up a DoH server to get one; this is
    /// the seam that keeps those tests deterministic.
    pub fn prime_for_test(&self, host: &str, ip: Ipv4Addr) {
        self.inner
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put(host, &[ip], &[], CACHE_MAX_TTL, Instant::now());
    }
}

enum Answer {
    Addrs { addrs: Vec<IpAddr>, ttl: Duration },
    NoSuchName,
}

/// Asks `providers` for one record type, starting the next provider after
/// [`HEDGE_DELAY`] so a slow provider is bypassed instead of waited out.
fn doh_lookup_hedged(
    transport: &Arc<dyn DohTransport>,
    breaker: &Breaker,
    providers: &[String],
    host: &str,
    rtype: Rtype,
    id: u16,
) -> Result<Answer, ()> {
    let query = match encode_query(id, host, rtype) {
        Some(q) => q,
        None => return Err(()),
    };
    let (tx, rx) = channel();
    let mut outstanding = 0usize;
    let mut index = 0usize;
    let mut last_error: Option<DohError> = None;
    let mut any_nxdomain = false;

    // Start the first provider immediately.
    send_attempt(
        &tx, transport, breaker, providers, index, &query, id, host, rtype,
    );
    outstanding += 1;

    loop {
        if outstanding == 0 && index + 1 >= providers.len() {
            // Nothing in flight and nothing left to try.
            return match last_error {
                None if any_nxdomain => Ok(Answer::NoSuchName),
                _ => Err(()),
            };
        }
        // While another provider is still available, wait only the hedge
        // delay; once this is the last one, wait for a real answer.
        let wait = if outstanding == 0 || index + 1 < providers.len() {
            HEDGE_DELAY
        } else {
            DOH_TIMEOUT
        };
        match rx.recv_timeout(wait) {
            Ok(msg) => {
                outstanding -= 1;
                match msg {
                    Attempt::Ok(addrs, ttl) => return Ok(Answer::Addrs { addrs, ttl }),
                    Attempt::NoSuchName => {
                        any_nxdomain = true;
                        // NXDOMAIN is authoritative enough to return as soon
                        // as no other attempt is still running.
                        if outstanding == 0 && index + 1 >= providers.len() {
                            return Ok(Answer::NoSuchName);
                        }
                    }
                    Attempt::Failed(e) => last_error = Some(e),
                }
            }
            Err(RecvTimeoutError::Timeout) => {
                // Hedge: bring the next provider in while the first runs.
                if index + 1 < providers.len() {
                    index += 1;
                    send_attempt(
                        &tx, transport, breaker, providers, index, &query, id, host, rtype,
                    );
                    outstanding += 1;
                }
            }
            Err(RecvTimeoutError::Disconnected) => {
                return Err(());
            }
        }
    }
}

enum Attempt {
    Ok(Vec<IpAddr>, Duration),
    NoSuchName,
    Failed(DohError),
}

impl Clone for Attempt {
    fn clone(&self) -> Self {
        match self {
            Attempt::Ok(addrs, ttl) => Attempt::Ok(addrs.clone(), *ttl),
            Attempt::NoSuchName => Attempt::NoSuchName,
            Attempt::Failed(e) => Attempt::Failed(*e),
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn send_attempt(
    tx: &std::sync::mpsc::Sender<Attempt>,
    transport: &Arc<dyn DohTransport>,
    breaker: &Breaker,
    providers: &[String],
    index: usize,
    query: &[u8],
    id: u16,
    host: &str,
    rtype: Rtype,
) {
    let Some(url) = providers.get(index) else {
        return;
    };
    let url = url.clone();
    let query = query.to_vec();
    let host = host.to_string();
    // The closure takes ownership of a clone so the error path below can
    // still report a failure on the original sender.
    let attempt_tx = tx.clone();
    let transport = transport.clone();
    let breaker = breaker.clone();
    let attempt_url = url.clone();
    let spawned = std::thread::Builder::new()
        .name("typebit-doh-attempt".to_string())
        .spawn(move || {
            let msg = match transport.exchange(&url, &query, DOH_TIMEOUT) {
                Err(e) => Attempt::Failed(e),
                Ok(body) => {
                    if body.len() > MAX_DOH_RESPONSE {
                        Attempt::Failed(DohError::BadResponse)
                    } else {
                        match decode_response(&body, id) {
                            None => Attempt::Failed(DohError::BadResponse),
                            Some(msg) if msg.truncated => Attempt::Failed(DohError::BadResponse),
                            Some(msg) if msg.rcode == RCODE_NXDOMAIN => Attempt::NoSuchName,
                            Some(msg) if msg.rcode != RCODE_NOERROR => {
                                Attempt::Failed(DohError::ServFail)
                            }
                            Some(msg) => {
                                let addrs: Vec<IpAddr> = match rtype {
                                    Rtype::A => {
                                        msg.ipv4(&host).into_iter().map(IpAddr::V4).collect()
                                    }
                                    Rtype::Aaaa => {
                                        msg.ipv6(&host).into_iter().map(IpAddr::V6).collect()
                                    }
                                };
                                let ttl = msg
                                    .answers
                                    .iter()
                                    .filter(|r| r.rtype == rtype.code())
                                    .map(|r| r.ttl)
                                    .min()
                                    .unwrap_or(300);
                                if addrs.is_empty() {
                                    // NOERROR with no address: treat as a
                                    // negative answer rather than a failure.
                                    Attempt::NoSuchName
                                } else {
                                    Attempt::Ok(addrs, Duration::from_secs(ttl as u64))
                                }
                            }
                        }
                    }
                }
            };
            // A closed receiver means the caller already had its answer.
            let _ = attempt_tx.send(msg.clone());
            // Report the provider's health from the attempt itself: a losing
            // hedge still tells the truth about the provider it used.
            breaker.record(
                &attempt_url,
                match msg {
                    Attempt::Ok(..) | Attempt::NoSuchName => Outcome::Ok,
                    Attempt::Failed(_) => Outcome::Fail,
                },
            );
        });
    if spawned.is_err() {
        // The spawn failed, so `tx` was never moved: report a transport
        // failure so the hedge can move on to the next provider.
        let _ = tx.send(Attempt::Failed(DohError::Transport));
    }
}

/// Monotonic-ish query id: only has to differ from other in-flight queries
/// from this process, which a counter does exactly.
fn next_query_id() -> u16 {
    static NEXT: AtomicU64 = AtomicU64::new(1);
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    ((n & 0xffff) ^ ((n >> 16) & 0xffff)) as u16
}

/// The OS resolver, used as the fallback (and as the only path when DoH is
/// off). Filtered to the requested family so a v6 address never lands on a
/// v4-only socket.
fn os_resolve(host: &str, port: u16, family: IpFamily) -> Vec<NetAddr> {
    let mut out: Vec<NetAddr> = Vec::new();
    let Ok(iter) = (host, port).to_socket_addrs() else {
        return out;
    };
    for sa in iter {
        let na = socket_addr_to_netaddr(sa);
        if family == IpFamily::V4Only && matches!(na, NetAddr::V6(..)) {
            continue;
        }
        if !out.contains(&na) {
            out.push(na);
        }
    }
    if family == IpFamily::V4Only {
        return out;
    }
    // IPv4 first: the routing table, compact peer lists and most CDNs are v4,
    // and the cost of a v6 attempt that cannot leave the host is a full
    // timeout on some networks.
    out.sort_by_key(|a| match a {
        NetAddr::V4(..) => 0,
        NetAddr::V6(..) => 1,
    });
    out
}

fn socket_addr_to_netaddr(sa: SocketAddr) -> NetAddr {
    match sa {
        SocketAddr::V4(v4) => NetAddr::V4(v4.ip().octets(), v4.port()),
        SocketAddr::V6(v6) => NetAddr::V6(v6.ip().octets(), v6.port()),
    }
}

/// Applies `port` to a cached address (the cache stores addresses, not ports).
fn with_port(addr: IpAddr, port: u16) -> NetAddr {
    match addr {
        IpAddr::V4(ip) => NetAddr::V4(ip.octets(), port),
        IpAddr::V6(ip) => NetAddr::V6(ip.octets(), port),
    }
}

/// Splits resolved endpoints into the two families, in order, deduplicated.
fn split_families(addrs: &[NetAddr]) -> (Vec<Ipv4Addr>, Vec<Ipv6Addr>) {
    (v4_of(addrs), v6_of(addrs))
}

/// The IPv4 half of a resolved set (deduplicated, order preserved).
fn v4_of(addrs: &[NetAddr]) -> Vec<Ipv4Addr> {
    let mut out = Vec::new();
    for a in addrs {
        if let NetAddr::V4(ip, _) = a {
            let ip = Ipv4Addr::new(ip[0], ip[1], ip[2], ip[3]);
            if !out.contains(&ip) {
                out.push(ip);
            }
        }
    }
    out
}

/// The IPv6 half of a resolved set (deduplicated, order preserved).
fn v6_of(addrs: &[NetAddr]) -> Vec<Ipv6Addr> {
    let mut out = Vec::new();
    for a in addrs {
        if let NetAddr::V6(ip, _) = a {
            let ip = Ipv6Addr::from(*ip);
            if !out.contains(&ip) {
                out.push(ip);
            }
        }
    }
    out
}

/// Reported by [`DnsService::stats`].
#[derive(Debug, Clone)]
pub struct DnsStats {
    pub queries: u64,
    pub cache_hits: u64,
    pub os_fallbacks: u64,
    pub provider_ok: u64,
    pub provider_failures: u64,
    /// `(url, healthy)` per configured provider.
    pub providers: Vec<(String, bool)>,
}

impl DnsStats {
    /// One log line, in the shape the stats dialog shows.
    pub fn summary(&self) -> String {
        let healthy = self.providers.iter().filter(|(_, ok)| *ok).count();
        format!(
            "dns: {} queries, {} cache hits, {} os fallbacks, providers {}/{} up ({} ok / {} failed)",
            self.queries,
            self.cache_hits,
            self.os_fallbacks,
            healthy,
            self.providers.len(),
            self.provider_ok,
            self.provider_failures
        )
    }
}

/// Default provider ladder.
///
/// Ordered by "authoritative first, reachable second": a resolver that is not
/// subject to local DNS policy answers truthfully, and the hedge means the
/// second entry is only consulted when the first is slow or dead. The list is
/// user-overridable — a user behind a corporate resolver, or one who does not
/// want any third party in the path, can empty it and get the OS resolver back.
pub const DEFAULT_DOH_PROVIDERS: &[&str] = &[
    "https://cloudflare-dns.com/dns-query",
    "https://dns.alidns.com/dns-query",
    "https://doh.pub/dns-query",
];

/// True when this host looks like it has a usable IPv6 stack.
///
/// Asked once and remembered: an AAAA query on a v4-only network is a wasted
/// round trip on every cold name.
pub fn has_ipv6() -> bool {
    static CACHE: AtomicU64 = AtomicU64::new(u64::MAX);
    let cached = CACHE.load(Ordering::Relaxed);
    if cached != u64::MAX {
        return cached == 1;
    }
    let probe = std::net::UdpSocket::bind("[::]:0")
        .and_then(|s| s.connect("[2001:4860:4860::8888]:53").map(|_| s))
        .is_ok();
    CACHE.store(if probe { 1 } else { 0 }, Ordering::Relaxed);
    probe
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    fn response_bytes(id: u16, name: &str, rtype: Rtype, ips: &[&str], ttl: u32) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&id.to_be_bytes());
        out.extend_from_slice(&[0x81, 0x80]); // QR + RD + RA
        out.extend_from_slice(&1u16.to_be_bytes());
        out.extend_from_slice(&(ips.len() as u16).to_be_bytes());
        out.extend_from_slice(&[0, 0, 0, 0]);
        for label in name.split('.') {
            out.push(label.len() as u8);
            out.extend_from_slice(label.as_bytes());
        }
        out.push(0);
        out.extend_from_slice(&rtype.code().to_be_bytes());
        out.extend_from_slice(&1u16.to_be_bytes());
        for ip in ips {
            // A compression pointer to the question name at offset 12 —
            // exercises the pointer path, which is what real answers use.
            out.extend_from_slice(&[0xc0, 0x0c]);
            out.extend_from_slice(&rtype.code().to_be_bytes());
            out.extend_from_slice(&1u16.to_be_bytes()); // IN
            out.extend_from_slice(&ttl.to_be_bytes());
            match ip.parse::<IpAddr>().unwrap() {
                IpAddr::V4(a) => {
                    out.extend_from_slice(&4u16.to_be_bytes());
                    out.extend_from_slice(&a.octets());
                }
                IpAddr::V6(a) => {
                    out.extend_from_slice(&16u16.to_be_bytes());
                    out.extend_from_slice(&a.octets());
                }
            }
        }
        out
    }

    #[test]
    fn encode_query_is_well_formed() {
        let q = encode_query(0x1234, "tracker.example.com", Rtype::A).unwrap();
        assert_eq!(&q[0..2], &[0x12, 0x34]);
        assert_eq!(q[2], 0x01); // RD
        assert_eq!(&q[4..6], &[0, 1]); // QDCOUNT
        assert_eq!(q[12], 7);
        assert_eq!(&q[13..20], b"tracker");
        assert_eq!(&q[q.len() - 4..q.len() - 2], &[0, 1]); // QTYPE A
                                                           // Rejections: empty, over-long label, over-long name, non-ASCII.
        assert!(encode_query(1, "", Rtype::A).is_none());
        assert!(encode_query(1, &"a".repeat(64), Rtype::A).is_none());
        assert!(encode_query(1, &"a.".repeat(200), Rtype::A).is_none());
        assert!(encode_query(1, "exämple.com", Rtype::A).is_none());
    }

    #[test]
    fn decode_response_reads_compressed_answers() {
        let id = 0xbeef;
        let body = response_bytes(
            id,
            "tracker.example.com",
            Rtype::A,
            &["1.2.3.4", "5.6.7.8"],
            120,
        );
        let msg = decode_response(&body, id).unwrap();
        assert!(!msg.truncated);
        assert_eq!(
            msg.ipv4("tracker.example.com"),
            vec![Ipv4Addr::new(1, 2, 3, 4), Ipv4Addr::new(5, 6, 7, 8)]
        );
        // A wrong id, a query-shaped message and a truncated buffer are all
        // rejected rather than parsed on a hope.
        assert!(decode_response(&body, id ^ 1).is_none());
        let mut as_query = body.clone();
        as_query[2] &= 0x7f; // clear QR
        assert!(decode_response(&as_query, id).is_none());
        assert!(decode_response(&body[..8], id).is_none());
    }

    #[test]
    fn decode_follows_cname_chain() {
        let id: u16 = 7;
        let mut body = Vec::new();
        body.extend_from_slice(&id.to_be_bytes());
        body.extend_from_slice(&[0x81, 0x80]);
        body.extend_from_slice(&1u16.to_be_bytes()); // QD
        body.extend_from_slice(&2u16.to_be_bytes()); // AN: CNAME + A
        body.extend_from_slice(&[0, 0, 0, 0]);
        for label in ["www", "example", "com"] {
            body.push(label.len() as u8);
            body.extend_from_slice(label.as_bytes());
        }
        body.push(0);
        body.extend_from_slice(&1u16.to_be_bytes());
        body.extend_from_slice(&1u16.to_be_bytes());
        // CNAME -> target.example.com (name at offset 12 via pointer)
        body.extend_from_slice(&[0xc0, 0x0c]);
        body.extend_from_slice(&5u16.to_be_bytes());
        body.extend_from_slice(&1u16.to_be_bytes());
        body.extend_from_slice(&60u32.to_be_bytes());
        let target = b"\x06target\x07example\x03com\x00";
        body.extend_from_slice(&(target.len() as u16).to_be_bytes());
        body.extend_from_slice(target);
        // A record owned by the CNAME target, full name (no pointer).
        body.extend_from_slice(target);
        body.extend_from_slice(&1u16.to_be_bytes());
        body.extend_from_slice(&1u16.to_be_bytes());
        body.extend_from_slice(&60u32.to_be_bytes());
        body.extend_from_slice(&4u16.to_be_bytes());
        body.extend_from_slice(&[9, 9, 9, 9]);
        let msg = decode_response(&body, id).unwrap();
        assert_eq!(msg.ipv4("www.example.com"), vec![Ipv4Addr::new(9, 9, 9, 9)]);
    }

    #[test]
    fn decode_rejects_pointer_loops() {
        // A name whose first label is a pointer to itself: naive decoders
        // spin forever here.
        let mut buf = Vec::new();
        buf.extend_from_slice(&1u16.to_be_bytes());
        buf.extend_from_slice(&[0x81, 0x80]);
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&1u16.to_be_bytes());
        buf.extend_from_slice(&[0, 0, 0, 0]);
        buf.extend_from_slice(&[0xc0, 0x0c]);
        buf.extend_from_slice(&1u16.to_be_bytes());
        buf.extend_from_slice(&1u16.to_be_bytes());
        buf.extend_from_slice(&10u32.to_be_bytes());
        buf.extend_from_slice(&4u16.to_be_bytes());
        buf.extend_from_slice(&[1, 1, 1, 1]);
        assert!(decode_response(&buf, 1).is_none());
    }

    #[test]
    fn cache_honours_ttl_and_negative_entries() {
        let now = Instant::now();
        let mut cache = DnsCache::new(4);
        cache.put(
            "a.example",
            &[Ipv4Addr::new(10, 0, 0, 1)],
            &[],
            Duration::from_secs(120),
            now,
        );
        assert_eq!(
            cache.lookup("a.example", now, false),
            Lookup::Fresh(vec![IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1))])
        );
        assert_eq!(cache.hits, 1);
        // A short TTL is raised to the documented floor, so a tracker that
        // hands out 1 s records does not make us query it on every announce.
        cache.put(
            "b.example",
            &[Ipv4Addr::new(10, 0, 0, 2)],
            &[],
            Duration::from_secs(1),
            now,
        );
        assert!(matches!(
            cache.lookup("b.example", now + Duration::from_secs(20), false),
            Lookup::Fresh(_)
        ));
        assert_eq!(
            cache.lookup(
                "b.example",
                now + CACHE_MIN_TTL + Duration::from_secs(1),
                false
            ),
            Lookup::Expired
        );
        // After the (clamped) TTL a lookup is a miss, not a stale answer.
        assert_eq!(
            cache.lookup(
                "a.example",
                now + CACHE_MAX_TTL + Duration::from_secs(1),
                false
            ),
            Lookup::Expired
        );
        // A negative entry answers "nothing" without hitting the network.
        cache.put_negative("dead.example", now);
        assert_eq!(cache.lookup("dead.example", now, false), Lookup::Negative);
        assert_eq!(
            cache.lookup(
                "dead.example",
                now + CACHE_NEGATIVE_TTL + Duration::from_secs(1),
                false
            ),
            Lookup::Expired
        );
        // Names are matched case-insensitively (RFC 4343).
        assert!(matches!(cache.lookup("A.EXAMPLE", now, false), Lookup::Fresh(_)));
    }

    #[test]
    fn cache_evicts_least_recently_used() {
        let now = Instant::now();
        let mut cache = DnsCache::new(2);
        cache.put(
            "one",
            &[Ipv4Addr::new(1, 1, 1, 1)],
            &[],
            Duration::from_secs(60),
            now,
        );
        cache.put(
            "two",
            &[Ipv4Addr::new(2, 2, 2, 2)],
            &[],
            Duration::from_secs(60),
            now,
        );
        // Touch "one" so "two" becomes the eviction candidate.
        assert!(matches!(cache.lookup("one", now, false), Lookup::Fresh(_)));
        cache.put(
            "three",
            &[Ipv4Addr::new(3, 3, 3, 3)],
            &[],
            Duration::from_secs(60),
            now,
        );
        assert_eq!(cache.len(), 2);
        assert_eq!(cache.lookup("two", now, false), Lookup::Expired);
        assert!(matches!(cache.lookup("one", now, false), Lookup::Fresh(_)));
        assert!(matches!(cache.lookup("three", now, false), Lookup::Fresh(_)));
    }

    /// A transport whose answers (and failures) are scripted per provider.
    struct Scripted {
        answers: Mutex<HashMap<String, Vec<u8>>>,
        fail: Mutex<Vec<String>>,
        calls: AtomicUsize,
        delay_ms: AtomicU64,
    }

    impl Scripted {
        fn new() -> Self {
            Scripted {
                answers: Mutex::new(HashMap::new()),
                fail: Mutex::new(Vec::new()),
                calls: AtomicUsize::new(0),
                delay_ms: AtomicU64::new(0),
            }
        }

        fn serve(&self, url: &str, body: Vec<u8>) {
            self.answers
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(url.to_string(), body);
        }

        fn break_url(&self, url: &str) {
            self.fail
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(url.to_string());
        }

        fn set_delay(&self, ms: u64) {
            self.delay_ms.store(ms, Ordering::Relaxed);
        }
    }

    impl DohTransport for Scripted {
        fn exchange(
            &self,
            url: &str,
            query: &[u8],
            _timeout: Duration,
        ) -> Result<Vec<u8>, DohError> {
            self.calls.fetch_add(1, Ordering::Relaxed);
            let delay = self.delay_ms.load(Ordering::Relaxed);
            if delay > 0 {
                std::thread::sleep(Duration::from_millis(delay));
            }
            if self
                .fail
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .iter()
                .any(|u| u == url)
            {
                return Err(DohError::Transport);
            }
            let id = u16::from_be_bytes([query[0], query[1]]);
            // Answer with the type the question asked for.
            let qtype = u16::from_be_bytes([query[query.len() - 4], query[query.len() - 3]]);
            let name = "tracker.example.com";
            let body = match self
                .answers
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .get(url)
            {
                Some(template) => {
                    let mut b = template.clone();
                    b[0] = query[0];
                    b[1] = query[1];
                    b
                }
                None if qtype == 1 => response_bytes(id, name, Rtype::A, &["203.0.113.10"], 90),
                None => response_bytes(id, name, Rtype::Aaaa, &["2001:db8::10"], 90),
            };
            Ok(body)
        }
    }

    fn service(transport: Arc<dyn DohTransport>, urls: &[&str]) -> DnsService {
        DnsService::new(
            transport,
            urls.iter().map(|s| s.to_string()).collect(),
            IpFamily::Any,
        )
    }

    #[test]
    fn resolve_uses_doh_then_cache() {
        let scripted = Arc::new(Scripted::new());
        let svc = service(scripted.clone(), &["https://a.example/dns-query"]);
        let now = Instant::now();
        let first = svc.resolve("tracker.example.com", 6969, now);
        assert_eq!(first.source, Source::Doh);
        assert!(first
            .addrs
            .iter()
            .any(|a| matches!(a, NetAddr::V4([203, 0, 113, 10], 6969))));
        let calls_after_first = scripted.calls.load(Ordering::Relaxed);
        let second = svc.resolve("tracker.example.com", 6969, Instant::now());
        assert_eq!(second.source, Source::Cache);
        assert_eq!(scripted.calls.load(Ordering::Relaxed), calls_after_first);
        // The synchronous hook reads the same cache without a socket.
        let cached = svc
            .cached("tracker.example.com", 6969, Instant::now())
            .unwrap();
        assert_eq!(cached.len(), second.addrs.len());
    }

    /// Builds an NXDOMAIN response for `name`.
    fn nxdomain_bytes(id: u16, name: &str) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&id.to_be_bytes());
        out.extend_from_slice(&[0x81, 0x83]); // QR + RA + rcode 3
        out.extend_from_slice(&1u16.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes());
        out.extend_from_slice(&[0, 0, 0, 0]);
        for label in name.split('.') {
            out.push(label.len() as u8);
            out.extend_from_slice(label.as_bytes());
        }
        out.push(0);
        out.extend_from_slice(&1u16.to_be_bytes());
        out.extend_from_slice(&1u16.to_be_bytes());
        out
    }

    #[test]
    fn nxdomain_is_cached_and_not_re_asked() {
        let scripted = Arc::new(Scripted::new());
        // The transport answers whatever the endpoint serves, with the query
        // id patched in — exactly how a real resolver replies.
        scripted.serve(
            "https://a.example/dns-query",
            nxdomain_bytes(0, "dead.example.com"),
        );
        let svc = service(scripted.clone(), &["https://a.example/dns-query"]);
        let first = svc.resolve("dead.example.com", 6969, Instant::now());
        assert_eq!(first.source, Source::Negative);
        assert!(first.addrs.is_empty());
        let calls = scripted.calls.load(Ordering::Relaxed);
        let second = svc.resolve("dead.example.com", 6969, Instant::now());
        assert_eq!(second.source, Source::Negative);
        assert!(second.addrs.is_empty());
        // The point: the second lookup never reached the network.
        assert_eq!(scripted.calls.load(Ordering::Relaxed), calls);
    }

    #[test]
    fn resolve_falls_back_to_os_when_every_provider_fails() {
        let scripted = Arc::new(Scripted::new());
        scripted.break_url("https://a.example/dns-query");
        scripted.break_url("https://b.example/dns-query");
        let svc = service(
            scripted.clone(),
            &["https://a.example/dns-query", "https://b.example/dns-query"],
        );
        // "localhost" is guaranteed to resolve without the network; a real
        // name would make this test depend on the machine's connectivity.
        let out = svc.resolve("localhost", 8080, Instant::now());
        assert_eq!(out.source, Source::OsResolver);
        assert!(!out.addrs.is_empty());
        // Both providers are broken, so the flight tried both for both record
        // types: 4 failed attempts, and both breakers open.
        let before = scripted.calls.load(Ordering::Relaxed);
        assert_eq!(before, 4);
        let again = svc.resolve("localhost", 8080, Instant::now());
        assert_eq!(again.source, Source::Cache);
        // The fallback answer was cached, so nothing touches the network again.
        assert_eq!(scripted.calls.load(Ordering::Relaxed), before);
        assert!(svc.cached("localhost", 8080, Instant::now()).is_some());
        let stats = svc.stats();
        assert_eq!(stats.provider_failures, 4);
        assert!(stats.providers.iter().all(|(_, healthy)| !healthy));
    }

    #[test]
    fn resolve_does_not_ask_every_provider_when_the_first_answers() {
        let scripted = Arc::new(Scripted::new());
        // The first provider answers instantly, so the hedge timer (250 ms)
        // never fires and the second provider is never contacted.
        let svc = service(
            scripted.clone(),
            &["https://a.example/dns-query", "https://b.example/dns-query"],
        );
        let out = svc.resolve("tracker.example.com", 6969, Instant::now());
        assert_eq!(out.source, Source::Doh);
        assert_eq!(scripted.calls.load(Ordering::Relaxed), 2); // A + AAAA
    }

    #[test]
    fn resolve_hedges_a_slow_provider() {
        let scripted = Arc::new(Scripted::new());
        // Every attempt is slow (400 ms > 250 ms hedge) but only the first
        // provider is delay-limited in reality; both are, so the assertion is
        // about the *number of attempts*, not which one won.
        scripted.set_delay(300);
        let svc = service(
            scripted.clone(),
            &["https://a.example/dns-query", "https://b.example/dns-query"],
        );
        let started = Instant::now();
        let out = svc.resolve("tracker.example.com", 6969, started);
        assert_eq!(out.source, Source::Doh);
        assert!(started.elapsed() < Duration::from_millis(1200));
    }

    #[test]
    fn cache_only_lookup_never_blocks() {
        let scripted = Arc::new(Scripted::new());
        scripted.set_delay(2000);
        let svc = service(scripted, &["https://a.example/dns-query"]);
        let started = Instant::now();
        // Nothing cached yet: the sync path returns the OS answer (or nothing)
        // and must not wait for the 2 s provider.
        let _ = svc.resolve_blocking_os("localhost", 80, Instant::now());
        assert!(started.elapsed() < Duration::from_millis(500));
    }

    #[test]
    fn cache_serves_the_synchronous_hooks_without_doh() {
        // The engine calls `cached` on its own thread: it must answer from the
        // cache and never block on a provider, which is what makes DoH safe to
        // keep out of the tick loop.
        let scripted = Arc::new(Scripted::new());
        let svc = service(scripted.clone(), &["https://a.example/dns-query"]);
        let now = Instant::now();
        assert!(svc.cached("tracker.example.com", 80, now).is_none());
        // A resolve fills the cache...
        svc.resolve("tracker.example.com", 80, now);
        let calls = scripted.calls.load(Ordering::Relaxed);
        // ...and every later synchronous read is free.
        let hit = svc
            .cached("tracker.example.com", 80, Instant::now())
            .expect("cached answer");
        assert!(hit
            .iter()
            .any(|a| matches!(a, NetAddr::V4([203, 0, 113, 10], 80))));
        assert_eq!(scripted.calls.load(Ordering::Relaxed), calls);
        // The same cache answers with whatever port the caller needs.
        assert!(svc.cached("tracker.example.com", 6969, Instant::now()).is_some());
    }

    #[test]
    fn single_flight_collapses_parallel_lookups() {
        let scripted = Arc::new(Scripted::new());
        scripted.set_delay(150);
        let svc = service(scripted.clone(), &["https://a.example/dns-query"]);
        let mut handles = Vec::new();
        for _ in 0..8 {
            let svc = svc.clone();
            handles.push(std::thread::spawn(move || {
                svc.resolve("tracker.example.com", 6969, Instant::now())
                    .source
            }));
        }
        let sources: Vec<Source> = handles.into_iter().filter_map(|h| h.join().ok()).collect();
        assert_eq!(sources.len(), 8);
        // One flight issues at most two queries (A + AAAA); the other seven
        // callers either join it or read the freshly written cache.
        let calls = scripted.calls.load(Ordering::Relaxed);
        assert!(calls <= 4, "expected <=4 provider calls, saw {calls}");
        assert!(sources
            .iter()
            .any(|s| *s == Source::Doh || *s == Source::Cache));
    }
}
