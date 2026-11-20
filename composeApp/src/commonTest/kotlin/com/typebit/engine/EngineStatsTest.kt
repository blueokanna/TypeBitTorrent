package com.typebit.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EngineStatsTest {
    @Test
    fun cacheHitRateCountsBytesFromBothSources() {
        val stats = EngineStatsDto(c_read_hits = 16384, c_read_bytes = 49152, c_read_ops = 3)
        assertEquals(0.25, stats.readHitRate)
        assertEquals(1.0, stats.copy(c_read_bytes = 0, c_read_ops = 0).readHitRate)
        assertNull(EngineStatsDto().readHitRate)
    }

    @Test
    fun missingNativeReplyIsDistinctFromIdleCounters() {
        assertFailsWith<SerializationException> { Json.decodeFromString<EngineStatsDto>("{}") }
        assertFailsWith<SerializationException> { Json.decodeFromString<EngineSnapshotDto>("""{"dht":0,"torrents":[]}""") }
        assertEquals(0, Json.decodeFromString<EngineStatsDto>("""{"d_total":0}""").d_total)
    }
}
