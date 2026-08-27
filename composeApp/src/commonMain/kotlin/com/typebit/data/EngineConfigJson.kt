package com.typebit.data

import com.typebit.platform.Platform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Maps [AppSettings] onto the JSON contract understood by the Rust bridge
 * (`native/src/engine.rs::parse_config`). Kept in one place so the two sides
 * of the seam are trivially auditable.
 */
object EngineConfigJson {

    /** Full engine config (used at engine creation). */
    fun engineConfig(settings: AppSettings): String {
        val bt = settings.bitTorrent
        val conn = settings.connection
        val speed = settings.speed
        val proxy = settings.connection.proxyType
        val port = if (conn.useRandomPort) Platform.findFreePort() else conn.listenPort
        return buildJsonObject {
            put("listen_port", port)
            put("cache_bytes", bt.cacheBytes)
            put("dht_enabled", bt.enableDht)
            put("lsd_enabled", bt.enableLsd)
            // LSD (BEP-14) announce interval: one infohash per interval,
            // round-robin over active torrents (mechanism 4). The engine
            // clamps it to a 30 s hard floor.
            put("lsd_interval_ms", 60_000)
            // typebit 0.1.1 built-in token-bucket global limits (bytes/sec).
            put("global_download_limit_bps", speed.globalDownloadLimitKib * 1024)
            put("global_upload_limit_bps", speed.globalUploadLimitKib * 1024)
            put("global_max_connections", conn.maxConnections)
            put("max_connections_per_ip", 8)
            put("verify_workers", 0) // auto: one per core minus one, capped 8
            put("connect_timeout_ms", 30_000)
            // UPnP/NAT-PMP is now real in the engine (was stored-only).
            put("port_mapping", bt.enableUpnp || bt.enableNatPmp)
            // SOCKS5 proxy (outbound-only mode). The engine implements SOCKS5
            // ONLY; SOCKS4/HTTP are legacy persisted values that must never
            // enable a proxy (an unsupported type would just break dialing).
            put(
                "proxy",
                buildJsonObject {
                    put("enabled", proxy == ProxyType.SOCKS5)
                    put("host", settings.connection.proxyHost)
                    put("port", settings.connection.proxyPort)
                    put("username", if (settings.connection.proxyAuthEnabled) settings.connection.proxyUsername else "")
                    put("password", if (settings.connection.proxyAuthEnabled) settings.connection.proxyPassword else "")
                },
            )
            put("max_peers", bt.maxPeersPerTorrent)
            put("request_pipeline", bt.requestPipeline)
            put("request_timeout_ms", bt.requestTimeoutMs)
            put("max_request_timeouts", bt.maxRequestTimeouts)
            put("endgame_pieces", bt.endgamePieces)
            put("smart_scheduling", bt.smartScheduling)
            put("use_default_trackers", bt.useDefaultTrackers)
            put("seeding_slots", bt.seedingSlots)
            put("leeching_slots", bt.leechingSlots)
            put("optimistic_interval_ms", bt.optimisticIntervalMs)
            put("snub_timeout_ms", bt.snubTimeoutMs)
            put("choke_interval_ms", bt.chokeIntervalMs)
            put("block_leech_clients", bt.blockLeechClients)
            put("alpha", bt.schedulerAlpha)
            put("beta", bt.schedulerBeta)
            put("gamma", bt.schedulerGamma)
            put("delta", bt.schedulerDelta)
            put("edge_bytes", bt.schedulerEdgeBytes)
            put("trackers", parseTrackers(bt.extraTrackers))
        }.toString()
    }

    /** Session defaults only — applied to torrents added from now on. */
    fun sessionConfig(settings: AppSettings): String {
        val bt = settings.bitTorrent
        return buildJsonObject {
            put("max_peers", bt.maxPeersPerTorrent)
            put("request_pipeline", bt.requestPipeline)
            put("request_timeout_ms", bt.requestTimeoutMs)
            put("max_request_timeouts", bt.maxRequestTimeouts)
            put("endgame_pieces", bt.endgamePieces)
            put("smart_scheduling", bt.smartScheduling)
            put("use_default_trackers", bt.useDefaultTrackers)
            put("seeding_slots", bt.seedingSlots)
            put("leeching_slots", bt.leechingSlots)
            put("optimistic_interval_ms", bt.optimisticIntervalMs)
            put("snub_timeout_ms", bt.snubTimeoutMs)
            put("choke_interval_ms", bt.chokeIntervalMs)
            put("block_leech_clients", bt.blockLeechClients)
            put("alpha", bt.schedulerAlpha)
            put("beta", bt.schedulerBeta)
            put("gamma", bt.schedulerGamma)
            put("delta", bt.schedulerDelta)
            put("edge_bytes", bt.schedulerEdgeBytes)
            put("trackers", parseTrackers(bt.extraTrackers))
        }.toString()
    }

    /** Splits a newline-separated tracker list into a JSON array. */
    private fun parseTrackers(raw: String): JsonArray = buildJsonArray {
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { add(JsonPrimitive(it)) }
    }
}
