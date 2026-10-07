//! Name resolution: a RecurseX-backed recursive resolver behind a
//! non-blocking edge for the engine thread.
//!
//! Why a resolver at all: the engine asks the *host* to resolve names, and on a
//! hostile or broken network the OS resolver is the weakest link in the client.
//! Two failure modes matter for BitTorrent:
//!
//! * **Poisoning / hijacking.** An ISP resolver that answers with a captive
//!   portal or an ad server leaves every `udp://` tracker and every BEP-5 DHT
//!   router unreachable, so the swarm never forms even though the network is
//!   fine.
//! * **Stalls.** One blocked domain (a bootstrap router, a tracker host) that
//!   hangs for the full OS timeout serialises everything behind it.
//!
//! ## What is ours and what is not
//!
//! The resolution itself is [`recurse_x`]: an iterative walk from the root with
//! RFC 9156 QNAME minimisation and 0x20 case randomisation, DNSSEC validation,
//! a multi-tier semantic cache with serve-stale (RFC 8767), and DoT/DoH
//! upstreams when the user wants a forwarder. Reimplementing any of that here
//! would be strictly worse — that is the entire reason this file shrank by two
//! thirds — so it is not reimplemented.
//!
//! What stays here is the part a resolver cannot provide, because it is about
//! *this* engine rather than about DNS:
//!
//! 1. **A non-blocking edge.** `Resolver::resolve` is blocking by design, and
//!    [`typebit::platform::Host::resolve_host`] runs on the engine thread, where
//!    a one-second stall is a stalled tick loop. [`DnsService::cached`] and
//!    [`DnsService::resolve_blocking_os`] answer from a small memo (the
//!    resolver's own answers, kept with their TTL) or from the OS resolver, and
//!    warm the resolver on a background thread for next time.
//! 2. **Single-flight across callers.** Six BEP-5 routers bootstrapping at once
//!    are one lookup per name, not six.
//! 3. **A published surface.** Mode, upstream health (measured RTT and
//!    timeouts per endpoint, read from the resolver's own path model), and the
//!    counters the stats dialog shows.
//! 4. **Upstream normalisation.** A settings file says
//!    `https://cloudflare-dns.com/dns-query`; a forwarder needs an *address*,
//!    because resolving a resolver's name through the resolver is a chicken and
//!    egg. See [`WELL_KNOWN`] and [`parse_upstream`].
//!
//! Scope, stated honestly: this feeds `resolve_host` (DHT bootstrap),
//! `resolve_host_all` (UDP trackers) and the address check in
//! [`crate::netpolicy`]. HTTPS tracker and web-seed requests are dialled inside
//! `courierust`, which resolves them itself and must, because the name is also
//! the TLS identity and the virtual host; the memo warms them and the guard
//! still refuses an answer that points somewhere it must not.

use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use recurse_x::cache::CacheConfig;
use recurse_x::error::ErrorKind;
use recurse_x::forward::Forwarder;
use recurse_x::name::Name;
use recurse_x::qtype::{Rcode, RrType};
use recurse_x::rdata::RData;
use recurse_x::resolver::{EngineConfig, RateLimitConfig};
use recurse_x::upstream::{Endpoint, Proto};
use recurse_x::{Resolver, ResolverConfig};
use typebit::platform::NetAddr;

use crate::netpolicy::NetworkPolicy;

/// Wall-clock budget for one attempt against one upstream, in milliseconds.
/// Short on purpose: the OS resolver is the fallback, and a mobile user would
/// rather get a wrong answer fast than a right one after ten seconds.
const RESOLVER_TIMEOUT_MS: u64 = 2_500;

/// Wall-clock budget for one resolution including everything it spawns —
/// referrals, out-of-bailiwick name-server lookups, CNAME chains. This is the
/// number that makes the worst case finite, and it is the only delay any caller
/// of [`DnsService::resolve`] can observe.
const QUERY_BUDGET_MS: u64 = 8_000;

/// Bounds on the memo, the engine-facing copy of the resolver's answers.
///
/// This is *not* a second cache: the authoritative store is the resolver's own
/// multi-tier cache, which holds properties this one cannot (serve-stale,
/// per-RRset credibility, DNSSEC state). The memo exists so that a lookup from
/// the engine thread is a hash probe, never a query.
const MEMO_CAPACITY: usize = 512;
/// Floor on a memoised TTL: a record that expires instantly would re-query on
/// every announce, which is the traffic a resolver exists to reduce.
const MEMO_MIN_TTL: Duration = Duration::from_secs(30);
/// Ceiling on a memoised TTL, so a pathological record cannot pin an address.
const MEMO_MAX_TTL: Duration = Duration::from_secs(3_600);
/// Negative answers are kept briefly. A dead tracker host must not be re-queried
/// every 100 ms tick.
const MEMO_NEGATIVE_TTL: Duration = Duration::from_secs(60);
/// TTL for an answer that came from the OS resolver instead of the resolver
/// above. Shorter on purpose: it is the answer we trust least, so it only
/// exists to stop a per-tick re-resolve until the background warm-up replaces
/// it.
const MEMO_OS_TTL: Duration = Duration::from_secs(60);

/// Wall-clock bound on waiting for another thread's in-flight lookup.
const SINGLE_FLIGHT_WAIT: Duration = Duration::from_millis(1_200);
/// Concurrent background warm-ups. A censored network can fail every lookup, and
/// the warm-up is the retry — so it needs a bound of its own.
const MAX_WARM_ACTIVE: usize = 4;

/// This build compiles RecurseX without the QUIC transports (`doq`, `doh3`).
///
/// They cost ~140 KB of vendored QUIC/TLS per ABI for a capability that no
/// setting in this app exposes, so they are off, and an upstream that names one
/// is reported instead of silently becoming something else.
const QUIC_UPSTREAMS_SUPPORTED: bool = false;

// ---------------------------------------------------------------------------
// Upstreams
// ---------------------------------------------------------------------------

/// Well-known resolver endpoints, keyed by the hostname a user is likely to
/// type.
///
/// A forwarder is addressed by **IP** — the TLS name travels in the `#fragment`
/// — so `https://cloudflare-dns.com/dns-query` has to become
/// `https://1.1.1.1/dns-query#cloudflare-dns.com`. Doing that through the OS
/// resolver would work on a healthy network and fail on exactly the network the
/// feature exists for, so the common providers are baked in. A provider that is
/// not in this table is resolved once at startup through the OS resolver, which
/// is the best available answer and is reported when it fails.
const WELL_KNOWN: &[(&str, &str)] = &[
    ("cloudflare-dns.com", "1.1.1.1"),
    ("one.one.one.one", "1.1.1.1"),
    ("mozilla.cloudflare-dns.com", "1.1.1.1"),
    ("dns.google", "8.8.8.8"),
    ("dns.alidns.com", "223.5.5.5"),
    ("doh.pub", "1.12.12.12"),
    ("dns.pub", "1.12.12.12"),
    ("dot.pub", "1.12.12.12"),
    ("dns.quad9.net", "9.9.9.9"),
    ("dns.adguard-dns.com", "94.140.14.14"),
    ("unfiltered.adguard-dns.com", "94.140.14.140"),
    ("doh.opendns.com", "208.67.222.222"),
    ("dns.umbrella.com", "208.67.222.222"),
    ("anycast.censurfridns.dk", "91.239.100.100"),
];

/// An upstream that made it into the resolver, kept so its measured health can
/// be reported. `spec` is the user's own text, `endpoint` is what was dialled.
#[derive(Debug, Clone)]
pub struct Upstream {
    pub spec: String,
    endpoint: Endpoint,
}

/// What one configured upstream became: a forwarder, some notes, or nothing.
#[derive(Debug)]
struct ParsedUpstream {
    forwarder: Forwarder,
    upstream: Upstream,
    notes: Vec<String>,
}

/// Translates the user's upstream list into RecurseX forwarders.
///
/// Deliberately total: an entry that cannot be used is reported with its own
/// text and dropped, never silently ignored and never guessed at.
fn build_upstreams(specs: &[String]) -> (Vec<Forwarder>, Vec<Upstream>, Vec<String>) {
    let mut forwarders = Vec::new();
    let mut upstreams = Vec::new();
    let mut problems = Vec::new();
    for spec in specs {
        match parse_upstream(spec) {
            Ok(parsed) => {
                problems.extend(parsed.notes);
                upstreams.push(parsed.upstream);
                forwarders.push(parsed.forwarder);
            }
            Err(why) => problems.push(why),
        }
    }
    (forwarders, upstreams, problems)
}

fn parse_upstream(spec: &str) -> Result<ParsedUpstream, String> {
    let original = spec.trim();
    if original.is_empty() {
        return Err("空的解析器条目".to_string());
    }
    let (rest, identity) = match original.split_once('#') {
        Some((a, b)) => (a.trim(), Some(b.trim())),
        None => (original, None),
    };
    if rest.is_empty() {
        return Err(format!("{original}: 只有 #名称，缺少地址"));
    }
    let (scheme, rest) = match rest.split_once("://") {
        Some((sch, r)) => (sch.to_ascii_lowercase(), r.trim()),
        // A bare address is plain DNS to that server, which is also what the
        // reference implementations do.
        None => ("udp".to_string(), rest),
    };
    let (authority, path) = match rest.split_once('/') {
        Some((a, p)) => (a.trim(), Some(p.trim())),
        None => (rest, None),
    };
    if authority.is_empty() {
        return Err(format!("{original}: 缺少地址"));
    }

    let (proto, default_port, want_path) = match scheme.as_str() {
        "udp" => (Proto::Udp, 53u16, false),
        "tcp" => (Proto::Tcp, 53, false),
        "tls" | "dot" => (Proto::Tls, 853, false),
        "https" | "doh" => (Proto::DoH, 443, true),
        "quic" | "doq" | "h3" | "doh3" => {
            if !QUIC_UPSTREAMS_SUPPORTED {
                return Err(format!(
                    "{original}: 本构建未编译 QUIC 上游（{scheme}://），请改用 tls:// 或 https://"
                ));
            }
            (Proto::DoQ, 853, false)
        }
        other => {
            return Err(format!(
                "{original}: 未知协议 {other}://（可用 udp/tcp/tls/https）"
            ))
        }
    };
    if path.is_some() && !want_path {
        return Err(format!(
            "{original}: {scheme}:// 不接受路径（只有 DoH 有请求路径）"
        ));
    }

    let mut notes = Vec::new();
    let (ip, port, name) = match split_authority(authority) {
        Some((ip, port)) => (ip, port.unwrap_or(default_port), identity.map(|s| s.to_string())),
        None => {
            // A hostname: resolve it once, here, so that a broken OS resolver
            // cannot break the resolver.
            let (name, port) = split_name_port(authority)
                .map_err(|why| format!("{original}: {why}"))?;
            let port = port.unwrap_or(default_port);
            let ip = well_known_or_os(&name, port)
                .ok_or_else(|| format!("{original}: 无法解析 {name} 的地址（请直接写 IP）"))?;
            (ip, port, Some(name))
        }
    };
    if port == 0 {
        return Err(format!("{original}: 端口 0 不是端口"));
    }

    let endpoint = Endpoint::new(ip, port, proto);
    if matches!(proto, Proto::Udp | Proto::Tcp) {
        if name.is_some() && identity.is_some() {
            notes.push(format!(
                "{original}: 明文上游不使用 TLS 名称，#{} 已忽略",
                identity.unwrap_or_default()
            ));
        }
        let forwarder = Forwarder::plain(endpoint);
        return Ok(ParsedUpstream {
            forwarder,
            upstream: Upstream {
                spec: original.to_string(),
                endpoint,
            },
            notes,
        });
    }

    let name = name.ok_or_else(|| {
        format!("{original}: 加密上游需要 TLS 名称，请追加 #dns.example")
    })?;
    let mut forwarder = Forwarder::encrypted(endpoint, name);
    if let Some(p) = path {
        if !p.is_empty() {
            forwarder = forwarder.with_path(format!("/{p}"));
        }
    }
    Ok(ParsedUpstream {
        forwarder,
        upstream: Upstream {
            spec: original.to_string(),
            endpoint,
        },
        notes,
    })
}

/// Splits an IP literal with an optional port. IPv6 needs its brackets.
fn split_authority(authority: &str) -> Option<(IpAddr, Option<u16>)> {
    let (host, port) = if let Some(rest) = authority.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        let port = tail.strip_prefix(':').filter(|p| !p.is_empty());
        (host, port)
    } else {
        match authority.rsplit_once(':') {
            Some((host, port)) if !host.contains(':') => (host, Some(port)),
            _ => (authority, None),
        }
    };
    let ip = host.parse::<IpAddr>().ok()?;
    let port = match port {
        Some(p) => Some(p.parse::<u16>().ok()?),
        None => None,
    };
    Some((ip, port))
}

/// Splits `host[:port]` when the host is a name rather than an address.
fn split_name_port(authority: &str) -> Result<(String, Option<u16>), String> {
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) => match port.parse::<u16>() {
            Ok(p) => (host, Some(p)),
            Err(_) => (authority, None),
        },
        None => (authority, None),
    };
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() || !host.is_ascii() {
        return Err("不是可用的主机名".to_string());
    }
    Ok((host, port))
}

/// The address for `name`, from [`WELL_KNOWN`] or, failing that, the OS
/// resolver — which is the last moment the OS resolver is allowed to matter.
fn well_known_or_os(name: &str, port: u16) -> Option<IpAddr> {
    if let Some((_, ip)) = WELL_KNOWN.iter().find(|(host, _)| *host == name) {
        return ip.parse().ok();
    }
    let mut addrs: Vec<IpAddr> = (name, port)
        .to_socket_addrs()
        .ok()?
        .map(|sa| sa.ip())
        .filter(|ip| !ip.is_unspecified())
        .collect();
    // IPv4 first: a v6 address that cannot leave the host is a full timeout on
    // some networks, and no public resolver is v6-only.
    addrs.sort_by_key(|ip| ip.is_ipv6());
    addrs.first().copied()
}

/// The resolver's configuration for this client.
///
/// Sizes are the interesting part. RecurseX defaults to a *serving* resolver's
/// cache (231k entries across three tiers) and a 100/s client rate limit, which
/// is right for a DNS server and wrong for a phone: a torrent client resolves a
/// few hundred distinct names per session, so the tiers are sized at a few
/// thousand entries (well under a megabyte) and the rate limiter is told there
/// is exactly one client — the app itself — so a swarm's worth of tracker
/// announces cannot trip it.
fn resolver_config(forwarders: Vec<Forwarder>) -> ResolverConfig {
    ResolverConfig {
        cache: CacheConfig {
            hot_capacity: 128,
            warm_capacity: 2_048,
            cold_capacity: 512,
            nx_capacity: 512,
            // Serve-stale is the feature that keeps a swarm alive through a
            // resolver outage; an hour is enough for that and short enough that
            // a stale address cannot outlive a network change.
            stale_window_secs: 3_600,
            negative_ttl_cap: 120,
            max_ttl_cap: 7_200,
            ..Default::default()
        },
        // Upstreams go in **both** places, and that is not redundancy.
        // `dns.upstreams` is the group table the routing layer chooses from, and
        // it is what decides "forward or walk from the root"; `engine.forwarders`
        // feeds the transport pool. Setting only the second — which reads like
        // the obvious field, and is what its own doc comment claims — leaves the
        // resolver in iterative mode with an unused pool of transports, so every
        // query goes to a root server and the configured upstream never sees
        // traffic. `ForwarderSet::add` deduplicates by transport key, so the
        // duplicate declaration costs one comparison.
        dns: recurse_x::resolver::DnsPolicy {
            upstreams: recurse_x::resolver::UpstreamGroups {
                default: forwarders.clone(),
                ..Default::default()
            },
            ..Default::default()
        },
        engine: EngineConfig {
            forwarders,
            timeout_ms: RESOLVER_TIMEOUT_MS,
            query_budget_ms: QUERY_BUDGET_MS,
            // DNSSEC is requested and validated wherever a signature chain
            // exists. It is *not* anchored: RecurseX ships no root trust anchor,
            // and claiming `ChainAnchored` without one is exactly the
            // over-claim that flag is designed to prevent.
            dnssec: true,
            dnssec_anchored: false,
            ..Default::default()
        },
        rate_limit: RateLimitConfig {
            client_capacity: 512.0,
            client_refill_per_sec: 256.0,
            // One client exists; a table sized for a public server would only
            // be memory an attacker could never reach anyway.
            max_client_buckets: 8,
        },
        max_inflight: 256,
        // Sweeps, prefetch and alias pruning. Five seconds is a compromise: the
        // background refreshes that keep serve-stale answers fresh are only
        // useful if they run, and a 1 s tick on a phone is a wake-up the
        // resolver does not need to do its job for a BitTorrent client.
        maintenance_interval_ms: 5_000,
        max_concurrent_refreshes: 4,
        ..Default::default()
    }
}

// ---------------------------------------------------------------------------
// The memo
// ---------------------------------------------------------------------------

/// Which address families to ask for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IpFamily {
    /// Both, IPv4 first (the socket layer decides what it can send).
    Any,
    /// IPv4 only: skips the AAAA query entirely on networks without IPv6.
    V4Only,
}

/// The memo's answer for a name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Lookup {
    /// Addresses, still inside their TTL.
    Fresh(Vec<IpAddr>),
    /// A remembered "no such name" (NXDOMAIN or an empty answer).
    Negative,
    /// Nothing usable — either never seen or past its TTL.
    Expired,
}

/// One name's addresses and the moment they stop being an answer.
#[derive(Debug, Clone)]
struct MemoEntry {
    v4: Vec<Ipv4Addr>,
    v6: Vec<Ipv6Addr>,
    expires: Instant,
    negative: bool,
}

/// The engine-facing copy of the resolver's answers, bounded and LRU-ish.
///
/// Recency is tracked with an explicit order queue rather than a timestamp per
/// entry: the queue is the eviction order, and an entry that is read moves to
/// its back, which is what makes the bound behave like a cache instead of a
/// FIFO.
#[derive(Default)]
struct Memo {
    entries: HashMap<String, MemoEntry>,
    order: VecDeque<String>,
}

impl Memo {
    fn get(&mut self, key: &str, now: Instant, want_v6: bool) -> Lookup {
        enum Pending {
            Negative,
            Fresh(Vec<IpAddr>),
        }
        let pending = {
            let Some(entry) = self.entries.get(key) else {
                return Lookup::Expired;
            };
            if entry.expires <= now {
                return Lookup::Expired;
            }
            if entry.negative {
                Pending::Negative
            } else {
                let mut addrs: Vec<IpAddr> =
                    entry.v4.iter().copied().map(IpAddr::V4).collect();
                if want_v6 {
                    addrs.extend(entry.v6.iter().copied().map(IpAddr::V6));
                }
                if addrs.is_empty() {
                    return Lookup::Expired;
                }
                Pending::Fresh(addrs)
            }
        };
        self.touch(key);
        match pending {
            Pending::Negative => Lookup::Negative,
            Pending::Fresh(addrs) => Lookup::Fresh(addrs),
        }
    }

    fn put(&mut self, key: &str, v4: &[Ipv4Addr], v6: &[Ipv6Addr], ttl: Duration, now: Instant) {
        self.insert(
            key,
            MemoEntry {
                v4: v4.to_vec(),
                v6: v6.to_vec(),
                expires: now + ttl.clamp(MEMO_MIN_TTL, MEMO_MAX_TTL),
                negative: false,
            },
        );
    }

    fn put_negative(&mut self, key: &str, now: Instant) {
        self.insert(
            key,
            MemoEntry {
                v4: Vec::new(),
                v6: Vec::new(),
                expires: now + MEMO_NEGATIVE_TTL,
                negative: true,
            },
        );
    }

    fn insert(&mut self, key: &str, entry: MemoEntry) {
        self.entries.insert(key.to_string(), entry);
        self.touch(key);
        while self.entries.len() > MEMO_CAPACITY {
            let Some(oldest) = self.order.pop_front() else {
                break;
            };
            // An entry can be touched between the pop and the remove only by
            // this thread, so the key is present unless it was just re-inserted;
            // `remove` on a missing key is a no-op either way.
            self.entries.remove(&oldest);
        }
    }

    fn touch(&mut self, key: &str) {
        if let Some(pos) = self.order.iter().position(|k| k == key) {
            self.order.remove(pos);
        }
        self.order.push_back(key.to_string());
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.entries.len()
    }
}

// ---------------------------------------------------------------------------
// Single-flight
// ---------------------------------------------------------------------------

/// One in-flight lookup, waited on by every other caller of the same name.
#[derive(Default)]
struct InflightSlot {
    done: Mutex<bool>,
    cv: Condvar,
}

impl InflightSlot {
    fn finish(&self) {
        let mut done = self.done.lock().unwrap_or_else(|e| e.into_inner());
        *done = true;
        self.cv.notify_all();
    }

    /// Waits up to `max` for the leader, returning whether it finished.
    fn wait(&self, max: Duration) {
        let done = self.done.lock().unwrap_or_else(|e| e.into_inner());
        if *done {
            return;
        }
        let _ = self
            .cv
            .wait_timeout(done, max)
            .unwrap_or_else(|e| e.into_inner());
    }
}

/// Whether this call owns the lookup or is waiting on someone else's.
enum Role {
    Leader,
    Follower(Arc<InflightSlot>),
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/// Shared resolver state. Cheap to clone (one `Arc`), safe to use from the
/// engine thread and the resolver workers at the same time.
pub struct DnsService {
    inner: Arc<Inner>,
}

struct Inner {
    resolver: Resolver,
    /// The maintenance thread, joined on shutdown so an engine restart cannot
    /// leak one thread per start.
    maintenance: Mutex<Option<JoinHandle<()>>>,
    memo: Mutex<Memo>,
    inflight: Mutex<HashMap<String, Arc<InflightSlot>>>,
    /// Names with a warm-up in flight, so consecutive engine ticks share one.
    warming: Mutex<HashSet<String>>,
    warm_active: AtomicUsize,
    family: IpFamily,
    /// The upstreams in use, for the per-provider health readout.
    upstreams: Vec<Upstream>,
    /// True when the resolver forwards instead of walking from the root.
    forwarded: bool,
    /// Configuration that could not be used, in the user's own terms.
    problems: Vec<String>,
    queries: AtomicU64,
    cache_hits: AtomicU64,
    os_fallbacks: AtomicU64,
    provider_ok: AtomicU64,
    provider_failures: AtomicU64,
    dnssec_failures: AtomicU64,
    validated: AtomicU64,
}

/// One resolution outcome, with the provenance the log line and the counters
/// report.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// The memo.
    Cache,
    /// The resolver (forwarded upstream or iterative walk).
    Resolver,
    /// The operating system's resolver.
    OsResolver,
    /// A name that does not exist.
    Negative,
}

/// A resolution result plus where it came from.
#[derive(Debug, Clone)]
pub struct Resolved {
    pub addrs: Vec<NetAddr>,
    pub source: Source,
}

/// What one pass through the resolver produced.
enum Outcome {
    /// Addresses, with the TTL they may be memoised for.
    Addrs {
        v4: Vec<Ipv4Addr>,
        v6: Vec<Ipv6Addr>,
        ttl: Duration,
    },
    /// The name does not exist, or exists with no address of this kind.
    NotFound,
    /// DNSSEC said the answer is a lie.
    Bogus,
    /// The resolver could not answer; the OS resolver may still be able to.
    Failed,
}

impl DnsService {
    /// Builds a service under `policy`, over `family`.
    ///
    /// Encrypted upstreams are only used when trust anchors are available: a
    /// DoT/DoH forwarder with verification off is an unauthenticated server that
    /// can rewrite every answer, which is worse than the OS resolver it
    /// replaced. If the platform's anchors cannot be read the encrypted entries
    /// are dropped, reported, and the resolver walks from the root instead.
    pub fn new(policy: &NetworkPolicy, family: IpFamily) -> Self {
        let (forwarders, upstreams, mut problems) = build_upstreams(&policy.doh_providers);
        let wants_encryption = upstreams
            .iter()
            .any(|u| !matches!(u.endpoint.proto, Proto::Udp | Proto::Tcp));

        let (forwarders, upstreams) = if wants_encryption {
            match crate::tlsroots::anchors() {
                Ok(anchors) => {
                    let resolver = Resolver::new(resolver_config(forwarders));
                    resolver.set_forwarder_roots(anchors.roots.clone(), true);
                    let maintenance = resolver.spawn_maintenance();
                    return DnsService::wrap(
                        resolver,
                        maintenance,
                        upstreams,
                        true,
                        problems,
                        family,
                    );
                }
                Err(why) => {
                    problems.push(format!(
                        "加密上游已停用（{why}）：改用从根开始的迭代解析"
                    ));
                    let kept: Vec<Upstream> = upstreams
                        .iter()
                        .filter(|u| matches!(u.endpoint.proto, Proto::Udp | Proto::Tcp))
                        .cloned()
                        .collect();
                    let kept_forwarders: Vec<Forwarder> = forwarders
                        .iter()
                        .filter(|f| matches!(f.endpoint.proto, Proto::Udp | Proto::Tcp))
                        .cloned()
                        .collect();
                    (kept_forwarders, kept)
                }
            }
        } else {
            (forwarders, upstreams)
        };

        let forwarded = !forwarders.is_empty();
        let resolver = Resolver::new(resolver_config(forwarders));
        let maintenance = resolver.spawn_maintenance();
        DnsService::wrap(resolver, maintenance, upstreams, forwarded, problems, family)
    }

    fn wrap(
        resolver: Resolver,
        maintenance: JoinHandle<()>,
        upstreams: Vec<Upstream>,
        forwarded: bool,
        problems: Vec<String>,
        family: IpFamily,
    ) -> Self {
        DnsService {
            inner: Arc::new(Inner {
                resolver,
                maintenance: Mutex::new(Some(maintenance)),
                memo: Mutex::new(Memo::default()),
                inflight: Mutex::new(HashMap::new()),
                warming: Mutex::new(HashSet::new()),
                warm_active: AtomicUsize::new(0),
                family,
                upstreams,
                forwarded,
                problems,
                queries: AtomicU64::new(0),
                cache_hits: AtomicU64::new(0),
                os_fallbacks: AtomicU64::new(0),
                provider_ok: AtomicU64::new(0),
                provider_failures: AtomicU64::new(0),
                dnssec_failures: AtomicU64::new(0),
                validated: AtomicU64::new(0),
            }),
        }
    }

    /// True when the resolver forwards to configured upstreams rather than
    /// walking from the root.
    pub fn forwarded(&self) -> bool {
        self.inner.forwarded
    }

    /// Configuration that could not be applied, in the user's own terms.
    pub fn problems(&self) -> &[String] {
        &self.inner.problems
    }

    pub fn stats(&self) -> DnsStats {
        let snap = self.inner.resolver.stats_snapshot();
        let failures_seen = self.inner.provider_failures.load(Ordering::Relaxed);
        let shared = self.inner.resolver.shared();
        let selector = shared.selector.lock();
        let providers = self
            .inner
            .upstreams
            .iter()
            .map(|u| {
                let path = selector.path(&u.endpoint);
                ProviderHealth {
                    spec: u.spec.clone(),
                    // "Up" means it answered at least once, or nothing has
                    // failed yet. A forwarder's transport error is not recorded
                    // in the resolver's path model — only timeouts and SERVFAILs
                    // are — so a never-answered endpoint with a failure elsewhere
                    // is reported down rather than optimistically up. The
                    // inverse mistake (claiming a dead upstream is healthy) is
                    // the one a user would act on wrongly.
                    up: path
                        .map(|p| {
                            p.successes > 0
                                || (p.timeouts == 0
                                    && p.servfails == 0
                                    && p.failures == 0
                                    && failures_seen == 0)
                        })
                        .unwrap_or(failures_seen == 0),
                    rtt_ms: path.map(|p| p.rtt_ewma_ms.round() as u64).unwrap_or(0),
                    successes: path.map(|p| p.successes).unwrap_or(0),
                    timeouts: path.map(|p| p.timeouts).unwrap_or(0),
                }
            })
            .collect();
        DnsStats {
            forwarded: self.inner.forwarded,
            queries: self.inner.queries.load(Ordering::Relaxed),
            cache_hits: self.inner.cache_hits.load(Ordering::Relaxed),
            os_fallbacks: self.inner.os_fallbacks.load(Ordering::Relaxed),
            provider_ok: self.inner.provider_ok.load(Ordering::Relaxed),
            provider_failures: self.inner.provider_failures.load(Ordering::Relaxed),
            dnssec_failures: self.inner.dnssec_failures.load(Ordering::Relaxed),
            validated: self.inner.validated.load(Ordering::Relaxed),
            upstream_queries: snap.upstream_queries,
            upstream_timeouts: snap.upstream_timeouts,
            servfails: snap.servfails,
            nxdomain: snap.nxdomain,
            nodata: snap.nodata,
            rate_limited: snap.rate_limited,
            stale: snap.served_stale,
            resolver_cache_hits: snap.cache_hits,
            resolver_cache_misses: snap.cache_misses,
            avg_resolve_us: snap.avg_resolve_us,
            providers,
            problems: self.inner.problems.clone(),
        }
    }

    /// Cached answer only — never blocks, never opens a socket.
    ///
    /// This is what the synchronous `Host` hooks and the address guard use on
    /// threads that must not wait: a resolve that takes a second must not stall
    /// the tick loop or a tracker announce. A negative entry reports `None`
    /// because that is exactly what the caller then does with it.
    pub fn cached(&self, host: &str, port: u16, now: Instant) -> Option<Vec<NetAddr>> {
        let want_v6 = self.inner.family == IpFamily::Any;
        let mut memo = self.memo();
        match memo.get(&host.to_ascii_lowercase(), now, want_v6) {
            Lookup::Fresh(addrs) if !addrs.is_empty() => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                Some(addrs.into_iter().map(|a| with_port(a, port)).collect())
            }
            _ => None,
        }
    }

    /// Resolves `host` from the memo or the OS resolver, never from the
    /// resolver — but starts a background warm-up on a miss.
    ///
    /// Call this from the engine thread: it cannot block on the network. That
    /// ordering matters, because a resolver query on the engine thread would
    /// stall the tick loop for as long as an upstream takes to time out. The
    /// *next* lookup is the one that gets the resolver's answer.
    pub fn resolve_blocking_os(&self, host: &str, port: u16, now: Instant) -> Resolved {
        let key = host.to_ascii_lowercase();
        let want_v6 = self.inner.family == IpFamily::Any;
        let cached = {
            let mut memo = self.memo();
            memo.get(&key, now, want_v6)
        };
        match cached {
            Lookup::Fresh(addrs) => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                Resolved {
                    addrs: addrs.into_iter().map(|a| with_port(a, port)).collect(),
                    source: Source::Cache,
                }
            }
            Lookup::Negative => {
                self.inner.cache_hits.fetch_add(1, Ordering::Relaxed);
                Resolved {
                    addrs: Vec::new(),
                    source: Source::Negative,
                }
            }
            Lookup::Expired => self.os_fallback(&key, port, now),
        }
    }

    /// Full resolution: memo, then the resolver, then the OS resolver.
    ///
    /// Call this from a resolver worker, never from the engine thread.
    pub fn resolve(&self, host: &str, port: u16, now: Instant) -> Resolved {
        let key = host.to_ascii_lowercase();
        let want_v6 = self.inner.family == IpFamily::Any;
        let cached = {
            let mut memo = self.memo();
            memo.get(&key, now, want_v6)
        };
        match cached {
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
        self.inner.queries.fetch_add(1, Ordering::Relaxed);

        let mut leader = true;
        if let Role::Follower(slot) = self.enter(&key) {
            leader = false;
            // Another caller is already resolving this name. Waiting is cheaper
            // than a second query, and the wait is bounded so a wedged leader
            // cannot wedge us. A follower that gives up must *not* release the
            // slot: the leader still owns it, and waking the other waiters early
            // would turn one query into three.
            slot.wait(SINGLE_FLIGHT_WAIT);
            let cached = {
                let mut memo = self.memo();
                memo.get(&key, Instant::now(), want_v6)
            };
            match cached {
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
        }

        let resolved = self.resolve_uncached(&key, port);
        if leader {
            self.leave(&key);
        }
        resolved
    }

    /// Warms the memo for `host` on a background thread.
    ///
    /// Used by the synchronous hooks: they must not block, but the next lookup
    /// should be authoritative even when the OS resolver just lied.
    pub fn refresh_async(&self, host: &str) {
        let key = host.to_ascii_lowercase();
        {
            let mut warming = self.inner.warming.lock().unwrap_or_else(|e| e.into_inner());
            if !warming.insert(key.clone()) {
                return;
            }
        }
        if self.inner.warm_active.load(Ordering::Relaxed) >= MAX_WARM_ACTIVE {
            // The bound is the point: a censored network fails every lookup, and
            // the memo TTL is what brings us back rather than an unbounded pile
            // of threads.
            self.inner
                .warming
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&key);
            return;
        }
        self.inner.warm_active.fetch_add(1, Ordering::Relaxed);
        let svc = self.clone();
        let thread_key = key.clone();
        let spawned = std::thread::Builder::new()
            .name("typebit-dns-warm".to_string())
            .spawn(move || {
                if let Outcome::Addrs { v4, v6, ttl } = svc.via_resolver(&thread_key) {
                    svc.put_memo(&thread_key, &v4, &v6, ttl, Instant::now());
                }
                svc.inner
                    .warming
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&thread_key);
                svc.inner.warm_active.fetch_sub(1, Ordering::Relaxed);
            });
        if spawned.is_err() {
            self.inner
                .warming
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&key);
            self.inner.warm_active.fetch_sub(1, Ordering::Relaxed);
        }
    }

    /// Asks background work to stop and joins the maintenance thread.
    ///
    /// Called from engine teardown; idempotent.
    pub fn shutdown(&self) {
        self.inner.resolver.shutdown();
        let handle = self
            .inner
            .maintenance
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        if let Some(handle) = handle {
            let _ = handle.join();
        }
    }

    fn memo(&self) -> std::sync::MutexGuard<'_, Memo> {
        self.inner
            .memo
            .lock()
            .unwrap_or_else(|e| e.into_inner())
    }

    /// Registers a lookup, returning the caller's role.
    fn enter(&self, key: &str) -> Role {
        let mut inflight = self
            .inner
            .inflight
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(slot) = inflight.get(key) {
            return Role::Follower(slot.clone());
        }
        inflight.insert(key.to_string(), Arc::new(InflightSlot::default()));
        Role::Leader
    }

    /// Releases a leader's slot and wakes its waiters.
    fn leave(&self, key: &str) {
        let slot = self
            .inner
            .inflight
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(key);
        if let Some(slot) = slot {
            slot.finish();
        }
    }

    /// The resolver, then the OS resolver for the cases it cannot cover.
    fn resolve_uncached(&self, key: &str, port: u16) -> Resolved {
        let now = Instant::now();
        match self.via_resolver(key) {
            Outcome::Addrs { v4, v6, ttl } => {
                self.inner.provider_ok.fetch_add(1, Ordering::Relaxed);
                self.put_memo(key, &v4, &v6, ttl, now);
                Resolved {
                    addrs: to_netaddrs(&v4, &v6, port),
                    source: Source::Resolver,
                }
            }
            Outcome::NotFound => {
                self.inner.provider_ok.fetch_add(1, Ordering::Relaxed);
                self.put_negative(key, now);
                Resolved {
                    addrs: Vec::new(),
                    source: Source::Negative,
                }
            }
            Outcome::Bogus => {
                // A signature that does not verify is a poisoning signal, not a
                // transient failure: falling back to the resolver that would
                // return the same lie is the one response that cannot be right.
                self.inner.dnssec_failures.fetch_add(1, Ordering::Relaxed);
                self.put_negative(key, now);
                Resolved {
                    addrs: Vec::new(),
                    source: Source::Negative,
                }
            }
            Outcome::Failed => self.os_fallback(key, port, now),
        }
    }

    /// The OS resolver, memoised briefly, with a warm-up kicked off behind it.
    fn os_fallback(&self, key: &str, port: u16, now: Instant) -> Resolved {
        self.inner.os_fallbacks.fetch_add(1, Ordering::Relaxed);
        let addrs = os_resolve(key, port, self.inner.family);
        if addrs.is_empty() {
            // "No such name" from the OS resolver is exactly the case the
            // resolver exists to correct, so the failure is remembered briefly
            // and the name is warmed in the background.
            self.put_negative(key, now);
            self.refresh_async(key);
            return Resolved {
                addrs,
                source: Source::Negative,
            };
        }
        let (v4, v6) = split_families(&addrs);
        self.put_memo(key, &v4, &v6, MEMO_OS_TTL, now);
        self.refresh_async(key);
        Resolved {
            addrs,
            source: Source::OsResolver,
        }
    }

    /// One pass through RecurseX: A, plus AAAA when the host has a v6 stack.
    fn via_resolver(&self, host: &str) -> Outcome {
        let name = match Name::from_ascii(host) {
            Ok(name) => name,
            // Not a DNS name in ASCII form (an IDN in the user's own script, an
            // IP literal, junk). The OS resolver accepts names this parser does
            // not, and is the right fallback for them.
            Err(_) => return Outcome::Failed,
        };
        let types: &[RrType] = match self.inner.family {
            IpFamily::Any => &[RrType::A, RrType::AAAA],
            IpFamily::V4Only => &[RrType::A],
        };
        let mut v4: Vec<Ipv4Addr> = Vec::new();
        let mut v6: Vec<Ipv6Addr> = Vec::new();
        let mut ttl = Duration::ZERO;
        let mut answered = 0usize;
        let mut missing = 0usize;
        let mut transient_failure = false;

        for rr_type in types {
            match self.inner.resolver.resolve(&name, *rr_type) {
                Ok(res) => {
                    answered += 1;
                    if res.validated {
                        self.inner.validated.fetch_add(1, Ordering::Relaxed);
                    }
                    let mut records = 0usize;
                    for record in &res.answers {
                        match &record.rdata {
                            RData::A(ip) => {
                                if !v4.contains(ip) {
                                    v4.push(*ip);
                                }
                                records += 1;
                            }
                            RData::Aaaa(ip) => {
                                if !v6.contains(ip) {
                                    v6.push(*ip);
                                }
                                records += 1;
                            }
                            _ => {}
                        }
                    }
                    if matches!(res.rcode, Rcode::NXDOMAIN) || records == 0 {
                        missing += 1;
                    }
                    // The shortest TTL in the chain is the only safe one to
                    // memoise for: keeping an answer longer than its own record
                    // allows is how a client ends up dialling a moved address.
                    let res_ttl = Duration::from_secs(res.ttl as u64);
                    if records > 0 && (ttl.is_zero() || res_ttl < ttl) {
                        ttl = res_ttl;
                    }
                }
                Err(err) => match err.kind() {
                    ErrorKind::NxDomain | ErrorKind::NoData => missing += 1,
                    ErrorKind::Dnssec => return Outcome::Bogus,
                    _ => transient_failure = true,
                },
            }
        }

        if !v4.is_empty() || !v6.is_empty() {
            return Outcome::Addrs { v4, v6, ttl };
        }
        if answered + missing > 0 && !transient_failure {
            return Outcome::NotFound;
        }
        self.inner
            .provider_failures
            .fetch_add(1, Ordering::Relaxed);
        Outcome::Failed
    }

    fn put_memo(&self, key: &str, v4: &[Ipv4Addr], v6: &[Ipv6Addr], ttl: Duration, now: Instant) {
        self.memo().put(key, v4, v6, ttl, now);
    }

    fn put_negative(&self, key: &str, now: Instant) {
        self.memo().put_negative(key, now);
    }
}

impl Clone for DnsService {
    fn clone(&self) -> Self {
        DnsService {
            inner: self.inner.clone(),
        }
    }
}

/// Stops the maintenance thread even if teardown forgot to.
///
/// The thread holds a clone of the resolver, so without this an engine restart
/// would leave one behind — on Android, one per rotation.
impl Drop for Inner {
    fn drop(&mut self) {
        self.resolver.shutdown();
        let handle = self
            .maintenance
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        if let Some(handle) = handle {
            let _ = handle.join();
        }
    }
}

#[cfg(test)]
impl DnsService {
    /// Fills the memo directly.
    ///
    /// A test that cares about *what happens to an answer* (a hostile `.torrent`
    /// naming a name that points at loopback, a warm-up that must not block the
    /// engine thread) should not have to stand up a DNS server to get one; this
    /// is the seam that keeps those tests deterministic.
    pub fn prime_for_test(&self, host: &str, ip: Ipv4Addr) {
        self.memo().put(
            &host.to_ascii_lowercase(),
            &[ip],
            &[],
            MEMO_MAX_TTL,
            Instant::now(),
        );
    }

    /// The number of memoised names, for eviction tests.
    pub fn memo_len_for_test(&self) -> usize {
        self.memo().len()
    }

    /// A service over an explicit resolver configuration.
    ///
    /// The end-to-end tests need an upstream on loopback and a budget measured
    /// in milliseconds; both are configuration, so the test seam is a
    /// configuration constructor rather than a mock resolver. The upstreams it
    /// reports are the ones it dials, so a health assertion tests the path the
    /// query actually took.
    pub fn from_config_for_test(config: ResolverConfig, family: IpFamily) -> Self {
        let upstreams = config
            .dns
            .upstreams
            .default
            .iter()
            .map(|f| Upstream {
                spec: f.endpoint.addr_str(),
                endpoint: f.endpoint,
            })
            .collect::<Vec<_>>();
        let forwarded = !upstreams.is_empty();
        let resolver = Resolver::new(config);
        let maintenance = resolver.spawn_maintenance();
        DnsService::wrap(resolver, maintenance, upstreams, forwarded, Vec::new(), family)
    }
}

/// Reported by [`DnsService::stats`].
#[derive(Debug, Clone)]
pub struct DnsStats {
    /// True when queries go to configured upstreams, false when the resolver
    /// walks from the root itself.
    pub forwarded: bool,
    pub queries: u64,
    pub cache_hits: u64,
    pub os_fallbacks: u64,
    pub provider_ok: u64,
    pub provider_failures: u64,
    pub dnssec_failures: u64,
    pub validated: u64,
    pub upstream_queries: u64,
    pub upstream_timeouts: u64,
    pub servfails: u64,
    pub nxdomain: u64,
    pub nodata: u64,
    pub rate_limited: u64,
    pub stale: u64,
    pub resolver_cache_hits: u64,
    pub resolver_cache_misses: u64,
    pub avg_resolve_us: u64,
    pub providers: Vec<ProviderHealth>,
    pub problems: Vec<String>,
}

/// One upstream's measured health, read from the resolver's path model rather
/// than from a breaker of our own.
#[derive(Debug, Clone)]
pub struct ProviderHealth {
    pub spec: String,
    pub up: bool,
    pub rtt_ms: u64,
    pub successes: u64,
    pub timeouts: u64,
}

impl DnsStats {
    /// One log line, in the shape the stats dialog shows.
    pub fn summary(&self) -> String {
        let mode = if self.forwarded {
            let healthy = self.providers.iter().filter(|p| p.up).count();
            format!("转发 {} / {} 可用", healthy, self.providers.len())
        } else {
            "从根迭代".to_string()
        };
        format!(
            "dns: {mode} · 查询 {} · 缓存命中 {} · 系统回退 {} · 解析成功 {} / 失败 {} · 权威查询 {} · 超时 {} · DNSSEC 校验 {}",
            self.queries,
            self.cache_hits,
            self.os_fallbacks,
            self.provider_ok,
            self.provider_failures,
            self.upstream_queries,
            self.upstream_timeouts,
            self.validated
        )
    }
}

/// Default upstream ladder.
///
/// Addressed by IP with the TLS name in the fragment, which is the form a
/// forwarder needs; the providers are chosen so that the second is reachable
/// where the first is not (a mainland-China path, a global anycast path, and a
/// third that answers where both are throttled). The list is user-overridable —
/// a user behind a corporate resolver, or one who does not want any third party
/// in the path, can empty it and get a resolver that walks from the root.
pub const DEFAULT_DOH_PROVIDERS: &[&str] = &[
    "https://1.1.1.1/dns-query#cloudflare-dns.com",
    "https://223.5.5.5/dns-query#dns.alidns.com",
    "https://1.12.12.12/dns-query#doh.pub",
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
// Address helpers
// ---------------------------------------------------------------------------

/// The OS resolver, used as the fallback. Filtered to the requested family so a
/// v6 address never lands on a v4-only socket.
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

/// Applies `port` to a memoised address (the memo stores addresses, not ports).
fn with_port(addr: IpAddr, port: u16) -> NetAddr {
    match addr {
        IpAddr::V4(ip) => NetAddr::V4(ip.octets(), port),
        IpAddr::V6(ip) => NetAddr::V6(ip.octets(), port),
    }
}

/// Both families, in order, as endpoints for the engine.
fn to_netaddrs(v4: &[Ipv4Addr], v6: &[Ipv6Addr], port: u16) -> Vec<NetAddr> {
    let mut out: Vec<NetAddr> = Vec::with_capacity(v4.len() + v6.len());
    out.extend(v4.iter().map(|ip| NetAddr::V4(ip.octets(), port)));
    out.extend(v6.iter().map(|ip| NetAddr::V6(ip.octets(), port)));
    out
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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::netpolicy::NetworkPolicy;

    fn policy_with(specs: &[&str]) -> NetworkPolicy {
        NetworkPolicy {
            doh_providers: specs.iter().map(|s| s.to_string()).collect(),
            ..Default::default()
        }
    }

    fn service(policy: &NetworkPolicy) -> DnsService {
        DnsService::new(policy, IpFamily::V4Only)
    }

    // -- upstream parsing ---------------------------------------------------

    #[test]
    fn a_hostname_doh_url_becomes_an_addressed_doh_forwarder() {
        let parsed = parse_upstream("https://cloudflare-dns.com/dns-query").expect("parses");
        let f = parsed.forwarder;
        assert_eq!(f.endpoint.proto, Proto::DoH);
        assert_eq!(f.endpoint.port, 443);
        assert_eq!(f.endpoint.ip.to_string(), "1.1.1.1");
        assert_eq!(f.host.as_deref(), Some("cloudflare-dns.com"));
        assert_eq!(f.doh_path(), "/dns-query");
        assert_eq!(parsed.upstream.spec, "https://cloudflare-dns.com/dns-query");
    }

    #[test]
    fn an_addressed_dot_upstream_keeps_its_identity() {
        let parsed = parse_upstream("tls://1.1.1.1#one.one.one.one").expect("parses");
        assert_eq!(parsed.forwarder.endpoint.proto, Proto::Tls);
        assert_eq!(parsed.forwarder.endpoint.port, 853);
        assert_eq!(parsed.forwarder.host.as_deref(), Some("one.one.one.one"));
        assert!(parsed.notes.is_empty());
    }

    #[test]
    fn a_bare_address_is_plain_dns() {
        let parsed = parse_upstream("223.5.5.5").expect("parses");
        assert_eq!(parsed.forwarder.endpoint.proto, Proto::Udp);
        assert_eq!(parsed.forwarder.endpoint.port, 53);
        assert_eq!(parsed.forwarder.host, None);
    }

    #[test]
    fn an_explicit_port_and_ipv6_literal_are_honoured() {
        let parsed = parse_upstream("https://[2606:4700:4700::1111]:8443/dns-query#cloudflare-dns.com")
            .expect("parses");
        assert_eq!(parsed.forwarder.endpoint.port, 8443);
        assert_eq!(parsed.forwarder.endpoint.ip.to_string(), "2606:4700:4700::1111");
    }

    #[test]
    fn encrypted_upstreams_require_a_tls_name() {
        let why = parse_upstream("tls://1.1.1.1").expect_err("must be rejected");
        assert!(why.contains("#dns.example"), "{why}");
    }

    #[test]
    fn quic_upstreams_are_reported_not_silently_downgraded() {
        let why = parse_upstream("quic://dns.adguard-dns.com#dns.adguard-dns.com")
            .expect_err("must be rejected");
        assert!(why.contains("QUIC"), "{why}");
    }

    #[test]
    fn nonsense_is_reported_with_its_own_text() {
        let why = parse_upstream("ftp://1.1.1.1").expect_err("must be rejected");
        assert!(why.contains("ftp://"), "{why}");
        let why = parse_upstream("").expect_err("must be rejected");
        assert!(why.contains("空"), "{why}");
        let why =
            parse_upstream("https://1.1.1.1/dns-query").expect_err("no identity for DoH");
        assert!(why.contains("TLS 名称"), "{why}");
        let why = parse_upstream("tls://1.1.1.1/foo#name").expect_err("path on a non-DoH upstream");
        assert!(why.contains("路径"), "{why}");
    }

    #[test]
    fn a_plain_upstream_drops_a_tls_name_with_a_note() {
        let parsed = parse_upstream("udp://1.1.1.1#ignored").expect("parses");
        assert_eq!(parsed.forwarder.host, None);
        assert_eq!(parsed.notes.len(), 1);
        assert!(parsed.notes[0].contains("已忽略"), "{}", parsed.notes[0]);
    }

    // -- configuration ------------------------------------------------------

    #[test]
    fn the_default_ladder_is_usable_without_the_os_resolver() {
        // Every default entry must be an IP-literal upstream: resolving a
        // resolver's name through the OS resolver is the failure this feature
        // exists to survive, so none of them may depend on it.
        for spec in DEFAULT_DOH_PROVIDERS {
            let parsed = parse_upstream(spec).unwrap_or_else(|e| panic!("{spec}: {e}"));
            assert!(
                matches!(parsed.forwarder.endpoint.proto, Proto::DoH | Proto::Tls),
                "{spec}"
            );
            assert!(parsed.forwarder.host.is_some(), "{spec}");
            let authority = spec
                .split_once("://")
                .map(|(_, rest)| rest)
                .unwrap_or(spec)
                .split('/')
                .next()
                .unwrap_or(spec)
                .split('#')
                .next()
                .unwrap_or(spec);
            assert!(
                authority.parse::<std::net::IpAddr>().is_ok(),
                "{spec} must address an IP"
            );
        }
    }

    #[test]
    fn an_empty_list_means_iterative_resolution() {
        let svc = service(&policy_with(&[]));
        assert!(!svc.forwarded());
        assert!(svc.stats().providers.is_empty());
        svc.shutdown();
    }

    #[test]
    fn a_plain_upstream_keeps_the_service_in_forwarded_mode() {
        let svc = service(&policy_with(&["udp://223.5.5.5"]));
        assert!(svc.forwarded());
        let stats = svc.stats();
        assert_eq!(stats.providers.len(), 1);
        assert_eq!(stats.providers[0].spec, "udp://223.5.5.5");
        // Never dialled yet is unknown, which is not the same as down.
        assert!(stats.providers[0].up);
        svc.shutdown();
    }

    #[test]
    fn unusable_upstreams_are_reported_not_ignored() {
        let svc = service(&policy_with(&["ftp://1.1.1.1", "udp://223.5.5.5"]));
        let stats = svc.stats();
        assert_eq!(stats.providers.len(), 1);
        assert_eq!(stats.problems.len(), 1);
        assert!(stats.problems[0].contains("ftp://"), "{}", stats.problems[0]);
        svc.shutdown();
    }

    /// The configuration the resolver is actually built from, checked for the
    /// bounds this client depends on: a phone-sized cache, a rate limit that
    /// cannot punish our own traffic, and DNSSEC requested but not over-claimed.
    #[test]
    fn the_resolver_configuration_is_sized_for_a_client() {
        let cfg = resolver_config(Vec::new());
        assert!(cfg.cache.hot_capacity + cfg.cache.warm_capacity + cfg.cache.cold_capacity < 4_096);
        assert_eq!(cfg.cache.negative_ttl_cap, 120);
        assert_eq!(cfg.cache.max_ttl_cap, 7_200);
        assert!(cfg.rate_limit.client_capacity >= 128.0);
        assert!(cfg.engine.dnssec);
        assert!(!cfg.engine.dnssec_anchored, "no root anchor is shipped");
        assert!(cfg.engine.qname_minimization);
        assert!(cfg.engine.use_0x20);
        assert!(cfg.engine.query_budget_ms >= 2 * cfg.engine.timeout_ms);
        assert!(cfg.maintenance_interval_ms >= 1_000);
    }

    // -- the memo -----------------------------------------------------------

    #[test]
    fn the_memo_expires_negative_and_is_bounded() {
        let now = Instant::now();
        let mut memo = Memo::default();
        memo.put("a.example", &[Ipv4Addr::new(1, 2, 3, 4)], &[], Duration::from_secs(60), now);
        assert_eq!(
            memo.get("a.example", now, false),
            Lookup::Fresh(vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))])
        );
        assert_eq!(
            memo.get("a.example", now + Duration::from_secs(61), false),
            Lookup::Expired
        );

        memo.put_negative("gone.example", now);
        assert_eq!(memo.get("gone.example", now, true), Lookup::Negative);
        assert_eq!(
            memo.get("gone.example", now + MEMO_NEGATIVE_TTL + Duration::from_secs(1), true),
            Lookup::Expired
        );

        for i in 0..(MEMO_CAPACITY + 32) {
            memo.put(
                &format!("h{i}.example"),
                &[Ipv4Addr::new(10, 0, (i >> 8) as u8, i as u8)],
                &[],
                Duration::from_secs(60),
                now,
            );
        }
        assert_eq!(memo.len(), MEMO_CAPACITY);
        // The oldest entries are the ones evicted: the queue is the order.
        assert_eq!(memo.get("h0.example", now, false), Lookup::Expired);
        assert!(matches!(
            memo.get(&format!("h{}.example", MEMO_CAPACITY + 31), now, false),
            Lookup::Fresh(_)
        ));
    }

    #[test]
    fn a_v4_only_host_never_sees_a_v6_address() {
        let now = Instant::now();
        let mut memo = Memo::default();
        memo.put(
            "dual.example",
            &[Ipv4Addr::new(1, 1, 1, 1)],
            &["2606:4700::1111".parse().unwrap()],
            Duration::from_secs(60),
            now,
        );
        assert_eq!(
            memo.get("dual.example", now, false),
            Lookup::Fresh(vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1))])
        );
        assert_eq!(fresh(memo.get("dual.example", now, true)).len(), 2);
    }

    /// The TTL clamps are what keep a record with a 1 s TTL from turning into a
    /// query per announce, and a record with a week-long TTL from pinning an
    /// address for a week.
    #[test]
    fn memoised_ttls_are_clamped_at_both_ends() {
        let now = Instant::now();
        let mut memo = Memo::default();
        memo.put("short.example", &[Ipv4Addr::LOCALHOST], &[], Duration::from_secs(1), now);
        assert!(matches!(
            memo.get("short.example", now + MEMO_MIN_TTL - Duration::from_secs(1), false),
            Lookup::Fresh(_)
        ));
        assert_eq!(
            memo.get("short.example", now + MEMO_MIN_TTL + Duration::from_secs(1), false),
            Lookup::Expired
        );

        let mut memo = Memo::default();
        memo.put("long.example", &[Ipv4Addr::LOCALHOST], &[], Duration::from_secs(86_400), now);
        assert!(matches!(
            memo.get("long.example", now + MEMO_MAX_TTL - Duration::from_secs(1), false),
            Lookup::Fresh(_)
        ));
        assert_eq!(
            memo.get("long.example", now + MEMO_MAX_TTL + Duration::from_secs(1), false),
            Lookup::Expired
        );
    }

    // -- the service edge ---------------------------------------------------
    #[test]
    fn the_cache_only_lookup_never_blocks_and_respects_the_memo() {
        let svc = service(&policy_with(&["udp://223.5.5.5"]));
        let now = Instant::now();
        assert!(svc.cached("peer.example", 6881, now).is_none());

        svc.prime_for_test("peer.example", Ipv4Addr::new(203, 0, 113, 5));
        let addrs = svc.cached("peer.example", 6881, now).expect("memoised");
        assert_eq!(addrs, vec![NetAddr::V4([203, 0, 113, 5], 6881)]);
        // The port is the caller's, not the resolver's: the memo stores
        // addresses, and two callers want different ports.
        assert_eq!(
            svc.cached("peer.example", 80, now).expect("memoised"),
            vec![NetAddr::V4([203, 0, 113, 5], 80)]
        );
        assert_eq!(svc.stats().cache_hits, 2);
        svc.shutdown();
    }

    #[test]
    fn the_synchronous_hook_answers_from_the_memo_without_the_network() {
        let svc = service(&policy_with(&["udp://223.5.5.5"]));
        svc.prime_for_test("tracker.example", Ipv4Addr::new(198, 51, 100, 7));
        let resolved = svc.resolve_blocking_os("tracker.example", 6969, Instant::now());
        assert_eq!(resolved.source, Source::Cache);
        assert_eq!(resolved.addrs, vec![NetAddr::V4([198, 51, 100, 7], 6969)]);
        svc.shutdown();
    }

    /// A negative memo entry must answer *without* consulting anything: that is
    /// what stops a per-tick re-resolve of a dead tracker host.
    #[test]
    fn a_negative_entry_short_circuits_the_synchronous_hook() {
        let svc = service(&policy_with(&["udp://223.5.5.5"]));
        svc.put_negative("dead.example", Instant::now());
        let resolved = svc.resolve_blocking_os("dead.example", 443, Instant::now());
        assert_eq!(resolved.source, Source::Negative);
        assert!(resolved.addrs.is_empty());
        assert_eq!(svc.stats().os_fallbacks, 0);
        svc.shutdown();
    }

    // -- end to end against a real upstream ----------------------------------
    //
    // The resolver is a dependency, not code under test *here* — but the thing
    // this file is responsible for is the wiring: that a configured upstream is
    // reachable, that an answer is mapped to the right address, that the TTL
    // and the negative path survive, and that a failure falls through to the OS
    // resolver. A fake upstream over a loopback UDP socket is what proves that
    // without a network.

    /// A stub authoritative server: answers every A query with `answer`, or
    /// NXDOMAIN when `answer` is `None`. Counts queries so the single-flight
    /// test can see them.
    struct FakeUpstream {
        port: u16,
        queries: Arc<AtomicUsize>,
        stop: Arc<std::sync::atomic::AtomicBool>,
        thread: Option<JoinHandle<()>>,
    }

    /// One of the reserved documentation addresses, so a failure names itself.
    const FAKE_IP: [u8; 4] = [203, 0, 113, 9];

    impl FakeUpstream {
        fn start(answer: Option<[u8; 4]>) -> Self {
            let socket = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind stub");
            socket
                .set_read_timeout(Some(Duration::from_millis(50)))
                .expect("read timeout");
            let port = socket.local_addr().expect("addr").port();
            let queries = Arc::new(AtomicUsize::new(0));
            let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let thread = {
                let queries = queries.clone();
                let stop = stop.clone();
                std::thread::spawn(move || {
                    let mut buf = [0u8; 512];
                    while !stop.load(Ordering::Relaxed) {
                        let Ok((n, from)) = socket.recv_from(&mut buf) else {
                            continue;
                        };
                        queries.fetch_add(1, Ordering::Relaxed);
                        if let Some(reply) = dns_reply(&buf[..n], answer) {
                            let _ = socket.send_to(&reply, from);
                        }
                    }
                })
            };
            FakeUpstream {
                port,
                queries,
                stop,
                thread: Some(thread),
            }
        }

        fn queries(&self) -> usize {
            self.queries.load(Ordering::Relaxed)
        }
    }

    impl Drop for FakeUpstream {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            if let Some(t) = self.thread.take() {
                let _ = t.join();
            }
        }
    }

    /// Builds a minimal DNS reply for one query: same id, one question, and
    /// either an A record or NXDOMAIN. Only A is answered; an AAAA query gets a
    /// NOERROR with no records, which is what a NODATA answer looks like.
    fn dns_reply(query: &[u8], answer: Option<[u8; 4]>) -> Option<Vec<u8>> {
        if query.len() < 12 {
            return None;
        }
        // Walk the question: labels until the root, then qtype/qclass.
        let mut pos = 12;
        while pos < query.len() && query[pos] != 0 {
            pos += 1 + query[pos] as usize;
        }
        if pos + 5 > query.len() {
            return None;
        }
        let question_end = pos + 5;
        let qtype = u16::from_be_bytes([query[pos + 1], query[pos + 2]]);
        let is_a = qtype == 1;

        let mut out = Vec::with_capacity(question_end + 16);
        out.extend_from_slice(&query[0..2]);
        // QR=1, RD=1, RA=1, rcode = NXDOMAIN only when the stub is asked to.
        let rcode: u8 = if answer.is_none() { 3 } else { 0 };
        out.extend_from_slice(&(0x8180u16 | rcode as u16).to_be_bytes());
        out.extend_from_slice(&1u16.to_be_bytes());
        let answers: u16 = if answer.is_some() && is_a { 1 } else { 0 };
        out.extend_from_slice(&answers.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes());
        out.extend_from_slice(&query[12..question_end]);
        if answers == 1 {
            let ip = answer?;
            // A pointer to the question's name, then the record itself.
            out.extend_from_slice(&[0xC0, 0x0C]);
            out.extend_from_slice(&1u16.to_be_bytes());
            out.extend_from_slice(&1u16.to_be_bytes());
            out.extend_from_slice(&60u32.to_be_bytes());
            out.extend_from_slice(&4u16.to_be_bytes());
            out.extend_from_slice(&ip);
        }
        Some(out)
    }

    /// A service whose upstream is the stub, with a budget short enough that a
    /// test cannot hang on a lost datagram.
    fn service_over_stub(stub: &FakeUpstream, family: IpFamily) -> DnsService {
        let forwarder = Forwarder::plain(Endpoint::new(
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            stub.port,
            Proto::Udp,
        ));
        DnsService::from_config_for_test(stub_config(vec![forwarder]), family)
    }

    /// The production configuration, sized for a test: one attempt and a
    /// sub-second budget.
    fn stub_config(forwarders: Vec<Forwarder>) -> ResolverConfig {
        let mut config = resolver_config(forwarders);
        config.engine.timeout_ms = 500;
        config.engine.query_budget_ms = 1_500;
        config.maintenance_interval_ms = 60_000;
        config
    }

    #[test]
    fn an_upstream_answer_reaches_the_engine_as_an_address() {
        let stub = FakeUpstream::start(Some(FAKE_IP));
        let svc = service_over_stub(&stub, IpFamily::V4Only);
        let first = svc.resolve("peer.test", 6881, Instant::now());
        assert_eq!(first.source, Source::Resolver, "{first:?}");
        assert_eq!(first.addrs, vec![NetAddr::V4(FAKE_IP, 6881)]);
        assert!(stub.queries() >= 1);

        // The answer is memoised, so the engine's next tick is a hash probe.
        let second = svc.resolve("peer.test", 6881, Instant::now());
        assert_eq!(second.source, Source::Cache);
        assert_eq!(second.addrs, first.addrs);
        let stats = svc.stats();
        assert_eq!(stats.provider_ok, 1);
        assert_eq!(stats.cache_hits, 1);
        assert!(stats.resolver_cache_misses >= 1);
        // The stub answered, so the OS resolver was never needed.
        assert_eq!(stats.os_fallbacks, 0);
        svc.shutdown();
    }

    #[test]
    fn a_v4_only_service_never_asks_for_aaaa() {
        let stub = FakeUpstream::start(Some(FAKE_IP));
        let svc = service_over_stub(&stub, IpFamily::V4Only);
        let resolved = svc.resolve("peer.test", 6881, Instant::now());
        assert!(!resolved.addrs.is_empty());
        // One query for one type: the AAAA lookup is not made at all.
        assert_eq!(stub.queries(), 1);
        svc.shutdown();
    }

    /// NXDOMAIN from an upstream that answered is an answer: the OS resolver
    /// must not be asked to second-guess it.
    #[test]
    fn an_upstream_nxdomain_is_not_second_guessed() {
        let stub = FakeUpstream::start(None);
        let svc = service_over_stub(&stub, IpFamily::V4Only);
        let resolved = svc.resolve("gone.test", 6881, Instant::now());
        assert_eq!(resolved.source, Source::Negative, "{resolved:?}");
        assert!(resolved.addrs.is_empty());
        assert_eq!(svc.stats().os_fallbacks, 0);
        svc.shutdown();
    }

    /// The resolver failing is *not* an answer, so the OS resolver gets its turn
    /// — and its answer is memoised, which is what keeps a failing resolver from
    /// costing a file descriptor per tick.
    #[test]
    fn a_dead_upstream_falls_through_to_the_os_resolver() {
        let stub = FakeUpstream::start(Some(FAKE_IP));
        let port = stub.port;
        drop(stub); // nothing is listening on that port any more
        let mut config = stub_config(vec![Forwarder::plain(Endpoint::new(
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            port,
            Proto::Udp,
        ))]);
        config.engine.timeout_ms = 150;
        config.engine.query_budget_ms = 400;
        config.engine.max_attempts_per_server = 1;
        let svc = DnsService::from_config_for_test(config, IpFamily::V4Only);
        let first = svc.resolve("localhost", 80, Instant::now());
        assert_eq!(first.source, Source::OsResolver, "{first:?}");
        assert!(first
            .addrs
            .iter()
            .all(|a| matches!(a, NetAddr::V4(ip, _) if ip[0] == 127)));
        let stats = svc.stats();
        assert_eq!(stats.os_fallbacks, 1);
        assert!(stats.provider_failures >= 1);

        let second = svc.resolve("localhost", 80, Instant::now());
        assert_eq!(second.source, Source::Cache, "the fallback answer is memoised");
        assert_eq!(svc.stats().os_fallbacks, 1, "and not repeated");
        svc.shutdown();
    }

    #[test]
    fn two_threads_one_name_is_one_upstream_query() {
        let stub = FakeUpstream::start(Some(FAKE_IP));
        let svc = service_over_stub(&stub, IpFamily::V4Only);
        let handles: Vec<_> = (0..2)
            .map(|_| {
                let svc = svc.clone();
                std::thread::spawn(move || svc.resolve("same.test", 6881, Instant::now()))
            })
            .collect();
        let results: Vec<Resolved> = handles.into_iter().filter_map(|h| h.join().ok()).collect();
        assert_eq!(results.len(), 2);
        assert!(results.iter().all(|r| !r.addrs.is_empty()));
        // One query on the wire: the follower waits and reads the memo.
        assert_eq!(stub.queries(), 1);
        svc.shutdown();
    }

    #[test]
    fn shutdown_is_idempotent_and_leaves_no_maintenance_thread() {
        let svc = service(&policy_with(&["udp://223.5.5.5"]));
        svc.shutdown();
        svc.shutdown();
        assert!(svc.inner.resolver.is_shutting_down());
    }

    /// Entries whose upstream is unusable must not take the whole service down:
    /// the remaining ones stay, and the resolver still answers.
    #[test]
    fn one_bad_upstream_does_not_disable_the_others() {
        let svc = service(&policy_with(&["not a url at all", "udp://223.5.5.5"]));
        assert!(svc.forwarded());
        assert_eq!(svc.stats().providers.len(), 1);
        assert_eq!(svc.stats().problems.len(), 1);
        svc.shutdown();
    }

    /// An upstream that has never answered is not reported as healthy just
    /// because it has never been *probed*: the resolver's path model records
    /// timeouts and SERVFAILs, not transport errors, so "no evidence" plus a
    /// failure elsewhere has to read as down. Claiming a dead upstream is up is
    /// the mistake a user acts on wrongly.
    #[test]
    fn an_upstream_that_never_answered_is_not_reported_up() {
        let stub = FakeUpstream::start(Some(FAKE_IP));
        let port = stub.port;
        drop(stub);
        let mut config = stub_config(vec![Forwarder::plain(Endpoint::new(
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            port,
            Proto::Udp,
        ))]);
        config.engine.timeout_ms = 150;
        config.engine.query_budget_ms = 400;
        config.engine.max_attempts_per_server = 1;
        let svc = DnsService::from_config_for_test(config, IpFamily::V4Only);
        let before = svc.stats();
        assert_eq!(before.providers.len(), 1);
        assert!(before.providers[0].up, "nothing has failed yet");

        let _ = svc.resolve("localhost", 80, Instant::now());
        let after = svc.stats();
        assert!(after.provider_failures >= 1, "{after:?}");
        assert!(!after.providers[0].up, "a failed upstream must not read as up");
        assert_eq!(after.providers[0].successes, 0);
        svc.shutdown();
    }

    fn fresh(lookup: Lookup) -> Vec<IpAddr> {
        match lookup {
            Lookup::Fresh(addrs) => addrs,
            other => panic!("expected a fresh answer, got {other:?}"),
        }
    }
}
