//! Anti-leech peer identification.
//!
//! `typebit 0.1.0` does not expose a per-peer reject/ban API (the `Host`
//! trait has no choke/ban hooks and `PeerConnected` carries no connection
//! handle). What we CAN do honestly is identify leeching clients from the
//! 20-byte peer ID the engine reports on every connection, count them and
//! surface them to the UI + logs. The user sees which clients are leeching
//! and how often; actual connection refusal requires an engine-level hook
//! and is tracked as a roadmap item (see README).

/// Identifies the client from a 20-byte peer ID (BEP-20 style `-XX####-…`).
/// Returns a short display name, or `None` when the ID is not recognizable.
pub fn detect_client(peer_id: &[u8]) -> Option<&'static str> {
    if peer_id.len() != 20 {
        return None;
    }
    let code: String = if peer_id[0] == b'-' {
        String::from_utf8_lossy(&peer_id[1..3]).to_ascii_uppercase()
    } else {
        String::from_utf8_lossy(&peer_id[0..4]).to_ascii_uppercase()
    };
    Some(match code.as_str() {
        "XL" => "Xunlei (迅雷)",
        "SD" => "ThunderX (闪电下载)",
        "XF" => "QQ Xuanfeng (旋风)",
        "QQ" => "QQ Download",
        "DN" => "Demonoid Leech",
        "BC" => "BitComet (legacy)",
        "UT" => "µTorrent",
        "TR" => "Transmission",
        "qB" | "QB" => "qBittorrent",
        "AR" => "aria2",
        "DE" => "Deluge",
        "LT" => "libtorrent",
        "AZ" => "Azureus/Vuze",
        "FT" => "FoxTorrent",
        "RS" => "BitTorrent (official)",
        "BT" | "BN" | "BX" => "BitTorrent (official)",
        "KT" => "KTorrent",
        "SP" => "Bittornado",
        "TS" => "TorrentStorm",
        "MP" => "MooPolice",
        _ => return None,
    })
}

/// Leeching / upload-squatting clients we treat as hostile.
const LEECH_TAGS: &[&str] = &[
    "XL", // Xunlei — the classic leech: downloads aggressively, uploads ~0.
    "SD", // Lightning/ThunderX variants.
    "XF", // QQ Xuanfeng.
    "QQ", // QQ Download.
    "DN", // Old Demonoid leech build.
];

/// Returns the client name when [peer_id] belongs to a known leeching
/// client, else `None`.
pub fn detect_leech(peer_id: &[u8]) -> Option<&'static str> {
    if peer_id.len() != 20 {
        return None;
    }
    let code: String = if peer_id[0] == b'-' {
        String::from_utf8_lossy(&peer_id[1..3]).to_ascii_uppercase()
    } else {
        String::from_utf8_lossy(&peer_id[0..4]).to_ascii_uppercase()
    };
    if LEECH_TAGS.contains(&code.as_str()) {
        detect_client(peer_id)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pid(prefix: &[u8]) -> [u8; 20] {
        let mut b = [0u8; 20];
        for (i, &x) in prefix.iter().take(20).enumerate() {
            b[i] = x;
        }
        b
    }

    #[test]
    fn detects_xunlei_bep20() {
        // -XL1234-...
        assert_eq!(detect_leech(&pid(b"-XL1234-123456789012")), Some("Xunlei (迅雷)"));
    }

    #[test]
    fn detects_nonstandard_xunlei() {
        // XL0001...
        assert_eq!(detect_leech(&pid(b"XL0001-1234567890123")), Some("Xunlei (迅雷)"));
    }

    #[test]
    fn ignores_benign_clients() {
        assert_eq!(detect_leech(&pid(b"-qB4500-123456789012")), None);
        assert_eq!(detect_leech(&pid(b"-TR3000-123456789012")), None);
        assert_eq!(detect_leech(&pid(b"-UT2400-123456789012")), None);
    }

    #[test]
    fn short_peer_id_ignored() {
        assert_eq!(detect_leech(&pid(b"-XL")), None);
    }
}
