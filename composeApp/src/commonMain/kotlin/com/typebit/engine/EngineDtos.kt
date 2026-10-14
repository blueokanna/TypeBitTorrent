package com.typebit.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire DTOs; property names mirror the Rust JSON (snake_case), so the contract stays greppable. */
@Serializable
data class FileDto(
        val path: List<String> = emptyList(),
        val length: Long = 0L,
        /** User rename (relative path); null keeps the original name. */
        val renamed: String? = null,
) {
    val displayPath: String
        get() = path.joinToString("/")

    val effectivePath: String
        get() = renamed ?: displayPath
}

/** Mirrored metainfo for one torrent; unresolved magnets have no entry. */
@Serializable
data class TorrentInfoDto(
        val hash: String = "",
        val name: String = "",
        val kind: String = "unknown",
        val size: Long = 0L,
        val piece_length: Long = 0L,
        val piece_count: Long = 0L,
        val `private`: Boolean = false,
        val metadata_ready: Boolean = false,
        val comment: String? = null,
        val created_by: String? = null,
        val creation_date: Long? = null,
        val announce_list: List<List<String>> = emptyList(),
        val web_seeds: List<String> = emptyList(),
        val files: List<FileDto> = emptyList(),
) {
    val trackers: List<String>
        get() = announce_list.flatten()

    fun effectiveName(): String =
            if (name.isNotBlank()) name else files.firstOrNull()?.path?.firstOrNull() ?: hash
}

@Serializable
data class TorrentStateDto(
        val hash: String = "",
        val save_path: String = "",
        val have: Long = 0L,
        /** Verified-piece bitfield, MSB-first, hex-encoded. */
        val hx: String = "",
        val paused: Boolean = false,
)

/** Compact-key runtime row from `Cmd::Snapshot` (native/src/engine.rs). */
@Serializable
data class TorrentSnapshotDto(
        val h: String = "",
        /** Progress 0.0..1.0. */
        val p: Double = 0.0,
        /** Payload bytes downloaded / uploaded, without protocol overhead. */
        val d: Long = 0L,
        val u: Long = 0L,
        val c: Boolean = false,
        val paused: Boolean = false,
        val have: Long = 0L,
        /** Verified-piece bitfield, MSB-first, hex-encoded. */
        val hx: String = "",
        val name: String = "",
        val size: Long = 0L,
        val pieces: Long = 0L,
        val meta: Boolean = false,
)

@Serializable
data class PeerDto(
        val addr: String = "",
        val client: String = "",
        /** ISO-3166 alpha-2 country code; empty when unknown or private. */
        val cc: String = "",
        /** 0=Connecting 1=Handshake 2=Ready 3=Closed. */
        val phase: Int = 0,
        val seed: Boolean = false,
        /** Smoothed rates, bytes/s. */
        val down: Long = 0L,
        val up: Long = 0L,
        /** Outstanding request blocks (pipeline depth). */
        val inflight: Int = 0,
) {
    val phaseLabel: String
        get() =
                when (phase) {
                    0 -> "连接中"
                    1 -> "握手中"
                    2 -> if (seed) "已连接 (做种)" else "已连接"
                    else -> "已断开"
                }
}

@Serializable
data class SnapshotTotalsDto(
        val d: Long = 0L,
        val u: Long = 0L,
)

/**
 * Batched snapshot per UI poll tick. `@SerialName` is load-bearing: the JSON is snake_case and
 * this decoder has no naming strategy, so an unannotated camelCase property keeps its default.
 */
@Serializable
data class EngineSnapshotDto(
        val dht: Int = 0,
        /** Active (not failed) trackers, all torrents. */
        val trackers: Int = 0,
        /** NAT-detected external UDP endpoint (BEP-42); empty / 0 until confirmed. */
        @SerialName("ext_ip") val extIp: String = "",
        @SerialName("ext_port") val extPort: Int = 0,
        /** UPnP/NAT-PMP phase code (6 = mapped, 9 = failed); `typebit::portmap::PortMapPhase::code`. */
        @SerialName("pm_phase") val pmPhase: Int = 0,
        @SerialName("pm_port") val pmPort: Int = 0,
    /** Actually bound TCP port (0 = not listening) — the port inbound peers reach and a firewall rule must open. */
        @SerialName("listen_port") val listenPort: Int = 0,
        /** LSD (BEP-14) counters: announces sent, BT-SEARCH datagrams received, peers found. */
        val lsd_sent: Long = 0,
        val lsd_recv: Long = 0,
        val lsd_peers: Long = 0,
        val totals: SnapshotTotalsDto = SnapshotTotalsDto(),
        val torrents: List<TorrentSnapshotDto> = emptyList(),
) {
    val totalsPair: Pair<Long, Long>
        get() = totals.d to totals.u

    val portMappingLabel: String
        get() =
                when (pmPhase) {
                    0 -> "Disabled"
                    1, 2 -> "NAT-PMP detection in progress…"
                    3, 4, 5 -> "UPnP mapping in progress…"
                    6 -> if (pmPort > 0) "Port $pmPort mapped (TCP+UDP)" else "Mapped"
                    7 -> "Removing mapping…"
                    8 -> "Mapping cancelled"
                    9 -> "Mapping failed (router does not support UPnP/NAT-PMP)"
                    else -> "Unknown status"
                }
}

/**
 * One engine event; `t` selects the variant: 1=PeerConnected(h,a,p) 2=PieceVerified(h,piece)
 * 3=HashFailure(h,piece) 4=TorrentComplete(h) 5=MetadataComplete(h) 6=MetadataFailed(h)
 * 7=TrackerAnnounced(h,peers) 8=DhtNodeCount(n) 9=LeechClientSeen(c=client,a=addr)
 * 10=PeerBanned(h,a,r=reason) 11=EngineError(code,detail) 12=PortMapping(phase,port)
 */
@Serializable
data class EngineEventDto(
        val t: Int = 0,
        val h: String = "",
        val a: String? = null,
        val p: String? = null,
        val piece: Int? = null,
        val peers: Int? = null,
        val n: Int? = null,
        /** Anti-leech: detected client code (t=9) or ban reason (t=10). */
        val c: String? = null,
        /** Anti-leech ban reason code: corrupt | protocol | free-ride (t=10). */
        val r: String? = null,
        /** t=11 degradation notice: 0=udp_open_failed, 1=dht_no_seeds; `detail` is the stable tag. */
        val code: Int? = null,
        val detail: String? = null,
        /** Port-mapping phase (t=12); `PortMapPhase::code`, 6 = mapped. */
        val phase: Int? = null,
        val port: Int? = null,
)

/** Windows system action result; the Rust side returns `{"ok":bool,"message":".."}` (firewall.rs). */
@Serializable
data class SystemResultDto(
        val ok: Boolean = false,
        val message: String = "",
) {}

val portMappingPhaseName: (Int) -> String = { phase ->
    when (phase) {
        0 -> "Disabled"
        1, 2 -> "NAT-PMP detection in progress…"
        3, 4, 5 -> "UPnP mapping in progress…"
        6 -> "Mapped"
        7 -> "Removing mapping…"
        8 -> "Mapping cancelled"
        9 -> "Mapping failed"
        else -> "Unknown status"
    }
}

@Serializable
data class LogEntryDto(
        val l: Int = 0,
        val m: String = "",
)

data class GlobalStats(
        val downloadedTotal: Long = 0L,
        val uploadedTotal: Long = 0L,
        val dhtNodes: Int = 0,
        val torrentCount: Int = 0,
)

/** Field names mirror `Cmd::Stats` JSON (native/src/engine.rs::stats_to_json). */
@Serializable
data class EngineStatsDto(
        val d_total: Long = 0L,
        val u_total: Long = 0L,
        val d_discarded: Long = 0L,
        val d_peers: Long = 0L,
        val c_read_ops: Long = 0L,
        val c_read_hits: Long = 0L,
        val c_read_bytes: Long = 0L,
        val c_write_ops: Long = 0L,
        val c_write_bytes: Long = 0L,
        val c_coalesced: Long = 0L,
        val c_ops_saved: Long = 0L,
        val c_evictions: Long = 0L,
        val c_buf: Long = 0L,
        val c_budget: Long = 0L,
        val c_clean: Long = 0L,
        val c_clean_budget: Long = 0L,
        val c_dirty_entries: Long = 0L,
        /** 1 = forwards to the configured upstreams, 0 = walks from the root (native/src/dns.rs). */
        val dns_mode: Long = 0L,
        val dns_queries: Long = 0L,
        val dns_cache_hits: Long = 0L,
        val dns_os_fallbacks: Long = 0L,
        val dns_provider_ok: Long = 0L,
        val dns_provider_failures: Long = 0L,
        /** Healthy upstreams out of the configured total. */
        val dns_providers_up: Long = 0L,
        val dns_providers_total: Long = 0L,
        val dns_upstream_queries: Long = 0L,
        val dns_upstream_timeouts: Long = 0L,
        val dns_servfails: Long = 0L,
        val dns_nxdomain: Long = 0L,
        val dns_nodata: Long = 0L,
        val dns_rate_limited: Long = 0L,
        val dns_stale: Long = 0L,
        val dns_dnssec_failures: Long = 0L,
        val dns_validated: Long = 0L,
        val dns_resolver_cache_hits: Long = 0L,
        val dns_resolver_cache_misses: Long = 0L,
        val dns_avg_resolve_us: Long = 0L,
        /** Unusable upstream entries, as the user typed them. */
        val dns_problems: String = "",
) {
    val ratio: Double
        get() = if (d_total > 0) u_total.toDouble() / d_total else 0.0

    val readHitRate: Double
        get() = if (c_read_ops > 0) c_read_hits.toDouble() / c_read_ops else 0.0

    val writeOverload: Double
        get() = if (c_budget > 0) (c_buf - c_clean).coerceAtLeast(0L).toDouble() / c_budget else 0.0

    val readOverload: Double
        get() = if (c_clean_budget > 0) c_clean.toDouble() / c_clean_budget else 0.0

    val dnsSummary: String?
        get() {
            if (dns_queries <= 0L && dns_providers_total <= 0L) return null
            // No upstreams means root-walking resolution: a mode, not a failure.
            val mode =
                if (dns_providers_total <= 0L) "从根迭代"
                else "转发 $dns_providers_up/$dns_providers_total 可用"
            val first =
                "$mode · 查询 $dns_queries · 缓存命中 $dns_cache_hits · 系统解析回退 $dns_os_fallbacks"
            val avg = if (dns_avg_resolve_us > 0L) " · 平均 ${dns_avg_resolve_us / 1000} ms" else ""
            // `dns_provider_ok` counts every answer the resolver produced (cache included); the
            // `_upstream_*` counters are only the iterative walk's authoritative queries.
            val second =
                "解析成功 $dns_provider_ok / 失败 $dns_provider_failures · " +
                    "权威查询 $dns_upstream_queries / 超时 $dns_upstream_timeouts$avg\n" +
                    "DNSSEC 校验 $dns_validated / 失败 $dns_dnssec_failures · " +
                    "解析器缓存 $dns_resolver_cache_hits 命中"
            return "$first\n$second"
        }

    /**
     * Reason a rejected upstream entry is shown next to the resolver field: a dropped entry must be
     * visible, or the only symptom is "I added my resolver and nothing changed".
     */
    val dnsProblems: String?
        get() = dns_problems.trim().ifEmpty { null }
}

/**
 * Signed proof-of-download receipt; the schema mirrors `native/src/engine.rs::receipt_to_json`.
 */
@Serializable
data class ReceiptDto(
        val version: Int = 1,
        /** Infohash of the attested content: hex, 32 bytes. */
        val content_root: String = "",
        /** Ed25519 public key of the signing node: hex, 32 bytes. */
        val node_id: String = "",
        /** Attested byte range: inclusive start, exclusive end. */
        val range_start: Long = 0L,
        val range_end: Long = 0L,
        /** Wall-clock window in unix seconds: inclusive start, exclusive end. */
        val epoch_start: Long = 0L,
        val epoch_end: Long = 0L,
        val bytes_received: Long = 0L,
        val challenge_digest: String = "",
        /** Hash over sampled real blocks: hex, 32 bytes. */
        val data_proof: String = "",
        /** Ed25519 signature over the canonical message: hex, 64 bytes. */
        val signature: String = "",
) {
    val rangeBytes: Long get() = (range_end - range_start).coerceAtLeast(0L)

    val coverageRatio: Double
        get() = if (rangeBytes > 0) bytes_received.toDouble() / rangeBytes else 0.0
}

@Serializable
data class ReceiptVerifyResultDto(
        val ok: Boolean = false,
        val error: String? = null,
        val node_id: String = "",
        val content_root: String = "",
        val range_start: Long = 0L,
        val range_end: Long = 0L,
        val epoch_start: Long = 0L,
        val epoch_end: Long = 0L,
        val bytes_received: Long = 0L,
)
