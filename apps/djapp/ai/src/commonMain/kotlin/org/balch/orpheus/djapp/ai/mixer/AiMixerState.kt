package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToLong
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.capture
import org.balch.orpheus.features.pulsar.bench.effective
import org.balch.orpheus.features.pulsar.bench.macroView
import org.balch.orpheus.features.pulsar.bench.sectionScopeAvailable
import org.balch.orpheus.features.pulsar.bench.vibeDefault
import org.balch.orpheus.features.pulsar.models.Vibe

@Immutable
data class MixerTrack(
    val index: Int,
    val name: String,
    val engine: String,
    val levelDb: Float,
    val pan: Float,
    val filePan: Float,
    val reverb: Float,
    val delay: Float,
    val muted: Boolean,
    val soloed: Boolean,
    val silenced: Boolean,
)

@Immutable
data class MixerMacro(
    val macro: Macro,
    val value: Float,
    val resetValue: Float,
    val changed: Boolean,
    val note: String,
)

@Immutable
data class MixerCapture(
    val ok: Boolean,
    val message: String,
    val file: String?,
    val rows: Int,
    val stale: Boolean?,
    val appliedToAiVibe: Boolean,
)

@Immutable
data class AiMixerUiState(
    val vibeName: String = "",
    val sections: List<String> = emptyList(),
    val section: Int = -1,
    val loopSection: Int? = null,
    val sectionScopeAvailable: Boolean = false,
    val scope: Scope = Scope.VIBE,
    val tracks: List<MixerTrack> = emptyList(),
    val macros: List<MixerMacro> = emptyList(),
    val changes: List<CaptureRow> = emptyList(),
    val capture: MixerCapture? = null,
    val capturing: Boolean = false,
    val notice: String? = null,
) {
    val sectionScope: Boolean get() = scope == Scope.SECTION && sectionScopeAvailable && section >= 0
}

/** The bench page's state view, for the panel. [knobs] are the app's macro knobs. */
fun mixerState(
    vibe: Vibe,
    section: Int,
    layers: BenchLayers,
    scope: Scope,
    muted: Set<Int>,
    soloed: Set<Int>,
    loopSection: Int?,
    knobs: Map<Macro, Float>,
): AiMixerUiState {
    val sectionScopeAvailable = vibe.sectionScopeAvailable()
    val inSection = scope == Scope.SECTION && sectionScopeAvailable && section >= 0
    val tracks = vibe.tracks.indices.map { t ->
        val track = vibe.tracks[t]
        val e = layers.effective(vibe, section, t, knobs[Macro.ENERGY] ?: vibe.energy)
        MixerTrack(
            index = t,
            name = BenchMeters.CHANNEL_NAMES.getOrElse(t) { "T$t" },
            engine = "${track.engineEdm.engineId.name}/${track.engineSpace.engineId.name}",
            levelDb = e.levelDb,
            pan = e.pan,
            filePan = track.pan,
            reverb = e.reverbSend,
            delay = e.delaySend,
            muted = t in muted,
            soloed = t in soloed,
            silenced = t in muted || (soloed.isNotEmpty() && t !in soloed),
        )
    }
    val macros = Macro.entries.map { m ->
        val mv = layers.macroView(vibe, section, m, knobs[m] ?: m.vibeDefault(vibe))
        val changed = if (inSection) mv.heardMoved else mv.knobMoved
        MixerMacro(
            macro = m,
            value = if (inSection) mv.heard else mv.knob,
            resetValue = if (inSection) min(1f, mv.file * mv.mult) else mv.file,
            changed = changed,
            note = when {
                inSection -> "heard here"
                abs(mv.mult - 1f) > 1e-3f -> "heard ${f2(mv.heard)} (x${f2(mv.mult)})"
                !changed && abs(mv.live - mv.file) > 1e-3f -> "app knob; file ${f2(mv.file)}"
                else -> ""
            },
        )
    }
    return AiMixerUiState(
        vibeName = vibe.name,
        sections = vibe.arrangement?.sections.orEmpty().map { it.name },
        section = section,
        loopSection = loopSection,
        sectionScopeAvailable = sectionScopeAvailable,
        scope = scope,
        tracks = tracks,
        macros = macros,
        changes = capture(vibe, layers),
    )
}

enum class ChangeKind { HEADER, OLD, NEW, FLAG }

data class ChangeLine(val kind: ChangeKind, val text: String)

private val FIELD = mapOf(
    CaptureField.VOLUME to "volume (edm)",
    CaptureField.VOLUME_SPACE to "volume (space)",
    CaptureField.PAN to "pan",
    CaptureField.REVERB_SEND to "reverbSend",
    CaptureField.REVERB_SEND_SPACE to "reverbSend (space)",
    CaptureField.DELAY_SEND to "delaySend",
    CaptureField.DELAY_SEND_SPACE to "delaySend (space)",
)

private fun fieldName(r: CaptureRow): String {
    val macro = r.macro?.name?.lowercase().orEmpty()
    return when (r.field) {
        CaptureField.MACRO -> macro + if (r.scope == Scope.SECTION) " multiplier" else ""
        CaptureField.MACRO_RAMP -> "$macro rampTo"
        else -> FIELD.getValue(r.field)
    }
}

/** Display text for one changes row, as bench.js `renderSide` writes it: header, "- old" and "+ new" lines, and flags. */
fun changeLines(rows: List<CaptureRow>): List<ChangeLine> {
    val out = mutableListOf<ChangeLine>()
    var last = ""
    var spaceWarn = false
    for (r in rows) {
        val where = if (r.scope == Scope.VIBE) "vibe" else "section \"${r.sectionName}\""
        val head = "// $where" + (r.track?.let { " · ${BenchMeters.CHANNEL_NAMES.getOrElse(it) { "T$it" }}" } ?: "")
        if (head != last) {
            out += ChangeLine(ChangeKind.HEADER, head)
            last = head
        }
        val f = fieldName(r)
        out += ChangeLine(ChangeKind.OLD, "- $f = ${fixed(r.old, 3)}")
        var add = "+ $f = ${fixed(r.new, 3)}"
        if ((r.field == CaptureField.VOLUME || r.field == CaptureField.VOLUME_SPACE) && r.old > 0f && r.new > 0f) {
            val d = 20.0 * log10((r.new / r.old).toDouble())
            add += "  (" + (if (d > 0) "+" else "") + fixed(d.toFloat(), 1) + " dB)"
        }
        out += ChangeLine(ChangeKind.NEW, add)
        if (r.clampedDb > 0f) {
            out += ChangeLine(
                ChangeKind.FLAG,
                "clamped: written value is ${fixed(r.clampedDb, 1)} dB quieter than heard; cut the other tracks by that much instead",
            )
        }
        if (r.scope == Scope.SECTION && r.macro == Macro.SPACE) spaceWarn = true
    }
    if (spaceWarn) {
        out += ChangeLine(ChangeKind.FLAG, "A section Space change also scales that section's sends (Kotlin applies Space to sends).")
    }
    return out
}

internal fun f2(x: Float) = fixed(x, 2)

/** JS `toFixed` for the small ranges shown here; multi-platform, so no String.format. */
internal fun fixed(x: Float, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(x.toDouble()) * scale).roundToLong()
    val neg = x < 0f && scaled != 0L
    val whole = scaled / scale
    val frac = (scaled % scale).toString().padStart(decimals, '0')
    return (if (neg) "-" else "") + whole + "." + frac
}
