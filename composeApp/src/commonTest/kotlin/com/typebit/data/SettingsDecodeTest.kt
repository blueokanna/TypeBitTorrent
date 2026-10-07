package com.typebit.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * Settings decoding, which is the one place a hand-edited file meets the app.
 *
 * The DNS upstream list is what a NAS owner edits by hand, and the file they
 * edit it in is whatever Windows or their editor gives them — usually with a
 * byte-order mark. Rejecting that file would silently reset every other
 * setting, so the tolerance is a contract, not a nicety.
 */
class SettingsDecodeTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Test
    fun a_bom_does_not_break_a_hand_edited_file() {
        val text = "\uFEFF" + """{ "connection": { "dohProviders": "tls://1.1.1.1#one.one.one.one" } }"""
        val settings = assertNotNull(decodeSettings(json, text), "a BOM-prefixed file must decode")
        assertEquals("tls://1.1.1.1#one.one.one.one", settings.connection.dohProviders)
    }

    @Test
    fun a_plain_file_decodes_and_ignores_unknown_keys() {
        val text = """{ "connection": { "dohProviders": "udp://223.5.5.5" }, "futureKnob": 7 }"""
        val settings = assertNotNull(decodeSettings(json, text))
        assertEquals("udp://223.5.5.5", settings.connection.dohProviders)
    }

    @Test
    fun an_empty_object_is_a_valid_settings_file() {
        val settings = assertNotNull(decodeSettings(json, "{}"))
        assertEquals(DEFAULT_DOH_PROVIDERS, settings.connection.dohProviders)
    }

    @Test
    fun nonsense_is_reported_as_a_decode_failure_rather_than_a_half_parsed_file() {
        assertNull(decodeSettings(json, "{ \"connection\": "))
        assertNull(decodeSettings(json, "not json at all"))
        assertNull(decodeSettings(json, ""))
    }
}
