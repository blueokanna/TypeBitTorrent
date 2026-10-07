//! Platform network facts the engine cannot discover for itself.
//!
//! Right now that is exactly one thing: the default gateway, which NAT-PMP
//! (RFC 6886) needs to send its request to and which SSDP uses as a fallback
//! when multicast is filtered. The engine's `Host::default_gateway` hook has no
//! default, so without this the port mapper degrades to UPnP-only — which is
//! fine on most home routers, but leaves a seeding client unreachable on the
//! ones that only speak NAT-PMP.
//!
//! Both implementations are dependency-free and read-only:
//! * Linux/Android: `/proc/net/route` plus `/proc/net/ipv6_route`.
//! * Windows: `GetBestRoute` (the same call `route print` uses) via the
//!   `windows-sys` dependency the sparse-file code already pulls in.
//!
//! It also owns the one socket option that matters for reachability:
//! `IPV6_V6ONLY = 0`, so a single UDP socket serves IPv4 and IPv6 and the DHT
//! can talk to whichever the swarm speaks. `std` exposes no socket options, so
//! this is the only place that needs raw FFI — kept to a handful of lines with
//! a no-op fallback everywhere it is not supported.
//!
//! Everything is best-effort by design: `None` costs nothing but a mapper
//! that has one fewer option.

use typebit::platform::NetAddr;

/// Sets `IPV6_V6ONLY` on a socket so one bind serves both address families.
///
/// Best-effort: a platform or kernel that refuses the option leaves the socket
/// as-is (the caller falls back to an IPv4-only bind when the socket cannot do
/// dual-stack at all), and the failure is never fatal.
pub fn set_dual_stack(sock: &std::net::UdpSocket, v6only: bool) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::fd::AsRawFd;
        let value: CInt = if v6only { 1 } else { 0 };
        // SAFETY: `fd` is a live socket owned by `sock`, and the option value
        // is a correctly sized `c_int` that outlives the call.
        let rc = unsafe {
            set_sockopt(
                sock.as_raw_fd(),
                IPPROTO_IPV6,
                IPV6_V6ONLY,
                &value as *const CInt as *const std::ffi::c_void,
                std::mem::size_of::<CInt>() as u32,
            )
        };
        if rc != 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawSocket;
        let value: CInt = if v6only { 1 } else { 0 };
        // SAFETY: as above, with the Winsock entry point.
        let rc = unsafe {
            windows_setsockopt(
                sock.as_raw_socket() as usize,
                IPPROTO_IPV6,
                IPV6_V6ONLY,
                &value as *const CInt as *const std::ffi::c_char,
                std::mem::size_of::<CInt>() as i32,
            )
        };
        if rc != 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (sock, v6only);
        Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "dual-stack sockets are not supported on this platform",
        ))
    }
}

#[cfg(unix)]
type CInt = std::ffi::c_int;

#[cfg(unix)]
const IPPROTO_IPV6: CInt = 41;
#[cfg(unix)]
const IPV6_V6ONLY: CInt = 26;

#[cfg(unix)]
unsafe extern "C" {
    fn setsockopt(
        fd: std::ffi::c_int,
        level: std::ffi::c_int,
        name: std::ffi::c_int,
        value: *const std::ffi::c_void,
        len: u32,
    ) -> std::ffi::c_int;
}

#[cfg(unix)]
unsafe fn set_sockopt(
    fd: std::ffi::c_int,
    level: std::ffi::c_int,
    name: std::ffi::c_int,
    value: *const std::ffi::c_void,
    len: u32,
) -> std::ffi::c_int {
    // SAFETY: forwarded to the platform `setsockopt` with the caller's
    // guarantees (valid fd, correctly sized option value).
    unsafe { setsockopt(fd, level, name, value, len) }
}

#[cfg(windows)]
type CInt = i32;

#[cfg(windows)]
const IPPROTO_IPV6: CInt = 41;
#[cfg(windows)]
const IPV6_V6ONLY: CInt = 27;

#[cfg(windows)]
unsafe fn windows_setsockopt(
    s: usize,
    level: i32,
    optname: i32,
    optval: *const std::ffi::c_char,
    optlen: i32,
) -> i32 {
    // SAFETY: forwarded to Winsock's `setsockopt` with the caller's
    // guarantees (live socket, correctly sized option value). The constants
    // above are the protocol's own ABI values, not a local invention.
    unsafe {
        windows_sys::Win32::Networking::WinSock::setsockopt(
            s as windows_sys::Win32::Networking::WinSock::SOCKET,
            level,
            optname,
            optval as *const u8,
            optlen,
        )
    }
}

/// The default gateway for the interface carrying the default route.
pub fn default_gateway() -> Option<NetAddr> {
    #[cfg(windows)]
    {
        windows_gateway()
    }
    #[cfg(not(windows))]
    {
        proc_gateway().or_else(proc_gateway_v6)
    }
}

/// Reads `/proc/net/route` (Linux, Android, and every NAS this project ships
/// a package for).
///
/// The file is a fixed-width table; the gateway is hex, little-endian, and the
/// default route is the row with destination `00000000` and a non-zero
/// gateway.
#[cfg(not(windows))]
fn proc_gateway() -> Option<NetAddr> {
    let text = std::fs::read_to_string("/proc/net/route").ok()?;
    for line in text.lines().skip(1) {
        let mut fields = line.split_whitespace();
        let _iface = fields.next()?;
        let dest = fields.next()?;
        let gateway = fields.next()?;
        if dest != "00000000" || gateway == "00000000" {
            continue;
        }
        if let Some(octets) = parse_le_hex_v4(gateway) {
            if octets != [0, 0, 0, 0] {
                return Some(NetAddr::V4(octets, 0));
            }
        }
    }
    None
}

/// Reads `/proc/net/ipv6_route` for a v6 default route, for hosts that have
/// IPv6 but no IPv4 default (and no IPv4 NAT-PMP to speak of).
#[cfg(not(windows))]
fn proc_gateway_v6() -> Option<NetAddr> {
    let text = std::fs::read_to_string("/proc/net/ipv6_route").ok()?;
    for line in text.lines() {
        let fields: Vec<&str> = line.split_whitespace().collect();
        // destination(32) source(32) next-hop(32) ... — the default route is
        // all-zero destination, and the next hop must not be all zero.
        if fields.len() < 5 || fields[0] != "00000000000000000000000000000000" {
            continue;
        }
        let hop = fields[4];
        if hop.len() != 32 || hop.chars().all(|c| c == '0') {
            continue;
        }
        let mut octets = [0u8; 16];
        let mut ok = true;
        for (i, slot) in octets.iter_mut().enumerate() {
            match u8::from_str_radix(&hop[i * 2..i * 2 + 2], 16) {
                Ok(b) => *slot = b,
                Err(_) => {
                    ok = false;
                    break;
                }
            }
        }
        if ok {
            return Some(NetAddr::V6(octets, 0));
        }
    }
    None
}

/// Parses the little-endian hex IPv4 notation used by `/proc/net/route`.
#[cfg(not(windows))]
fn parse_le_hex_v4(text: &str) -> Option<[u8; 4]> {
    let value = u32::from_str_radix(text, 16).ok()?;
    // The kernel prints the address in host byte order on little-endian CPUs,
    // but the field itself is always the raw 32-bit address, so the bytes come
    // out low-first.
    Some([
        (value & 0xff) as u8,
        ((value >> 8) & 0xff) as u8,
        ((value >> 16) & 0xff) as u8,
        ((value >> 24) & 0xff) as u8,
    ])
}

/// `GetBestRoute(0.0.0.0)` → the next hop for the default route.
#[cfg(windows)]
fn windows_gateway() -> Option<NetAddr> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{GetBestRoute, MIB_IPFORWARDROW};

    let mut row: MIB_IPFORWARDROW = unsafe { std::mem::zeroed() };
    // SAFETY: `row` is a valid, writable MIB_IPFORWARDROW and the two address
    // arguments are plain `u32` network addresses (0 = the default route);
    // both live for the duration of the call.
    let status = unsafe { GetBestRoute(0, 0, &mut row) };
    if status != 0 {
        return None;
    }
    // The next hop is a `u32` in network byte order.
    let raw = row.dwForwardNextHop;
    if raw == 0 {
        return None;
    }
    Some(NetAddr::V4(raw.to_ne_bytes(), 0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(not(windows))]
    #[test]
    fn little_endian_gateway_parsing() {
        // 192.168.1.1 stored little-endian is 0x0101A8C0.
        assert_eq!(parse_le_hex_v4("0101A8C0"), Some([192, 168, 1, 1]));
        assert_eq!(parse_le_hex_v4("00000000"), Some([0, 0, 0, 0]));
        assert_eq!(parse_le_hex_v4("nonsense"), None);
    }

    #[test]
    fn gateway_lookup_is_total_and_returns_a_usable_address() {
        // The value is machine-dependent (a lab box may have no default route at
        // all), so the contract under test is: the lookup returns promptly, and
        // when it does return an address that address is a usable unicast one.
        let started = std::time::Instant::now();
        let gateway = default_gateway();
        println!("default_gateway = {gateway:?}");
        if let Some(NetAddr::V4(ip, _)) = gateway {
            assert_ne!(ip, [0, 0, 0, 0], "a zero next hop is not a gateway");
            assert_ne!(ip[0], 255, "broadcast is not a gateway");
        }
        assert!(started.elapsed() < std::time::Duration::from_secs(2));
    }
}
