package org.balch.orpheus.plugins.perstringbender

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import kotlin.math.absoluteValue
import kotlin.math.pow

/**
 * DSP Plugin for per-string pitch bending.
 *
 * Pure state container — C++ handles all audio processing.
 * Keeps computation logic for bend curves and forwards results via `audioEngine.setPort()`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class PerStringBenderPlugin(
    private val audioEngine: AudioEngine,
    private val resonatorPlugin: ResonatorPlugin
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Per-String Bender",
        author = "Balch"
    )

    private val audioPorts = ports {
        // Voice Bend Outputs (0-7)
        for (i in 0..7) {
            audioPort { index = i; symbol = "bend_$i"; name = "Bend Voice $i"; isInput = false }
        }
        // Voice Mix Outputs (8-15)
        for (i in 0..7) {
            audioPort { index = 8 + i; symbol = "mix_$i"; name = "Mix Voice $i"; isInput = false }
        }
        // Audio Output (16)
        audioPort { index = 16; symbol = "audio_out"; name = "Audio Output"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports

    companion object {
        const val URI = "org.balch.orpheus.plugins.perstringbender"
        private const val NUM_STRINGS = 4
        private const val MAX_BEND_SEMITONES = 12.0 // 1 octave bend range per string
        private const val SPRING_DURATION_MS = 800
    }

    // Per-string bend state
    private data class StringBenderState(
        var bendAmount: Float = 0f,
        var rawDeflection: Float = 0f,
        var voiceMix: Float = 0.5f,
        var isActive: Boolean = false,
        var wasActive: Boolean = false,
        var triggeredVoice: Boolean = false,
        var baseFrequency: Float = 440f
    )

    private val stringStates = Array(NUM_STRINGS) { StringBenderState() }

    // State for gesture tracking
    private data class GestureState(
        var lastY: Float = 0.5f,
        var lastX: Float = 0f,
        var lastTime: Long = 0L,
        var slideActive: Boolean = false
    )
    private val gestureStates = Array(NUM_STRINGS) { GestureState() }

    // Reactive flow for UI spring positions
    private val _springPositionFlow = MutableStateFlow(FloatArray(NUM_STRINGS))
    val springPositionFlow: StateFlow<FloatArray> = _springPositionFlow.asStateFlow()

    override val audioUnits: List<AudioUnit> = emptyList()

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    private fun applyDirectionForString(stringIndex: Int, rawDeflection: Float): Float {
        return if (stringIndex < 2) rawDeflection else -rawDeflection
    }

    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float, voiceIsPlaying: Boolean = true): Boolean {
        if (stringIndex !in 0 until NUM_STRINGS) return false

        val state = stringStates[stringIndex]

        // Store raw and apply direction
        state.rawDeflection = bendAmount.coerceIn(-1f, 1f)
        state.bendAmount = applyDirectionForString(stringIndex, state.rawDeflection)
        state.voiceMix = voiceMix.coerceIn(0f, 1f)
        state.isActive = true

        // Calculate pitch bend
        val normalizedBend = state.bendAmount
        val tensionCurve = normalizedBend.pow(3)
        val semitones = tensionCurve * MAX_BEND_SEMITONES
        val frequencyMultiplier = 2.0.pow(semitones / 12.0) - 1.0

        // Apply to both voices in the duo via C++
        val voiceA = stringIndex * 2
        val voiceB = stringIndex * 2 + 1

        audioEngine.setPort(URI, "voice_bend_$voiceA", frequencyMultiplier.toFloat())
        audioEngine.setPort(URI, "voice_bend_$voiceB", frequencyMultiplier.toFloat())

        // Calculate voice mix volumes
        val voiceAVolume = when {
            voiceMix <= 0.25f -> 1.0f
            voiceMix >= 0.75f -> 1.0f - (voiceMix - 0.75f) / 0.25f
            else -> 1.0f
        }
        val voiceBVolume = when {
            voiceMix <= 0.25f -> voiceMix / 0.25f
            voiceMix >= 0.75f -> 1.0f
            else -> 1.0f
        }

        audioEngine.setPort(URI, "voice_mix_$voiceA", voiceAVolume)
        audioEngine.setPort(URI, "voice_mix_$voiceB", voiceBVolume)

        // Gesture tracking
        val gesture = gestureStates[stringIndex]
        val currentTime = Clock.System.now().toEpochMilliseconds()

        gesture.lastY = voiceMix
        gesture.lastX = bendAmount
        gesture.lastTime = currentTime

        // Trigger envelope
        val tension = normalizedBend.absoluteValue
        val isActive = tension > 0.05f
        if (isActive && !state.wasActive) {
            state.wasActive = true

            if (!voiceIsPlaying) {
                state.triggeredVoice = true
                return true
            }
        }

        return false
    }

    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float): Boolean {
        return setStringBend(stringIndex, bendAmount, voiceMix, voiceIsPlaying = false)
    }

    fun releaseString(stringIndex: Int): Pair<Int, Boolean> {
        if (stringIndex !in 0 until NUM_STRINGS) return Pair(0, false)

        val state = stringStates[stringIndex]
        if (!state.isActive) return Pair(0, false)

        state.isActive = false
        val shouldReleaseVoice = state.triggeredVoice
        state.triggeredVoice = false

        val pullDistance = state.rawDeflection.absoluteValue
        val gesture = gestureStates[stringIndex]

        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaTime = (currentTime - gesture.lastTime).coerceAtLeast(1L)
        val releaseVelocity = pullDistance / deltaTime * 1000f

        // Reset outputs via C++
        val voiceA = stringIndex * 2
        val voiceB = stringIndex * 2 + 1
        audioEngine.setPort(URI, "voice_bend_$voiceA", 0f)
        audioEngine.setPort(URI, "voice_bend_$voiceB", 0f)
        audioEngine.setPort(URI, "voice_mix_$voiceA", 1f)
        audioEngine.setPort(URI, "voice_mix_$voiceB", 1f)

        if (state.wasActive) {
            state.wasActive = false
        }

        // Pluck logic
        val pluckThreshold = 1.5f
        if (releaseVelocity > pluckThreshold && pullDistance > 0.15f) {
            val velocityPitchMod = 1.0 + (releaseVelocity - pluckThreshold) * 0.1
            val slideBend = slideBarPosition
            val tensionCurve = slideBend * (1.0 + slideBend.absoluteValue * 0.5)
            val slideSemitones = tensionCurve * MAX_BEND_SEMITONES * 0.5
            val slideMultiplier = 2.0.pow(slideSemitones / 12.0)

            val strumFreq = state.baseFrequency * velocityPitchMod * slideMultiplier
            resonatorPlugin.strum(strumFreq.toFloat())
        }

        state.bendAmount = 0f
        state.rawDeflection = 0f
        state.voiceMix = 0.5f
        gesture.lastY = 0.5f
        gesture.lastX = 0f
        gesture.lastTime = currentTime

        val springDuration = (SPRING_DURATION_MS * pullDistance.coerceIn(0.3f, 1f)).toInt()
        return Pair(springDuration, shouldReleaseVoice)
    }

    // Accessors
    fun getSpringPosition(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringStates[stringIndex].rawDeflection
    }

    fun isStringActive(stringIndex: Int): Boolean {
        if (stringIndex !in 0 until NUM_STRINGS) return false
        return stringStates[stringIndex].isActive
    }

    fun getStringBend(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringStates[stringIndex].bendAmount
    }

    fun getRawDeflection(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringStates[stringIndex].rawDeflection
    }

    fun setStringFrequency(stringIndex: Int, frequency: Double) {
        if (stringIndex !in 0 until NUM_STRINGS) return
        stringStates[stringIndex].baseFrequency = frequency.toFloat()
    }

    fun resetAll() {
        for (i in 0 until NUM_STRINGS) {
            val state = stringStates[i]
            state.isActive = false
            state.wasActive = false
            state.triggeredVoice = false
            state.bendAmount = 0f
            state.rawDeflection = 0f
            state.voiceMix = 0.5f

            val voiceA = i * 2
            val voiceB = i * 2 + 1
            audioEngine.setPort(URI, "voice_bend_$voiceA", 0f)
            audioEngine.setPort(URI, "voice_bend_$voiceB", 0f)
            audioEngine.setPort(URI, "voice_mix_$voiceA", 1f)
            audioEngine.setPort(URI, "voice_mix_$voiceB", 1f)

            gestureStates[i].lastY = 0.5f
            gestureStates[i].lastX = 0f
            gestureStates[i].slideActive = false
        }

        slideBarPosition = 0f
        slideBarVibratoDepth = 0f
        slideBarWasActive = false
    }

    // SLIDE BAR
    private var slideBarPosition = 0f
    private var slideBarLastX = 0.5f
    private var slideBarLastTime = 0L
    private var slideBarVibratoDepth = 0f
    private var slideBarWasActive = false

    fun setSlideBar(yPosition: Float, xPosition: Float) {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaTime = (currentTime - slideBarLastTime).coerceAtLeast(1L)

        val deltaX = (xPosition - slideBarLastX).absoluteValue
        val wiggleVelocity = deltaX / deltaTime * 1000f

        val vibratoThreshold = 0.8f
        slideBarVibratoDepth = ((wiggleVelocity - vibratoThreshold) / 3f).coerceIn(0f, 1f)

        slideBarLastX = xPosition
        slideBarLastTime = currentTime
        slideBarPosition = yPosition.coerceIn(0f, 1f)

        val slideBend = slideBarPosition

        for (i in 0 until NUM_STRINGS) {
            val vibratoOscillation = if (slideBarVibratoDepth > 0.05f) {
                kotlin.math.sin(currentTime * 0.03) * slideBarVibratoDepth * 0.3
            } else {
                0.0
            }

            val totalBend = slideBend + vibratoOscillation.toFloat()
            val tensionCurve = totalBend * (1.0 + totalBend.absoluteValue * 0.5)
            val semitones = tensionCurve * MAX_BEND_SEMITONES * 0.5
            val frequencyMultiplier = 2.0.pow(semitones / 12.0) - 1.0

            val voiceA = i * 2
            val voiceB = i * 2 + 1

            audioEngine.setPort(URI, "voice_bend_$voiceA", frequencyMultiplier.toFloat())
            audioEngine.setPort(URI, "voice_bend_$voiceB", frequencyMultiplier.toFloat())
        }

        val isActive = slideBend > 0.02f
        if (isActive && !slideBarWasActive) {
            slideBarWasActive = true
        }
    }

    fun releaseSlideBar() {
        slideBarPosition = 0f
        slideBarVibratoDepth = 0f
        slideBarWasActive = false

        for (i in 0 until NUM_STRINGS) {
            if (!stringStates[i].isActive) {
                val voiceA = i * 2
                val voiceB = i * 2 + 1
                audioEngine.setPort(URI, "voice_bend_$voiceA", 0f)
                audioEngine.setPort(URI, "voice_bend_$voiceB", 0f)
            }
        }
    }

    fun getSlideBarPosition(): Float = slideBarPosition
    fun getSlideBarVibratoDepth(): Float = slideBarVibratoDepth
}
