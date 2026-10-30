package org.balch.orpheus.ui.widgets.patch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.balch.orpheus.core.audio.patch.PatchFamily
import org.balch.orpheus.core.audio.patch.PatchSource

/** Each family is one hue; its outputs are shades of it, so a cable's colour names its source. */
object PatchColors {
    val lfo = Color(0xFF4FD8FF)
    val waves = Color(0xFF5FFFB0)
    val flux = Color(0xFFFFB347)
    val fm = Color(0xFFFF6FB5)

    /** Knob inputs, the bridge switch and its bar. */
    val input = Color(0xFFE070E0)

    fun family(family: PatchFamily): Color = when (family) {
        PatchFamily.LFO -> lfo
        PatchFamily.WAVES -> waves
        PatchFamily.FLUX -> flux
        PatchFamily.FM -> fm
    }

    private val members: Map<PatchFamily, List<PatchSource>> = PatchSource.entries.groupBy { it.family }

    /** The family hue, lightened for the first source and darkened toward the last. */
    fun source(source: PatchSource): Color {
        val hue = family(source.family)
        val list = members.getValue(source.family)
        if (list.size < 2) return hue
        val t = list.indexOf(source).toFloat() / (list.size - 1)
        val shade = LIGHTEST + (DARKEST - LIGHTEST) * t
        return if (shade >= 0f) lerp(hue, Color.White, shade) else lerp(hue, Color.Black, -shade)
    }

    private const val LIGHTEST = 0.45f
    private const val DARKEST = -0.3f
}
