package org.balch.orpheus.djapp.bench

import kotlin.math.roundToLong
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.Overview
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.effective
import org.balch.orpheus.features.pulsar.bench.sectionScopeAvailable
import org.balch.orpheus.features.pulsar.models.Vibe

/** Everything a `state` event shows; [vibes] is only filled for the first `snapshot`. */
internal data class BenchView(
    val vibe: Vibe,
    val section: Int,
    val layers: BenchLayers,
    val scope: Scope,
    val muted: Set<Int>,
    val soloed: Set<Int>,
    val loopSection: Int?,
    val playing: Boolean,
    val rows: List<CaptureRow>,
    val overviewStatus: String,
    val stale: Boolean,
    val source: String?,
)

internal object BenchJson {
    val json = Json { encodeDefaults = true }
    private val rowsSerializer = ListSerializer(CaptureRow.serializer())

    fun state(v: BenchView, vibes: List<String>? = null): JsonObject = buildJsonObject {
        if (vibes != null) putJsonArray("vibes") { vibes.forEach { add(it) } }
        put("vibe", v.vibe.name)
        put("playing", v.playing)
        put("section", v.section)
        putJsonArray("sections") {
            v.vibe.arrangement?.sections.orEmpty().forEachIndexed { i, s ->
                add(buildJsonObject { put("index", i); put("name", s.name) })
            }
        }
        put("sectionScopeAvailable", v.vibe.sectionScopeAvailable())
        put("scope", v.scope.name)
        put("loop", v.loopSection?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("tracks") {
            for (t in v.vibe.tracks.indices) {
                val track = v.vibe.tracks[t]
                val e = v.layers.effective(v.vibe, v.section, t)
                add(buildJsonObject {
                    put("name", BenchMeters.CHANNEL_NAMES.getOrElse(t) { "T$t" })
                    put("engine", "${track.engineEdm.engineId.name}/${track.engineSpace.engineId.name}")
                    put("levelDb", r3(e.levelDb))
                    put("pan", r3(e.pan))
                    put("filePan", r3(track.pan))
                    put("reverb", r3(e.reverbSend))
                    put("delay", r3(e.delaySend))
                    put("muted", t in v.muted)
                    put("soloed", t in v.soloed)
                })
            }
        }
        putJsonObject("macros") {
            for (m in Macro.entries) {
                val file = fileMacro(v.vibe, m)
                val mult = sectionMultiplier(v.vibe, v.section, m)
                val knob = v.layers.vibe.macros[m] ?: file
                val heard = v.layers.sections[v.section]?.macrosHeard?.get(m) ?: (knob * mult).coerceIn(0f, 1f)
                putJsonObject(m.name) {
                    put("file", r3(file))
                    put("knob", r3(knob))
                    put("mult", r3(mult))
                    put("heard", r3(heard))
                }
            }
        }
        put("changes", json.encodeToJsonElement(rowsSerializer, v.rows))
        put("overview", v.overviewStatus)
        put("stale", v.stale)
        put("source", v.source)
    }

    fun overview(vibe: String, o: Overview): JsonObject = buildJsonObject {
        put("vibe", vibe)
        put("data", json.encodeToJsonElement(Overview.serializer(), o))
    }

    fun rows(rows: List<CaptureRow>): JsonElement = json.encodeToJsonElement(rowsSerializer, rows)

    /** Hand-built: this goes out 20 times a second with ~5k wave points. */
    fun meters(m: MeterSnapshot, arr: PulsarArrangementState): String = buildString(48_000) {
        append("{\"lufsS\":").num(m.lufsShort)
        append(",\"lufsI\":").num(m.lufsIntegrated)
        append(",\"peak\":").num(m.preClipPeakDb)
        append(",\"knee\":").num(m.pastKneePct)
        append(",\"corr\":").num(m.correlation)
        append(",\"section\":").append(arr.sectionIndex)
        append(",\"bar\":").append(arr.barsElapsed)
        append(",\"bars\":").append(arr.barsTotal)
        append(",\"ch\":[")
        m.channels.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"n\":\"").append(c.name).append('"')
            append(",\"lu\":").num(c.relLu)
            append(",\"pk\":").num(c.peakDb)
            append(",\"pan\":").num(c.pan)
            append(",\"sub\":").num(c.subPct)
            append(",\"low\":").num(c.lowPct)
            append(",\"w\":[")
            c.wave.forEachIndexed { j, w ->
                if (j > 0) append(',')
                num(w.toDouble())
            }
            append("]}")
        }
        append("]}")
    }

    fun fileMacro(vibe: Vibe, m: Macro): Float = when (m) {
        Macro.ENERGY -> vibe.energy
        Macro.COMPLEXITY -> vibe.complexity
        Macro.MOOD -> vibe.mood
        Macro.SPACE -> vibe.space
    }

    private fun sectionMultiplier(vibe: Vibe, section: Int, m: Macro): Float {
        val o = vibe.arrangement?.sections?.getOrNull(section)?.macroOverrides ?: return 1f
        return when (m) {
            Macro.ENERGY -> o.energy
            Macro.COMPLEXITY -> o.complexity
            Macro.MOOD -> o.mood
            Macro.SPACE -> o.space
        } ?: 1f
    }

    private fun r3(x: Float): Float = (x * 1000f).roundToLong() / 1000f

    // Three decimals; NaN and infinities become the meters' silent floor.
    private fun StringBuilder.num(x: Double): StringBuilder {
        if (!x.isFinite()) return append(BenchMeters.SILENT_DB.toInt())
        val r = (x * 1000.0).roundToLong()
        if (r % 1000L == 0L) return append(r / 1000L)
        return append(r / 1000.0)
    }
}
