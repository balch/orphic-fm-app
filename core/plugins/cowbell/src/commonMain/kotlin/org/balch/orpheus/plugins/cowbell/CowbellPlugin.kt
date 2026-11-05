package org.balch.orpheus.plugins.cowbell

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.COWBELL_URI
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol

enum class CowbellMode { TAP, SHAKE, SYNTH }

/** The resonator body; the ordinals match the C++ per-body table. */
enum class CowbellBody { STRING, SITAR, METAL, SYNTH }

enum class CowbellFill { MARBLE, BEADS, SEEDS, JINGLES }

/** The `kind` a fill's rattle strikes carry on the strike port; kind 0 is the body. */
val CowbellFill.strikeKind: Int get() = ordinal + 1

/** "SHAKE" -> "Shake": the title-case label a port option shows. SYNTH shows as "Hold", set at the MODE port. */
private fun Enum<*>.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/**
 * Cowbell Plugin: a struck resonator voice (the C++ unit does all the audio).
 *
 * `strike` and `gate` are events, not state. Their port setters do nothing and the getters read
 * 0 / false, so the engine's port replay on startup and resync never re-fires a hit or leaves a
 * SYNTH note held. To play, send them through `SynthEngine.setPluginPort`, which forwards once.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class CowbellPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Cowbell",
        author = "Balch"
    )

    companion object {
        const val URI = COWBELL_URI
    }

    private var _level = 0.7f
    // An acoustic cowbell: the metal body at its darkest, ringing on, struck hard.
    private var _body = CowbellBody.METAL.ordinal
    private var _tone = 0f
    private var _ring = 0.48f
    private var _intensity = 0.85f
    private var _octave = 0
    // LOCK starts on: hits land on the beat while a vibe plays, and free when it is stopped.
    private var _grid = true
    private var _live = false
    private var _more = 0f
    private var _gridStrength = 0.7f
    private var _mode = 0
    private var _fill = 0
    private var _bend = 0f
    private var _structureOffset = 0f
    private var _positionOffset = 0f

    private val portDefs = ports(startIndex = 0) {
        controlPort(CowbellSymbol.LEVEL) {
            floatType {
                default = 0.7f
                get { _level }
                set { _level = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(CowbellSymbol.BODY) {
            intType {
                default = CowbellBody.METAL.ordinal; min = 0; max = CowbellBody.entries.lastIndex
                options = CowbellBody.entries.map { it.displayName() }
                get { _body }
                set { _body = it.coerceIn(0, CowbellBody.entries.lastIndex) }
            }
        }

        controlPort(CowbellSymbol.TONE) {
            floatType {
                default = 0f
                get { _tone }
                set { _tone = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(CowbellSymbol.RING) {
            floatType {
                default = 0.48f
                get { _ring }
                set { _ring = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(CowbellSymbol.INTENSITY) {
            floatType {
                default = 0.85f
                get { _intensity }
                set { _intensity = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(CowbellSymbol.OCTAVE) {
            intType {
                default = 0; min = -2; max = 2
                get { _octave }
                set { _octave = it.coerceIn(-2, 2) }
            }
        }

        controlPort(CowbellSymbol.GRID) {
            boolType {
                default = true
                get { _grid }
                set { _grid = it }
            }
        }

        controlPort(CowbellSymbol.GRID_STRENGTH) {
            floatType {
                default = 0.7f
                get { _gridStrength }
                set { _gridStrength = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(CowbellSymbol.MODE) {
            intType {
                default = 0; min = 0; max = CowbellMode.entries.lastIndex
                options = CowbellMode.entries.map { if (it == CowbellMode.SYNTH) "Hold" else it.displayName() }
                get { _mode }
                set { _mode = it.coerceIn(0, CowbellMode.entries.lastIndex) }
            }
        }

        controlPort(CowbellSymbol.FILL) {
            intType {
                default = 0; min = 0; max = CowbellFill.entries.lastIndex
                options = CowbellFill.entries.map { it.displayName() }
                get { _fill }
                set { _fill = it.coerceIn(0, CowbellFill.entries.lastIndex) }
            }
        }

        // A packed strike word, see cowbellStrikeValue. Event only: stores and forwards nothing.
        controlPort(CowbellSymbol.STRIKE) {
            floatType {
                default = 0f; min = 0f; max = 524287f
                excludeFromPresets = true
                get { 0f }
                set { }
            }
        }

        // SYNTH note held. Event only: stores and forwards nothing, so a resync cannot stick it on.
        controlPort(CowbellSymbol.GATE) {
            boolType {
                excludeFromPresets = true
                get { false }
                set { }
            }
        }

        controlPort(CowbellSymbol.BEND) {
            floatType {
                default = 0f; min = -1f; max = 1f
                excludeFromPresets = true
                get { _bend }
                set { _bend = it.coerceIn(-1f, 1f) }
            }
        }

        controlPort(CowbellSymbol.STRUCTURE_OFFSET) {
            floatType {
                default = 0f; min = -0.4f; max = 0.4f
                excludeFromPresets = true
                get { _structureOffset }
                set { _structureOffset = it.coerceIn(-0.4f, 0.4f) }
            }
        }

        controlPort(CowbellSymbol.POSITION_OFFSET) {
            floatType {
                default = 0f; min = -0.45f; max = 0.45f
                excludeFromPresets = true
                get { _positionOffset }
                set { _positionOffset = it.coerceIn(-0.45f, 0.45f) }
            }
        }

        // Held while the DJ cowbell tab is open: the bell then sounds through a paused vibe's mute.
        controlPort(CowbellSymbol.LIVE) {
            boolType {
                excludeFromPresets = true
                get { _live }
                set { _live = it }
            }
        }

        // The DJ cowbell's level past max: boosts the bell and ducks the vibe under it.
        controlPort(CowbellSymbol.MORE) {
            floatType {
                default = 0f; min = 0f; max = 1f
                excludeFromPresets = true
                get { _more }
                set { _more = it.coerceIn(0f, 1f) }
            }
        }
    }

    override val ports: List<Port> = portDefs.controlPorts

    override fun onStart() {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
