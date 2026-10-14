package com.typebit.data

import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

// The WebUI reads settings and posts them straight back, so the JSON must be the *whole* model: a field
// omitted here cannot be sent back by the browser, and it then resets itself on save.
class SettingsEncodeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun the_whole_model_is_encoded() {
        val root = json.encodeToJsonElement(AppSettings()).jsonObject
        println("top-level sections: ${root.keys}")
        for ((section, value) in root) {
            val n = (value as? kotlinx.serialization.json.JsonObject)?.size ?: 0
            println("  $section: $n fields")
        }
        val bt = root["bitTorrent"]!!.jsonObject
        for (key in listOf("enableDht", "useDefaultTrackers", "trackerUpdateUrl", "trackerUpdateHours")) {
            assert(key in bt) { "bitTorrent.$key missing from the encoded settings" }
        }
        assert(root["connection"]!!.jsonObject.size > 20)
    }
}
