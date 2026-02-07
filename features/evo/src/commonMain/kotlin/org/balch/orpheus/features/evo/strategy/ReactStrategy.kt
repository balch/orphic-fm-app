package org.balch.orpheus.features.evo.strategy

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.evo.AudioEvolutionStrategy
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * React Strategy - Audio & Input Reactive Evolution
 *
 * Modulates parameters in response to:
 * - Audio peaks (when sound gets louder/quieter)
 * - User input (when user adjusts controls)
 *
 * SENS (Knob 1): Sensitivity - how responsive to audio levels
 * FOLLOW (Knob 2): Follow amount - how strongly to track user changes
 */
@OptIn(ExperimentalTime::class)
@Inject
@ContributesIntoSet(AppScope::class)
class ReactStrategy(
    private val synthController: SynthController,
    private val synthEngine: SynthEngine
) : AudioEvolutionStrategy {

    private val log = logging("ReactStrategy")

    override val id = "react"
    override val name = "React"
    override val color = OrpheusColors.strategyOrange // Orange - reactive/dynamic
    override val knob1Label = "SENS"
    override val knob2Label = "FOLLOW"

    private var sensitivityKnob = 0.5f
    private var followKnob = 0.5f

    // Historical tracking for audio reactivity
    private var peakHistory = FloatArray(10) { 0f }
    private var historyIndex = 0
    private var lastPeak = 0f
    private var peakTrend = 0f  // Positive = getting louder, negative = quieter

    // User input tracking
    private var lastUserChange: UserChange? = null
    private var userChangeDecay = 0f

    // Coroutine scope for monitoring flows
    private var monitorScope: CoroutineScope? = null
    private var monitorJob: Job? = null

    // PluginControlId keys for matching incoming control events
    private val driveKey = DistortionSymbol.DRIVE.controlId.key
    private val delayMixKey = DelaySymbol.MIX.controlId.key
    private val delayFeedbackKey = DelaySymbol.FEEDBACK.controlId.key
    private val vibratoKey = VoiceSymbol.VIBRATO.controlId.key
    private val quadPitchPrefix = VoiceSymbol.QUAD_PITCH_0.controlId.uri

    data class UserChange(
        val controlId: String,
        val direction: Float,  // Positive = increasing, negative = decreasing
        val timestamp: Long
    )

    override fun setKnob1(value: Float) {
        sensitivityKnob = value.coerceIn(0f, 1f)
        log.debug { "SENS set to $sensitivityKnob" }
    }

    override fun setKnob2(value: Float) {
        followKnob = value.coerceIn(0f, 1f)
        log.debug { "FOLLOW set to $followKnob" }
    }

    private fun emit(id: PluginControlId, value: Float) {
        synthController.setPluginControl(id, FloatValue(value.coerceIn(0f, 1f)), ControlEventOrigin.EVO)
    }

    @OptIn(FlowPreview::class)
    override fun onActivate() {
        log.debug { "Activated (SENS=$sensitivityKnob, FOLLOW=$followKnob)" }

        // Reset state
        peakHistory.fill(0f)
        historyIndex = 0
        lastPeak = 0f
        peakTrend = 0f
        lastUserChange = null
        userChangeDecay = 0f

        // Start monitoring user input
        monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        monitorJob = monitorScope?.launch {
            synthController.onControlChange
                .filter { it.origin == ControlEventOrigin.UI || it.origin == ControlEventOrigin.MIDI }
                .debounce(100.milliseconds)
                .collect { event ->
                    // Track direction of change by comparing to current engine value
                    val currentValue = when (event.controlId) {
                        driveKey -> synthEngine.getDrive()
                        delayMixKey -> synthEngine.getDelayMix()
                        delayFeedbackKey -> synthEngine.getDelayFeedback()
                        vibratoKey -> synthEngine.getVibrato()
                        else -> 0.5f
                    }
                    val direction = event.value - currentValue

                    lastUserChange = UserChange(
                        controlId = event.controlId,
                        direction = direction,
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    userChangeDecay = 1f

                    log.debug { "User adjusted ${event.controlId}: ${if (direction > 0) "↑" else "↓"}" }
                }
        }
    }

    override fun onDeactivate() {
        log.debug { "Deactivated" }
        monitorJob?.cancel()
        monitorScope?.cancel()
        monitorScope = null
        monitorJob = null
    }

    override suspend fun evolve(engine: SynthEngine) {
        // === 1. Audio Reactivity ===
        val currentPeak = engine.getPeak()

        // Update history and compute trend
        peakHistory[historyIndex] = currentPeak
        historyIndex = (historyIndex + 1) % peakHistory.size

        val avgPeak = peakHistory.average().toFloat()
        val newTrend = currentPeak - lastPeak
        peakTrend = peakTrend * 0.7f + newTrend * 0.3f  // Smoothed trend
        lastPeak = currentPeak

        // Sensitivity determines how much we react to peaks
        val sensitivity = sensitivityKnob
        val peakInfluence = (currentPeak - avgPeak) * sensitivity * 2f
        val trendInfluence = peakTrend * sensitivity * 5f

        // === 2. User Follow Reactivity ===
        // Decay the user influence over time
        userChangeDecay *= 0.95f
        if (userChangeDecay < 0.01f) userChangeDecay = 0f

        val userInfluence = lastUserChange?.let { change ->
            change.direction * userChangeDecay * followKnob
        } ?: 0f

        // === 3. Apply Reactive Modulation ===

        // When peak is high → increase Drive slightly
        if (abs(peakInfluence) > 0.01f) {
            val currentDrive = engine.getDrive()
            val targetDrive = (currentDrive + peakInfluence * 0.1f).coerceIn(0f, 0.7f)
            emit(DistortionSymbol.DRIVE.controlId, currentDrive + (targetDrive - currentDrive) * 0.2f)
        }

        // When trend is rising → increase Delay Mix for "bloom" effect
        if (trendInfluence > 0.02f) {
            val currentDelay = engine.getDelayMix()
            val targetDelay = (currentDelay + trendInfluence * 0.3f).coerceIn(0f, 0.8f)
            emit(DelaySymbol.MIX.controlId, currentDelay + (targetDelay - currentDelay) * 0.1f)
        }

        // When trend is falling → reduce Delay feedback for "tightening"
        if (trendInfluence < -0.02f) {
            val currentFeedback = engine.getDelayFeedback()
            val targetFeedback = (currentFeedback + trendInfluence * 0.2f).coerceIn(0.1f, 0.85f)
            emit(DelaySymbol.FEEDBACK.controlId, currentFeedback + (targetFeedback - currentFeedback) * 0.1f)
        }

        // User follow: if user pushed a parameter up, gently push related params
        if (userInfluence != 0f && lastUserChange != null) {
            applyUserFollow(engine, lastUserChange!!, userInfluence)
        }

        // Vibrato follows peak intensity
        val vibratoTarget = 0.1f + (avgPeak * sensitivity * 0.4f)
        val currentVibrato = engine.getVibrato()
        emit(VoiceSymbol.VIBRATO.controlId, currentVibrato + (vibratoTarget - currentVibrato) * 0.05f)

        // Log occasionally
        if (historyIndex == 0) {
            log.debug {
                "React: peak=$currentPeak, trend=$peakTrend, userDecay=$userChangeDecay"
            }
        }
    }

    private fun applyUserFollow(engine: SynthEngine, change: UserChange, influence: Float) {
        // When user changes one parameter, subtly adjust related ones
        when {
            change.controlId == driveKey -> {
                // User increased drive → increase distortion mix slightly
                val currentMix = engine.getDistortionMix()
                emit(DistortionSymbol.MIX.controlId, (currentMix + influence * 0.3f).coerceIn(0f, 1f))
            }
            change.controlId == delayMixKey -> {
                // User increased delay → increase feedback slightly
                val currentFb = engine.getDelayFeedback()
                emit(DelaySymbol.FEEDBACK.controlId, (currentFb + influence * 0.2f).coerceIn(0f, 0.85f))
            }
            change.controlId.startsWith(quadPitchPrefix) && "quad_pitch" in change.controlId -> {
                // User changed quad pitch → adjust vibrato
                val currentVibrato = engine.getVibrato()
                emit(VoiceSymbol.VIBRATO.controlId, (currentVibrato + abs(influence) * 0.1f).coerceIn(0f, 0.5f))
            }
            change.controlId == vibratoKey -> {
                // User increased vibrato → increase voice coupling
                val currentCoupling = engine.getVoiceCoupling()
                emit(VoiceSymbol.COUPLING.controlId, (currentCoupling + influence * 0.15f).coerceIn(0f, 0.5f))
            }
        }
    }
}
