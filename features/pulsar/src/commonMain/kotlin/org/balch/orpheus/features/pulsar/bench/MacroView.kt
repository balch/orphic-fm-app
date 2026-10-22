package org.balch.orpheus.features.pulsar.bench

import org.balch.orpheus.features.pulsar.models.Vibe

/** One macro as the bench shows it in [section]; what BenchJson's `macros` block holds. */
data class MacroView(
    val file: Float,
    val live: Float,
    val knob: Float,
    val mult: Float,
    val heard: Float,
    val knobMoved: Boolean,
    val heardMoved: Boolean,
)

/** [live] is the app's knob for [m]; it plays wherever the layers have not moved the macro. */
fun BenchLayers.macroView(vibe: Vibe, section: Int, m: Macro, live: Float): MacroView {
    val file = m.vibeDefault(vibe)
    val mult = vibe.arrangement?.sections?.getOrNull(section)?.macroOverrides.multiplier(m) ?: 1f
    val knob = this.vibe.macros[m] ?: live
    val sectionHeard = sections[section]?.macrosHeard?.get(m)
    return MacroView(
        file = file,
        live = live,
        knob = knob,
        mult = mult,
        heard = sectionHeard ?: (knob * mult).coerceIn(0f, 1f),
        knobMoved = m in this.vibe.macros,
        heardMoved = sectionHeard != null,
    )
}
