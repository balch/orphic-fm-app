package org.balch.orpheus.djapp.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.mixer.f2
import org.balch.orpheus.djapp.ai.mixer.fixed

private val summaryJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * A tool row's one-line summary from the call's [args] JSON, e.g. "mix_set BASS −3.0 dB": the tool's name and its
 * most telling arguments. Null when the call has nothing worth showing, so the row keeps its plain label.
 */
internal fun toolSummary(tool: String, args: String?, trackName: (Int) -> String = ::mixerTrackName): String? {
    val a = args?.let { runCatching { summaryJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?: return if (tool == "pulsar_apply_vibe") args?.let(::cutVibeName)?.let { "$tool \"$it\"" } else null
    fun str(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    fun int(k: String) = (a[k] as? JsonPrimitive)?.intOrNull
    fun num(k: String) = (a[k] as? JsonPrimitive)?.floatOrNull
    fun track() = int("track")?.let(trackName)
    val detail: String? = when (tool) {
        "mix_set" -> {
            val v = num("value") ?: return null
            val what = when (str("field")) {
                "level_db" -> "${track() ?: "?"} ${signedDb(v)}"
                "pan" -> "${track() ?: "?"} pan ${CollabMixer.panText(v)}"
                "reverb", "delay" -> "${track() ?: "?"} ${str("field")} ${f2(v)}"
                "macro" -> "${str("macro") ?: "macro"} ${f2(v)}"
                "delay_time_a", "delay_time_b", "delay_feedback" -> "${str("field")} ${f2(v)}"
                else -> return null
            }
            if (str("scope") == "section") "$what · section" else what
        }
        "mix_mute", "mix_solo" -> track()
        "section_jump" -> str("name") ?: int("section")?.let { "section $it" }
        "section_loop" -> (a["on"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) "on" else "off" }
        "vibe_load" -> str("name")
        "transport" -> str("action")
        "port_set" -> str("key")?.let { k -> num("value")?.let { "$k ${f2(it)}" } ?: k }
        "pulsar_apply_vibe" -> str("vibeJson")?.let(::vibeName)?.let { "\"$it\"" }
        else -> null
    }
    return detail?.let { "$tool $it" }
}

/** "+2.0 dB", "−3.0 dB" with a true minus sign. */
private fun signedDb(db: Float): String {
    val t = fixed(db, 1)
    return when {
        t.startsWith("-") -> "−${t.drop(1)} dB"
        t == "0.0" -> "0.0 dB"
        else -> "+$t dB"
    }
}

/** The first `\"name\":\"…` in a vibe's escaped JSON. */
private val CUT_VIBE_NAME = Regex("""\\"name\\"\s*:\s*\\"([^"\\]+)""")

/** A big vibe's arguments arrive cut short and no longer parse, so its name is read from the text. */
private fun cutVibeName(args: String): String? = CUT_VIBE_NAME.find(args)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

private fun vibeName(vibeJson: String): String? = runCatching {
    ((summaryJson.parseToJsonElement(vibeJson) as JsonObject)["name"] as? JsonPrimitive)?.contentOrNull
}.getOrNull()?.takeIf { it.isNotBlank() }
