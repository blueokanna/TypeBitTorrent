//! Network policy: what this client is allowed to fetch, from where, and how
//! names are resolved.
//!
//! Two things live here, both driven by the same JSON blob the Kotlin side
//! sends with the engine config (see [`NetworkPolicy::from_config`]):
//!
//! * **The URL guard.** Every HTTP URL this host is handed comes from
//!   somewhere untrusted: a tracker announce comes from the torrent's
//!   `announce` list, a web seed from its `url-list`, and a `.torrent` file is
//!   attacker-controlled by definition. A client that fetches whatever those
//!   strings say is a server-side request forgery primitive pointed at the
//!   user's machine: `http://127.0.0.1:8080/admin`, `http://[::1]:9200/`,
//!   `http://169.254.169.254/latest/meta-data/` all become a GET from inside
//!   the network. The guard refuses those, and it deliberately keeps private
//!   ranges (RFC 1918 / ULA) working, because seeding from the NAS next to you
//!   over the LAN is a first-class use case here, not an attack.
//!
//! * **Resolution settings.** Which DoH providers to use (if any), and whether
//!   to ask for AAAA records at all.
//!
//! Nothing in this module opens a socket, so all of it is unit-testable.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// Address classification for the guard.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AddrScope {
    /// Public internet, or any address when the guard is off.
    Public,
    /// Loopback: `127.0.0.0/8`, `::1`. Refused always — a request to the
    /// user's own machine from a torrent's URL is never legitimate.
    Loopback,
    /// The cloud metadata address (`169.254.169.254`, `fd00:ec2::254`).
    /// Refused always: this is the single most valuable SSRF target there is.
    Metadata,
    /// Link-local, unspecified, multicast, broadcast, documentation-reserved
    /// and other special-purpose ranges. Refused always, except when the guard
    /// is off.
    Special,
    /// Private LAN (RFC 1918, ULA, CGNAT). Allowed by default: this is how a
    /// NAS seeds to its own LAN.
    Private,
}

impl AddrScope {
    /// Whether this scope may be fetched under `policy`.
    fn allowed(self, policy: &NetworkPolicy) -> bool {
        match self {
            AddrScope::Public => true,
            // The NAS-next-to-you case: on by default, off = paranoid mode.
            AddrScope::Private => policy.allow_private_fetch,
            AddrScope::Loopback | AddrScope::Metadata => policy.allow_loopback_fetch,
            AddrScope::Special => policy.allow_special_fetch,
        }
    }
}

/// Classifies an address. The split matters: `Private` is the NAS case the
/// guard must not break, everything else is either the internet or a target.
pub fn classify(ip: IpAddr) -> AddrScope {
    match ip {
        IpAddr::V4(v4) => classify_v4(v4),
        IpAddr::V6(v6) => classify_v6(v6),
    }
}

fn classify_v4(ip: Ipv4Addr) -> AddrScope {
    let o = ip.octets();
    // 127.0.0.0/8
    if o[0] == 127 {
        return AddrScope::Loopback;
    }
    // Cloud metadata endpoints all live in link-local.
    if ip == Ipv4Addr::new(169, 254, 169, 254) {
        return AddrScope::Metadata;
    }
    if ip.is_private() {
        return AddrScope::Private;
    }
    // 100.64.0.0/10 (CGNAT) — carrier-grade NAT is somebody else's LAN; a
    // request there is not "the internet" and not our own network either.
    if o[0] == 100 && (64..128).contains(&o[1]) {
        return AddrScope::Private;
    }
    if ip.is_unspecified()
        || ip.is_multicast()
        || ip.is_broadcast()
        || ip.is_link_local()
        || ip.is_documentation()
    {
        return AddrScope::Special;
    }
    // 0.0.0.0/8 ("this network") and 198.18.0.0/15 (benchmarking) are never
    // real destinations on an end-user machine.
    if o[0] == 0 || (o[0] == 198 && (o[1] == 18 || o[1] == 19)) {
        return AddrScope::Special;
    }
    // 192.0.0.0/24, 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 are already
    // covered by is_documentation() above; 192.88.99.0/24 (6to4 relay) is not.
    if o[0] == 192 && o[1] == 88 && o[2] == 99 {
        return AddrScope::Special;
    }
    AddrScope::Public
}

fn classify_v6(ip: Ipv6Addr) -> AddrScope {
    if ip.is_loopback() {
        return AddrScope::Loopback;
    }
    if ip.is_unspecified() || ip.is_multicast() {
        return AddrScope::Special;
    }
    // IPv4-mapped (`::ffff:a.b.c.d`): classify by the embedded address, which
    // is what a dual-stack socket actually dials.
    if let Some(v4) = ip.to_ipv4_mapped() {
        return classify_v4(v4);
    }
    let seg = ip.segments();
    // fe80::/10 link-local, fec0::/10 site-local (deprecated but still routed
    // in some networks), 2001:db8::/32 documentation, 2002::/16 6to4,
    // 2001::/32 Teredo — none of these are a destination a torrent may name.
    if (seg[0] & 0xffc0) == 0xfe80
        || (seg[0] & 0xffc0) == 0xfec0
        || (seg[0] == 0x2001 && seg[1] == 0x0db8)
        || seg[0] == 0x2002
        || (seg[0] == 0x2001 && seg[1] == 0x0000)
    {
        return AddrScope::Special;
    }
    // The AWS/GCP metadata service over IPv6.
    if seg[0] == 0xfd00 && seg[1] == 0x0ec2 && seg[2] == 0x0000 && seg[3] == 0x0254 {
        return AddrScope::Metadata;
    }
    // fc00::/7 unique local addresses are the IPv6 LAN: the NAS case.
    if (seg[0] & 0xfe00) == 0xfc00 {
        return AddrScope::Private;
    }
    AddrScope::Public
}

/// Why a URL was refused. Reported to the log, never to the peer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UrlReject {
    /// Not `http` or `https`.
    Scheme,
    /// No host component, or userinfo (`user@host`) which cannot be checked.
    Authority,
    /// Host is an IP literal in a refused scope.
    BlockedAddress(AddrScope),
    /// The URL is too long to be a real tracker/seed URL.
    TooLong,
}

impl UrlReject {
    /// A log line that says what happened and why, without echoing the URL
    /// back into the UI (the URL is attacker-influenced text).
    pub fn as_str(self) -> &'static str {
        match self {
            UrlReject::Scheme => "scheme is not http(s)",
            UrlReject::Authority => "host missing or contains userinfo",
            UrlReject::BlockedAddress(AddrScope::Loopback) => "host is a loopback address",
            UrlReject::BlockedAddress(AddrScope::Metadata) => {
                "host is a cloud metadata address"
            }
            UrlReject::BlockedAddress(AddrScope::Special) => "host is a special-use address",
            UrlReject::BlockedAddress(_) => "host address refused",
            UrlReject::TooLong => "url too long",
        }
    }
}

/// The host and port of a URL, without pulling in a URL parser.
///
/// Only `http(s)://authority[/...]` is accepted; anything else (a scheme with
/// no authority, a bare path, a `javascript:` payload) is reported as an
/// authority failure rather than guessed at.
pub fn url_host_port(url: &str) -> Option<(&str, Option<u16>)> {
    let (scheme, rest) = url.split_once("://")?;
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return None;
    }
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..end];
    if authority.is_empty() || authority.contains('@') {
        return None;
    }
    if let Some(inner) = authority.strip_prefix('[') {
        let (host, tail) = inner.split_once(']')?;
        let port = tail.strip_prefix(':').and_then(|p| p.parse::<u16>().ok());
        return Some((host, port));
    }
    match authority.rsplit_once(':') {
        Some((host, port)) => match port.parse::<u16>() {
            Ok(p) => Some((host, Some(p))),
            Err(_) => Some((authority, None)),
        },
        None => Some((authority, None)),
    }
}

/// The effective port of a URL (scheme default applied).
pub fn url_port(url: &str) -> Option<u16> {
    let (_, port) = url_host_port(url)?;
    if port.is_some() {
        return port;
    }
    let (scheme, _) = url.split_once("://")?;
    Some(if scheme.eq_ignore_ascii_case("https") { 443 } else { 80 })
}

/// The network policy applied to every engine-supplied HTTP request.
#[derive(Debug, Clone)]
pub struct NetworkPolicy {
    /// Forwarder upstream specs in priority order; empty means the resolver
    /// walks from the root itself.
    ///
    /// Each entry is `scheme://address[/path][#tls-name]` (see
    /// [`crate::dns::parse_upstream`]). Only the schemes this build can speak
    /// survive parsing; nothing here is trusted to be well-formed, because it
    /// comes from a settings file.
    pub doh_providers: Vec<String>,
    /// Ask for AAAA records as well as A.
    pub ipv6: bool,
    /// Allow fetching private LAN addresses (RFC 1918 / ULA / CGNAT).
    ///
    /// On by default: seeding from a NAS on the same LAN is the normal case
    /// here. Turning it off is the correct setting on a laptop that never
    /// intends to fetch from its local network.
    pub allow_private_fetch: bool,
    /// Allow fetching loopback / metadata addresses. Off, and not exposed in
    /// the UI: the only reason to set it is a test harness.
    pub allow_loopback_fetch: bool,
    /// Allow fetching special-use addresses (link-local, multicast, ...).
    pub allow_special_fetch: bool,
    /// Follow HTTP redirects at all. Redirects are the second half of the SSRF
    /// story: the guard checks the URL we were handed, and a redirect could
    /// send us somewhere else entirely, so the count is small by design.
    pub max_redirects: usize,
    /// Enable HTTP/2 for tracker and web-seed traffic.
    pub http2: bool,
}

impl Default for NetworkPolicy {
    fn default() -> Self {
        NetworkPolicy {
            doh_providers: Vec::new(),
            ipv6: true,
            allow_private_fetch: true,
            allow_loopback_fetch: false,
            allow_special_fetch: false,
            max_redirects: 2,
            http2: true,
        }
    }
}

impl NetworkPolicy {
    /// Reads the policy out of the flat engine-config JSON.
    ///
    /// Every key is optional; a missing key keeps the default, so an older
    /// Kotlin build talks to a newer native library without surprises.
    pub fn from_config(root: &nextjson::Value) -> NetworkPolicy {
        use nextjson::Value;

        let mut policy = NetworkPolicy::default();
        if let Some(Value::Array(items)) = root.get("doh_providers") {
            policy.doh_providers = items
                .iter()
                .filter_map(|v| v.as_str())
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
        }
        if let Some(Value::Bool(flag)) = root.get("doh_enabled") {
            if !flag {
                policy.doh_providers.clear();
            }
        }
        if let Some(Value::Bool(flag)) = root.get("ipv6_enabled") {
            policy.ipv6 = *flag;
        }
        if let Some(Value::Bool(flag)) = root.get("http2_enabled") {
            policy.http2 = *flag;
        }
        if let Some(Value::Bool(flag)) = root.get("allow_lan_webseeds") {
            // The UI switch for "my NAS seeds over the LAN": when off, private
            // ranges are refused too, which is the paranoid setting.
            policy.allow_private_fetch = *flag;
        }
        if let Some(n) = root.get("http_max_redirects").and_then(Value::as_u64) {
            policy.max_redirects = (n as usize).min(5);
        }
        policy
    }

    /// Checks a URL before it is dialled.
    ///
    /// A hostname is accepted here (it cannot be resolved without a socket, and
    /// resolving is the resolver's job); an IP literal is classified. The
    /// important part is that a hostile `.torrent` cannot reach loopback or the
    /// metadata service.
    pub fn check_url(&self, url: &str) -> Result<(), UrlReject> {
        if url.len() > MAX_URL_LEN {
            return Err(UrlReject::TooLong);
        }
        let Some((host, _)) = url_host_port(url) else {
            return Err(UrlReject::Authority);
        };
        // A bare `http://1.2.3.4` style literal, v6 in brackets already
        // stripped by `url_host_port`.
        if let Ok(ip) = host.parse::<IpAddr>() {
            let scope = classify(ip);
            if !scope.allowed(self) {
                return Err(UrlReject::BlockedAddress(scope));
            }
        }
        Ok(())
    }

    /// True when a resolved address may be dialled for a guarded request.
    ///
    /// The URL check cannot see through DNS, so a name that resolves to
    /// loopback (`localtest.me`, a hostile zone) must be caught here, before
    /// the connection is made.
    pub fn allows_address(&self, ip: IpAddr) -> bool {
        classify(ip).allowed(self)
    }
}

/// Longest URL this host will attempt. Real tracker announce URLs with a
/// full peer list are far below this; anything above is a resource attack.
const MAX_URL_LEN: usize = 8 * 1024;

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classification_covers_the_targets_and_the_lan() {
        // Refused: the user's own machine and the metadata service.
        assert_eq!(classify("127.0.0.1".parse().unwrap()), AddrScope::Loopback);
        assert_eq!(classify("127.9.9.9".parse().unwrap()), AddrScope::Loopback);
        assert_eq!(classify("::1".parse().unwrap()), AddrScope::Loopback);
        assert_eq!(
            classify("169.254.169.254".parse().unwrap()),
            AddrScope::Metadata
        );
        // Allowed: the LAN (the NAS case) and the internet.
        assert_eq!(classify("192.168.1.10".parse().unwrap()), AddrScope::Private);
        assert_eq!(classify("10.0.0.5".parse().unwrap()), AddrScope::Private);
        assert_eq!(classify("172.16.4.4".parse().unwrap()), AddrScope::Private);
        assert_eq!(classify("100.64.1.1".parse().unwrap()), AddrScope::Private);
        assert_eq!(classify("fd00::1".parse().unwrap()), AddrScope::Private);
        assert_eq!(classify("8.8.8.8".parse().unwrap()), AddrScope::Public);
        assert_eq!(
            classify("2606:4700::1111".parse().unwrap()),
            AddrScope::Public
        );
        // Special-use: never a destination a torrent may name.
        assert_eq!(classify("169.254.1.1".parse().unwrap()), AddrScope::Special);
        assert_eq!(classify("224.0.0.1".parse().unwrap()), AddrScope::Special);
        assert_eq!(classify("0.0.0.0".parse().unwrap()), AddrScope::Special);
        assert_eq!(classify("fe80::1".parse().unwrap()), AddrScope::Special);
        assert_eq!(
            classify("2001:db8::1".parse().unwrap()),
            AddrScope::Special
        );
        // IPv4-mapped v6 is classified by the embedded address: the exact
        // bypass a naive guard would miss.
        assert_eq!(
            classify("::ffff:127.0.0.1".parse().unwrap()),
            AddrScope::Loopback
        );
    }

    #[test]
    fn check_url_refuses_ssrf_targets_and_allows_real_ones() {
        let policy = NetworkPolicy::default();
        // The three classic SSRF payloads a malicious .torrent would carry.
        assert!(policy.check_url("http://127.0.0.1:8080/admin").is_err());
        assert!(policy.check_url("http://[::1]:9200/_search").is_err());
        assert!(policy
            .check_url("http://169.254.169.254/latest/meta-data/iam/security-credentials/")
            .is_err());
        assert!(policy.check_url("http://0.0.0.0/").is_err());
        assert!(policy.check_url("http://224.0.0.1/").is_err());
        // Real tracker and web-seed URLs pass, including the LAN NAS case.
        assert!(policy.check_url("https://tracker.example.org/announce").is_ok());
        assert!(policy.check_url("http://tracker.example.org:6969/announce").is_ok());
        assert!(policy.check_url("http://192.168.1.50/files/seed").is_ok());
        assert!(policy.check_url("http://[fd00::50]:8080/seed").is_ok());
        // Non-http(s) schemes and userinfo do not pass.
        assert_eq!(
            policy.check_url("file:///etc/passwd"),
            Err(UrlReject::Authority)
        );
        assert_eq!(
            policy.check_url("http://user@127.0.0.1/"),
            Err(UrlReject::Authority)
        );
        assert_eq!(policy.check_url("http:///nohost"), Err(UrlReject::Authority));
        // Length cap.
        let long = format!("http://example.com/{}", "a".repeat(MAX_URL_LEN));
        assert_eq!(policy.check_url(&long), Err(UrlReject::TooLong));
    }

    #[test]
    fn resolved_addresses_are_checked_too() {
        // A name can resolve anywhere, including loopback; the second half of
        // the guard is applied to what DNS actually returned.
        let policy = NetworkPolicy::default();
        assert!(!policy.allows_address("127.0.0.1".parse().unwrap()));
        assert!(!policy.allows_address("169.254.169.254".parse().unwrap()));
        assert!(policy.allows_address("192.168.1.10".parse().unwrap()));
        assert!(policy.allows_address("1.1.1.1".parse().unwrap()));
    }

    #[test]
    fn url_parsing_handles_ports_and_brackets() {
        assert_eq!(
            url_host_port("https://tracker.example.org/announce"),
            Some(("tracker.example.org", None))
        );
        assert_eq!(
            url_host_port("http://tracker.example.org:6969/announce?x=1"),
            Some(("tracker.example.org", Some(6969)))
        );
        assert_eq!(
            url_host_port("http://[2001:db8::1]:8080/x"),
            Some(("2001:db8::1", Some(8080)))
        );
        assert_eq!(url_port("https://a.example/x"), Some(443));
        assert_eq!(url_port("http://a.example:81/x"), Some(81));
        assert_eq!(url_host_port("not a url"), None);
        assert_eq!(url_host_port("http://host:port/x"), Some(("host:port", None)));
    }

    #[test]
    fn config_parsing_is_tolerant() {
        let json = r#"{
            "doh_enabled": true,
            "doh_providers": ["https://1.1.1.1/dns-query#cloudflare-dns.com", "tls://223.5.5.5#dns.alidns.com", ""],
            "ipv6_enabled": false,
            "http2_enabled": false,
            "allow_lan_webseeds": false,
            "http_max_redirects": 99
        }"#;
        let root: nextjson::Value = nextjson::nextdecode(json.as_bytes()).unwrap();
        let policy = NetworkPolicy::from_config(&root);
        // Blank entries are dropped; everything else is passed through
        // verbatim, because the upstream grammar belongs to the resolver and
        // refusing an entry here would report the wrong thing to the user.
        assert_eq!(
            policy.doh_providers,
            vec![
                "https://1.1.1.1/dns-query#cloudflare-dns.com".to_string(),
                "tls://223.5.5.5#dns.alidns.com".to_string()
            ]
        );
        assert!(!policy.ipv6);
        assert!(!policy.http2);
        assert!(!policy.allow_private_fetch);
        // Redirects are clamped: a large value would only widen the SSRF hole.
        assert_eq!(policy.max_redirects, 5);
        // A config with none of the keys keeps the defaults.
        let empty: nextjson::Value = nextjson::nextdecode(b"{}").unwrap();
        let d = NetworkPolicy::from_config(&empty);
        assert!(d.doh_providers.is_empty());
        assert!(d.ipv6);
        assert!(d.http2);
        assert_eq!(d.max_redirects, 2);
    }
}
