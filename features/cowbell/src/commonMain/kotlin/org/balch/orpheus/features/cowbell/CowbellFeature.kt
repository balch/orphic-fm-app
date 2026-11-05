package org.balch.orpheus.features.cowbell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.abs
import org.balch.orpheus.core.audio.AudioHostHold
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.COWBELL_URI
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.plugins.cowbell.CowbellBody
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.plugins.cowbell.cowbellStrikeValue

/** Saved whole (see [FeatureStatePersistence]), except the gesture fields marked transient. */
@Immutable
@Serializable
data class CowbellUiState(
    val level: Float = 0.7f,
    /** The DJ cowbell's boost past max, 0..1: it boosts the bell and ducks the vibe. */
    val more: Float = 0f,
    val body: CowbellBody = CowbellBody.METAL,
    val tone: Float = 0f,
    val ring: Float = 0.48f,
    val intensity: Float = 0.85f,
    val octave: Int = 0,
    val grid: Boolean = true,
    val gridStrength: Float = 0.7f,
    val mode: CowbellMode = CowbellMode.TAP,
    val fill: CowbellFill = CowbellFill.MARBLE,
    /**
     * The phone's tilt, jolts and shakes play the bell. Off by default: the bender bends, and SHAKE's fill takes taps and flings.
     * Never saved here or in a preset: the DJ screen's saved state keeps it for the session.
     */
    @Transient val motion: Boolean = false,
    /** A SYNTH note is held. */
    @Transient val holding: Boolean = false,
    /** The bell's own bend, -1..1. */
    @Transient val bend: Float = 0f,
    /** The device has motion sensors, so tilt can own STRUCTURE instead of the finger. */
    @Transient val motionAvailable: Boolean = false,
) {
    /** The DJ panel's single grid knob, derived from GRID and GRID_STRENGTH so other writers show on it. */
    val lock: Float get() = lockFrom(grid, gridStrength)
}

/** `x` and `y` are 0..1 over the bell, `velocity` is 0..1. */
@Immutable
data class CowbellPanelActions(
    val onBody: (CowbellBody) -> Unit,
    val onTone: (Float) -> Unit,
    val onRing: (Float) -> Unit,
    val onIntensity: (Float) -> Unit,
    val onLevel: (Float) -> Unit,
    val onGridStrength: (Float) -> Unit,
    val onOctave: (Int) -> Unit,
    val onGrid: (Boolean) -> Unit,
    val onMode: (CowbellMode) -> Unit,
    val onFill: (CowbellFill) -> Unit,
    val onStrike: (velocity: Float, x: Float, y: Float) -> Unit,
    val onHold: (x: Float, y: Float) -> Unit,
    val onMove: (x: Float, y: Float) -> Unit,
    val onRelease: () -> Unit,
    val onBend: (Float) -> Unit,
    val onBendDone: () -> Unit,
    /** Lets go of a held note and the bend. Safe to call twice, or with nothing held. */
    val releaseAll: () -> Unit,
    /** Tilt's STRUCTURE offset, -0.4..0.4. Writes only when it moves by more than [TILT_EPSILON]. */
    val onTilt: (structureOffset: Float) -> Unit,
    /** While true and [CowbellUiState.motion] is on, strikes and drags leave STRUCTURE to tilt. */
    val onMotionAvailable: (Boolean) -> Unit,
    /** A fill's rattle strike, already packed by `cowbellStrikeValue`. Sent unchanged. */
    val onFillStrike: (packed: Float) -> Unit,
    /** LOCK 0..1 onto GRID and GRID_STRENGTH. 0 turns the grid off and keeps the strength. */
    val onLock: (Float) -> Unit,
    /** MORE 0..1, the DJ cowbell's boost past max. Orpheus's panels never call it. */
    val onMore: (Float) -> Unit,
    val onMotion: (Boolean) -> Unit = {},
) {
    companion object {
        val EMPTY = CowbellPanelActions(
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {},
        )

        /** A tilt that moves less than this writes nothing. */
        const val TILT_EPSILON = 0.002f
    }
}

private sealed interface CowbellIntent {
    data class Level(val value: Float) : CowbellIntent
    data class More(val value: Float) : CowbellIntent
    data class Body(val value: CowbellBody) : CowbellIntent
    data class Tone(val value: Float) : CowbellIntent
    data class Ring(val value: Float) : CowbellIntent
    data class Intensity(val value: Float) : CowbellIntent
    data class Octave(val value: Int) : CowbellIntent
    data class Grid(val value: Boolean) : CowbellIntent
    data class GridStrength(val value: Float) : CowbellIntent
    data class Mode(val value: CowbellMode) : CowbellIntent
    data class Fill(val value: CowbellFill) : CowbellIntent
    data class Holding(val value: Boolean) : CowbellIntent
    data class Bend(val value: Float) : CowbellIntent
    data class MotionAvailable(val value: Boolean) : CowbellIntent
    data class Motion(val value: Boolean) : CowbellIntent
}

interface CowbellFeature : SynthFeature<CowbellUiState, CowbellPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    /** The bell's mode-change morph, kept in `AppPreferences` rather than on a port. */
    val morphStyle: StateFlow<BellMorphStyle> get() = DefaultMorphStyle

    fun setMorphStyle(style: BellMorphStyle) {}

    /** Turns true once saved knobs land on the ports. Stays false when there was nothing to restore. */
    val stateRestored: StateFlow<Boolean> get() = DefaultStateRestored

    /**
     * The DJ cowbell panel is shown: keeps the bell audible through a paused vibe. Counted, so two
     * callers do not clear it while one remains. Orpheus never arms it: its pause is the master mute.
     */
    fun armLive() {}

    /** The matching call when that panel leaves composition. */
    fun disarmLive() {}

    companion object {
        internal val DefaultMorphStyle: StateFlow<BellMorphStyle> = MutableStateFlow(BellMorphStyle.SHARDS)
        internal val DefaultStateRestored: StateFlow<Boolean> = MutableStateFlow(false)

        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.COWBELL
            override val title = "Cowbell"
            override val markdown = """
                # Cowbell

                A struck bell voice. A bare strike excites a resonator, so every hit rings
                through the body you pick.

                ## Controls
                - **BODY**: The resonator the bell plays through: String, Sitar, Metal or Synth.
                - **TONE**: The spacing of the partials within the body.
                - **RING**: How long the bell rings after a hit.
                - **INTENSITY**: Soft to biting. Brightens the tone and hardens the strike.
                - **LEVEL**: Output level.
                - **OCTAVE**: Shifts the bell up or down by octaves.
                - **GRID / GRID STRENGTH**: Snaps hits to the Pulsar beat grid.
                - **MODE**: Tap strikes once per touch. Hold sustains a note while touched.
                  Shake turns the bell into a shaker: shake the phone and the fill rattles.
                - **FILL**: What rattles in Shake mode: Marble, Beads, Seeds or Jingles.
                - **TILT**: On a phone, tipping it forward or back sweeps the bell's structure,
                  so the finger only picks the strike position.
            """.trimIndent()
            override val portControlKeys = mapOf(
                CowbellSymbol.LEVEL.controlId.key to "Output level",
                CowbellSymbol.BODY.controlId.key to "Resonator body (integer): 0=String, 1=Sitar, 2=Metal, 3=Synth",
                CowbellSymbol.TONE.controlId.key to "Partial spacing within the body",
                CowbellSymbol.RING.controlId.key to "How long the bell rings",
                CowbellSymbol.INTENSITY.controlId.key to "Soft to biting",
                CowbellSymbol.OCTAVE.controlId.key to "Octave shift, -2 to 2",
                CowbellSymbol.GRID.controlId.key to "Snap hits to the Pulsar grid",
                CowbellSymbol.GRID_STRENGTH.controlId.key to "How strongly hits snap to the grid",
                CowbellSymbol.MODE.controlId.key to "Play mode (integer): 0=Tap, 1=Shake, 2=Hold",
                CowbellSymbol.FILL.controlId.key to "Shaker fill (integer): 0=Marble, 1=Beads, 2=Seeds, 3=Jingles",
            )
        }
    }
}

/**
 * ViewModel for the Cowbell panel.
 *
 * Knobs go through [SynthController.controlFlow] so MIDI mapping and UI sync keep working.
 * Gesture ports (strike, gate, bend and the two offsets) are events, not state: they go through
 * [SynthEngine.setPluginPort], which sends each value to C++ once, in call order. A StateFlow would
 * swallow a repeated strike and could apply the offsets after the hit.
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(CowbellFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<CowbellFeature>())
class CowbellViewModel(
    private val synthEngine: SynthEngine,
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
    private val appPreferences: AppPreferencesRepository,
    private val hostHold: AudioHostHold,
    persistence: FeatureStatePersistence,
    restoreStrategy: RestoreStrategy,
) : CowbellFeature {

    private val levelFlow = synthController.controlFlow(CowbellSymbol.LEVEL.controlId)
    private val moreFlow = synthController.controlFlow(CowbellSymbol.MORE.controlId)
    private val bodyFlow = synthController.controlFlow(CowbellSymbol.BODY.controlId)
    private val toneFlow = synthController.controlFlow(CowbellSymbol.TONE.controlId)
    private val ringFlow = synthController.controlFlow(CowbellSymbol.RING.controlId)
    private val intensityFlow = synthController.controlFlow(CowbellSymbol.INTENSITY.controlId)
    private val octaveFlow = synthController.controlFlow(CowbellSymbol.OCTAVE.controlId)
    private val gridFlow = synthController.controlFlow(CowbellSymbol.GRID.controlId)
    private val gridStrengthFlow = synthController.controlFlow(CowbellSymbol.GRID_STRENGTH.controlId)
    private val modeFlow = synthController.controlFlow(CowbellSymbol.MODE.controlId)
    private val fillFlow = synthController.controlFlow(CowbellSymbol.FILL.controlId)

    // UI-only state: a held note and the bend are gestures, not ports the controller owns.
    private val holding = MutableStateFlow(false)
    private val bend = MutableStateFlow(0f)
    private val motionAvailable = MutableStateFlow(false)
    private val motion = MutableStateFlow(false)

    // Panels showing the bell. Composition adds and removes on the main thread.
    private var livePanels = 0

    // Tilt arrives every frame: only a real move reaches the engine. NaN means nothing sent yet.
    private var lastTilt = Float.NaN

    // A look, not a sound: presets, MIDI and the AI never carry it.
    private val morph = MutableStateFlow(BellMorphStyle.SHARDS)
    override val morphStyle: StateFlow<BellMorphStyle> = morph.asStateFlow()
    private val restored = MutableStateFlow(false)
    override val stateRestored: StateFlow<Boolean> = restored.asStateFlow()
    private val prefsScope: CoroutineScope = scope
    private val io = dispatcherProvider.io

    override val actions = CowbellPanelActions(
        onBody = bodyFlow.enumSetter(),
        onTone = toneFlow.floatSetter(),
        onRing = ringFlow.floatSetter(),
        onIntensity = intensityFlow.floatSetter(),
        onLevel = levelFlow.floatSetter(),
        onGridStrength = gridStrengthFlow.floatSetter(),
        onOctave = octaveFlow.intSetter(),
        onGrid = gridFlow.boolSetter(),
        onMode = modeFlow.enumSetter(),
        onFill = fillFlow.enumSetter(),
        onStrike = ::strike,
        onHold = ::hold,
        onMove = ::move,
        onRelease = ::release,
        onBend = ::setBend,
        onBendDone = ::bendDone,
        releaseAll = ::releaseAll,
        onTilt = ::tilt,
        onMotionAvailable = ::setMotionAvailable,
        onFillStrike = ::fillStrike,
        onLock = ::setLock,
        onMore = moreFlow.floatSetter(),
        onMotion = ::setMotion,
    )

    private val controlIntents = merge(
        levelFlow.map { CowbellIntent.Level(it.asFloat()) },
        moreFlow.map { CowbellIntent.More(it.asFloat()) },
        bodyFlow.map { CowbellIntent.Body(it.body()) },
        toneFlow.map { CowbellIntent.Tone(it.asFloat()) },
        ringFlow.map { CowbellIntent.Ring(it.asFloat()) },
        intensityFlow.map { CowbellIntent.Intensity(it.asFloat()) },
        octaveFlow.map { CowbellIntent.Octave(it.asInt().coerceIn(OCTAVE_MIN, OCTAVE_MAX)) },
        gridFlow.map { CowbellIntent.Grid(it.asBoolean()) },
        gridStrengthFlow.map { CowbellIntent.GridStrength(it.asFloat()) },
        modeFlow.map { CowbellIntent.Mode(it.mode()) },
        fillFlow.map { CowbellIntent.Fill(CowbellFill.entries[it.asInt().coerceIn(0, CowbellFill.entries.lastIndex)]) },
        holding.map { CowbellIntent.Holding(it) },
        bend.map { CowbellIntent.Bend(it) },
        motionAvailable.map { CowbellIntent.MotionAvailable(it) },
        motion.map { CowbellIntent.Motion(it) },
    )

    override val stateFlow: StateFlow<CowbellUiState> =
        controlIntents
            .scan(CowbellUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = SynthFeature.sharingStrategy,
                initialValue = CowbellUiState(),
            )

    init {
        // Covers test and scope teardown only: this scope is never cancelled in production, so the
        // panel's dispose is what really lets go of a held note or a bend.
        scope.onCleared { releaseAll() }
        scope.launch(dispatcherProvider.io) {
            // A tap that lands before the load wins over the saved style.
            val saved = appPreferences.load()
            morph.compareAndSet(BellMorphStyle.SHARDS, BellMorphStyle.fromName(saved.bellMorph))
        }
        persistence.bind(
            stateFlow = stateFlow,
            serializer = CowbellUiState.serializer(),
            reader = { it.lastCowbellJson },
            writer = { prefs, json -> prefs.copy(lastCowbellJson = json) },
            restoreStrategy = restoreStrategy,
            onRestore = ::restore,
        )
    }

    private fun restore(saved: CowbellUiState) {
        levelFlow.value = FloatValue(saved.level)
        moreFlow.value = FloatValue(saved.more)
        toneFlow.value = FloatValue(saved.tone)
        bodyFlow.value = IntValue(saved.body.ordinal)
        ringFlow.value = FloatValue(saved.ring)
        intensityFlow.value = FloatValue(saved.intensity)
        octaveFlow.value = IntValue(saved.octave.coerceIn(OCTAVE_MIN, OCTAVE_MAX))
        gridFlow.value = BoolValue(saved.grid)
        gridStrengthFlow.value = FloatValue(saved.gridStrength)
        modeFlow.value = IntValue(saved.mode.ordinal)
        fillFlow.value = IntValue(saved.fill.ordinal)
        restored.value = true
    }

    // The hold keeps a paused app's audio host running, or Android parks it and nothing renders.
    override fun armLive() {
        if (livePanels++ == 0) {
            hostHold.acquire()
            send(CowbellSymbol.LIVE, BoolValue(true))
        }
    }

    override fun disarmLive() {
        if (livePanels == 0) return
        if (--livePanels == 0) {
            send(CowbellSymbol.LIVE, BoolValue(false))
            hostHold.release()
        }
    }

    private fun setLock(lock: Float) {
        val (grid, strength) = lockToGrid(if (lock.isNaN()) 0f else lock)
        if (strength != null) actions.onGridStrength(strength)
        // A drag repeats the same GRID value; write it only when it changes.
        if (grid != gridFlow.value.asBoolean()) actions.onGrid(grid)
    }

    override fun setMorphStyle(style: BellMorphStyle) {
        morph.value = style
        prefsScope.launch(io) { appPreferences.update { it.copy(bellMorph = style.name) } }
    }

    private fun PortValue.body(): CowbellBody =
        CowbellBody.entries[asInt().coerceIn(0, CowbellBody.entries.lastIndex)]

    private fun PortValue.mode(): CowbellMode =
        CowbellMode.entries[asInt().coerceIn(0, CowbellMode.entries.lastIndex)]

    private fun reduce(state: CowbellUiState, intent: CowbellIntent): CowbellUiState =
        when (intent) {
            is CowbellIntent.Level -> state.copy(level = intent.value)
            is CowbellIntent.More -> state.copy(more = intent.value)
            is CowbellIntent.Body -> state.copy(body = intent.value)
            is CowbellIntent.Tone -> state.copy(tone = intent.value)
            is CowbellIntent.Ring -> state.copy(ring = intent.value)
            is CowbellIntent.Intensity -> state.copy(intensity = intent.value)
            is CowbellIntent.Octave -> state.copy(octave = intent.value)
            is CowbellIntent.Grid -> state.copy(grid = intent.value)
            is CowbellIntent.GridStrength -> state.copy(gridStrength = intent.value)
            is CowbellIntent.Mode -> state.copy(mode = intent.value)
            is CowbellIntent.Fill -> state.copy(fill = intent.value)
            is CowbellIntent.Holding -> state.copy(holding = intent.value)
            is CowbellIntent.Bend -> state.copy(bend = intent.value)
            is CowbellIntent.MotionAvailable -> state.copy(motionAvailable = intent.value)
            is CowbellIntent.Motion -> state.copy(motion = intent.value)
        }

    // Gestures

    private fun strike(velocity: Float, x: Float, y: Float) {
        writeOffsets(x, y)
        send(CowbellSymbol.STRIKE, FloatValue(cowbellStrikeValue(velocity)))
    }

    private fun hold(x: Float, y: Float) {
        writeOffsets(x, y)
        // Only SYNTH holds. A SHAKE press kicks the fill and never reaches onHold or onStrike;
        // any other mode that gets here strikes once, like TAP.
        if (modeFlow.value.mode() == CowbellMode.SYNTH) {
            send(CowbellSymbol.GATE, BoolValue(true))
            holding.value = true
        } else {
            send(CowbellSymbol.STRIKE, FloatValue(cowbellStrikeValue(HOLD_STRIKE_VELOCITY)))
        }
    }

    private fun move(x: Float, y: Float) = writeOffsets(x, y)

    /** Ungates a held note. The offsets stay where they are, so the ring-out does not jump. */
    private fun release() {
        if (!holding.value) return
        send(CowbellSymbol.GATE, BoolValue(false))
        holding.value = false
    }

    private fun setBend(value: Float) {
        val clamped = if (value.isNaN()) 0f else value.coerceIn(-1f, 1f)
        send(CowbellSymbol.BEND, FloatValue(clamped))
        bend.value = clamped
    }

    private fun bendDone() = setBend(0f)

    private fun tilt(structureOffset: Float) {
        if (structureOffset.isNaN()) return
        val value = structureOffset.coerceIn(-STRUCTURE_LIMIT, STRUCTURE_LIMIT)
        if (!lastTilt.isNaN() && abs(value - lastTilt) <= CowbellPanelActions.TILT_EPSILON) return
        lastTilt = value
        send(CowbellSymbol.STRUCTURE_OFFSET, FloatValue(value))
    }

    private fun setMotionAvailable(available: Boolean) {
        if (motionAvailable.value == available) return
        // The finger may move STRUCTURE while tilt is away, so tilt must write again when it returns.
        lastTilt = Float.NaN
        motionAvailable.value = available
    }

    private fun setMotion(on: Boolean) {
        if (motion.value == on) return
        lastTilt = Float.NaN
        motion.value = on
        // The last tilt must not stay on STRUCTURE once the finger owns it.
        if (motionAvailable.value && !tiltOwnsStructure()) send(CowbellSymbol.STRUCTURE_OFFSET, FloatValue(0f))
    }

    /** Tilt writes STRUCTURE with sensors and [CowbellUiState.motion] on; else the finger does. */
    private fun tiltOwnsStructure(): Boolean = motionAvailable.value && motion.value

    private fun fillStrike(packed: Float) = send(CowbellSymbol.STRIKE, FloatValue(packed))

    private fun releaseAll() {
        release()
        if (bend.value != 0f) setBend(0f)
    }

    private fun writeOffsets(x: Float, y: Float) {
        send(CowbellSymbol.POSITION_OFFSET, FloatValue((x.unit() - 0.5f) * POSITION_RANGE))
        if (!tiltOwnsStructure()) {
            send(CowbellSymbol.STRUCTURE_OFFSET, FloatValue((0.5f - y.unit()) * STRUCTURE_RANGE))
        }
    }

    private fun Float.unit(): Float = if (isNaN()) 0.5f else coerceIn(0f, 1f)

    private fun send(symbol: CowbellSymbol, value: PortValue) {
        synthEngine.setPluginPort(COWBELL_URI, symbol.symbol, value)
    }

    companion object {
        private const val OCTAVE_MIN = -2
        private const val OCTAVE_MAX = 2

        // Full travel of the offsets over the bell: +-0.45 position, +-0.4 structure.
        private const val POSITION_RANGE = 0.9f
        private const val STRUCTURE_RANGE = 0.8f
        private const val STRUCTURE_LIMIT = 0.4f

        /** A hold has no pointer speed, so a TAP-style hold strikes at a firm, fixed velocity. */
        internal const val HOLD_STRIKE_VELOCITY = 0.8f

        fun previewFeature(state: CowbellUiState = CowbellUiState()): CowbellFeature =
            object : CowbellFeature {
                override val stateFlow: StateFlow<CowbellUiState> = MutableStateFlow(state)
                override val actions: CowbellPanelActions = CowbellPanelActions.EMPTY
            }

        @Composable
        fun feature(): CowbellFeature =
            synthFeature<CowbellFeature>()
    }
}
