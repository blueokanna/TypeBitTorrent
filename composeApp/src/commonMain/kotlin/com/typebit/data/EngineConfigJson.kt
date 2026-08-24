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
        val port = if (conn.useRandomPort) Platform.findFreePort() else conn.listenPort
        return buildJsonObject {
            put("listen_port", port)
            put("cache_bytes", bt.cacheBytes)
            put("dht_enabled", bt.enableDht)
            put("max_peers", bt.maxPeersPerTorrent)
            put("request_pipeline", bt.requestPipeline)
            put("endgame_pieces", bt.endgamePieces)
            put("smart_scheduling", bt.smartScheduling)
            put("use_default_trackers", bt.useDefaultTrackers)
            put("seeding_slots", bt.seedingSlots)
            put("leeching_slots", bt.leechingSlots)
            put("optimistic_interval_ms", bt.optimisticIntervalMs)
            put("snub_timeout_ms", bt.snubTimeoutMs)
            put("choke_interval_ms", bt.chokeIntervalMs)
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
            put("endgame_pieces", bt.endgamePieces)
            put("smart_scheduling", bt.smartScheduling)
            put("use_default_trackers", bt.useDefaultTrackers)
            put("seeding_slots", bt.seedingSlots)
            put("leeching_slots", bt.leechingSlots)
            put("optimistic_interval_ms", bt.optimisticIntervalMs)
            put("snub_timeout_ms", bt.snubTimeoutMs)
            put("choke_interval_ms", bt.chokeIntervalMs)
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
