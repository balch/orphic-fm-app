package org.balch.orpheus.core.audio.dsp

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * FM / modulation comparison tests: JSyn (Kotlin) reference outputs for direct
 * comparison with C++ duo voice rendering.
 *
 * Each scenario produces a WAV file under `liborpheus_dsp/test/output/` with
 * prefix `jsyn_fm_`.  The companion C++ tests produce `cpp_fm_` prefixed files.
 * Run `compare_fm.py` to tabulate RMS / peak / ZCR differences.
 *
 * Scenarios:
 *  1. dry          – duo pair, no modulation (baseline)
 *  2. voice_fm_lo  – VOICE_FM depth=0.2
 *  3. voice_fm_hi  – VOICE_FM depth=0.8
 *  4. lfo_fm       – LFO at 5 Hz → FM depth=0.5
 *  5. vibrato      – vibrato depth = 20 Hz, rate = 5 Hz
 *  6. coupling     – voice coupling depth=0.5 (30 Hz * 0.5 = 15 Hz)
 *  7. feedback     – self-feedback (harmonics) 0.5
 */
class JsynFmCompareTest {

    companion object {
        private const val SR = 44100
        private const val NOTE_A = 60f  // C4
        private const val NOTE_B = 67f  // G4

        private val OUTPUT_DIR: File by lazy {
            var dir = File(System.getProperty("user.dir"))
            for (i in 0..5) {
                val candidate = File(dir, "liborpheus_dsp/test/output")
                if (candidate.exists() || candidate.parentFile.exists()) {
                    candidate.mkdirs()
                    return@lazy candidate
                }
                dir = dir.parentFile ?: break
            }
            File("build/test-output").also { it.mkdirs() }
        }
    }

    private fun createFactory(): DspFactory = DspFactoryImpl(
        sineFactory = JsynSineOscillatorFactory(),
        triangleFactory = JsynTriangleOscillatorFactory(),
        squareFactory = JsynSquareOscillatorFactory(),
        sawtoothFactory = JsynSawtoothOscillatorFactory(),
        envelopeFactory = JsynEnvelopeFactory(),
        delayLineFactory = JsynDelayLineFactory(),
        peakFollowerFactory = JsynPeakFollowerFactory(),
        limiterFactory = JsynLimiterFactory(),
        hardClipFactory = JsynHardClipFactory(),
        multiplyFactory = JsynMultiplyFactory(),
        addFactory = JsynAddFactory(),
        multiplyAddFactory = JsynMultiplyAddFactory(),
        passThroughFactory = JsynPassThroughFactory(),
        minimumFactory = JsynMinimumFactory(),
        maximumFactory = JsynMaximumFactory(),
        linearRampFactory = JsynLinearRampFactory(),
        automationPlayerFactory = JsynAutomationPlayerFactory(),
        plaitsUnitFactory = org.balch.orpheus.plugins.plaits.JsynPlaitsUnitFactory(),
        drumUnitFactory = org.balch.orpheus.plugins.drum.JsynDrumUnitFactory(),
        resonatorUnitFactory = org.balch.orpheus.plugins.resonator.JsynResonatorUnitFactory(),
        grainsUnitFactory = org.balch.orpheus.plugins.grains.JsynGrainsUnitFactory(),
        looperUnitFactory = JsynLooperUnitFactory(),
        warpsUnitFactory = org.balch.orpheus.plugins.warps.JsynWarpsUnitFactory(),
        clockUnitFactory = JsynClockUnitFactory(),
        fluxUnitFactory = org.balch.orpheus.plugins.flux.JsynFluxUnitFactory(),
        reverbUnitFactory = org.balch.orpheus.plugins.reverb.JsynReverbUnitFactory(),
        ttsPlayerUnitFactory = JsynTtsPlayerUnitFactory(),
        speechEffectsUnitFactory = JsynSpeechEffectsUnitFactory(),
    )

    // ── Core render helper: duo pair through Engine 0 ────────────────────

    private data class DuoConfig(
        val fmDepth: Double = 0.0,
        val voiceFm: Boolean = false,
        val lfoFmHz: Double = 0.0,
        val vibratoDepthHz: Double = 0.0,
        val vibratoLfoHz: Double = 5.0,
        val couplingDepthHz: Double = 0.0,
        val harmonicsA: Double = 0.0,
        val harmonicsB: Double = 0.0,
        val sharpnessA: Double = 0.0,
        val sharpnessB: Double = 0.0,
        val morphA: Double = 0.0,
        val morphB: Double = 0.0,
    )

    /**
     * Render a duo pair (voices A and B) with Engine 0 oscillators.
     * Returns interleaved stereo: voice A on left, voice B on right.
     */
    private fun renderDuo(
        cfg: DuoConfig,
        durationSeconds: Float = 2f,
        gateSeconds: Float = 1f,
    ): FloatArray {
        val engine = OfflineAudioEngine(SR)
        val factory = createFactory()
        val voiceA = DspVoice(engine, factory, pitchMultiplier = 1.0)
        val voiceB = DspVoice(engine, factory, pitchMultiplier = 1.0)

        // Engine 0 mode (oscillator, not Plaits)
        voiceA.setEngineActive(false)
        voiceB.setEngineActive(false)

        // Notes
        val freqA = 440.0 * 2.0.pow((NOTE_A - 69.0) / 12.0)
        val freqB = 440.0 * 2.0.pow((NOTE_B - 69.0) / 12.0)
        voiceA.frequency.set(freqA)
        voiceB.frequency.set(freqB)

        // Sharpness (timbre)
        voiceA.sharpness.set(cfg.sharpnessA)
        voiceB.sharpness.set(cfg.sharpnessB)

        // Harmonics (self-feedback)
        voiceA.feedbackAmount.set(cfg.harmonicsA)
        voiceB.feedbackAmount.set(cfg.harmonicsB)

        // Envelope speed: use a moderate speed for audible envelope
        voiceA.setEnvelopeSpeed(0.5f)
        voiceB.setEnvelopeSpeed(0.5f)

        // ── VOICE_FM wiring: A → B, B → A ──
        if (cfg.voiceFm) {
            voiceA.output.connect(voiceB.modInput)
            voiceB.output.connect(voiceA.modInput)
            voiceA.fmDepth.set(cfg.fmDepth)
            voiceB.fmDepth.set(cfg.fmDepth)
        }

        // ── LFO FM wiring ──
        // Use a simple sine oscillator as LFO source
        val lfo = if (cfg.lfoFmHz > 0 || cfg.vibratoDepthHz > 0) {
            val sineOsc = factory.createSineOscillator()
            engine.addUnit(sineOsc)
            sineOsc.frequency.set(
                if (cfg.lfoFmHz > 0) cfg.lfoFmHz else cfg.vibratoLfoHz
            )
            sineOsc.amplitude.set(1.0)
            sineOsc
        } else null

        if (cfg.lfoFmHz > 0 && lfo != null) {
            lfo.output.connect(voiceA.modInput)
            lfo.output.connect(voiceB.modInput)
            voiceA.fmDepth.set(cfg.fmDepth)
            voiceB.fmDepth.set(cfg.fmDepth)
        }

        // ── Vibrato wiring ──
        if (cfg.vibratoDepthHz > 0 && lfo != null) {
            lfo.output.connect(voiceA.vibratoInput)
            lfo.output.connect(voiceB.vibratoInput)
            // vibratoDepth is Hz range: LFO output (-1..+1) * depth = ±depth Hz
            // JSyn: vibrato_depth * 2 semitones → Hz
            // C++: lfo * vibrato_depth * 2.0 semitones → freq offset
            // To match: set vibratoDepth directly as Hz
            voiceA.vibratoDepth.set(cfg.vibratoDepthHz)
            voiceB.vibratoDepth.set(cfg.vibratoDepthHz)
        }

        // ── Coupling wiring ──
        if (cfg.couplingDepthHz > 0) {
            voiceA.envelopeOutput.connect(voiceB.couplingInput)
            voiceB.envelopeOutput.connect(voiceA.couplingInput)
            voiceA.couplingDepth.set(cfg.couplingDepthHz)
            voiceB.couplingDepth.set(cfg.couplingDepthHz)
        }

        // Output: A → left, B → right
        voiceA.output.connect(engine.lineOutLeft)
        voiceB.output.connect(engine.lineOutRight)

        engine.start()

        val totalFrames = (SR * durationSeconds).toInt()
        val gateFrames = (SR * gateSeconds).toInt()
        val buf = engine.renderStereo(totalFrames, chunkSize = 128) { offset ->
            if (offset == 0) {
                voiceA.gate.set(1.0)
                voiceB.gate.set(1.0)
            }
            if (offset >= gateFrames) {
                voiceA.gate.set(0.0)
                voiceB.gate.set(0.0)
            }
        }

        engine.stop()
        return buf
    }

    // ── Scenarios ────────────────────────────────────────────────────────

    @Test
    fun fmComparison() {
        println("\n=== JSyn FM Comparison WAVs ===")

        data class Scenario(val name: String, val cfg: DuoConfig)

        val scenarios = listOf(
            Scenario("dry", DuoConfig()),
            Scenario("voice_fm_lo", DuoConfig(voiceFm = true, fmDepth = 0.2)),
            Scenario("voice_fm_hi", DuoConfig(voiceFm = true, fmDepth = 0.8)),
            Scenario("lfo_fm", DuoConfig(lfoFmHz = 5.0, fmDepth = 0.5)),
            Scenario("vibrato", DuoConfig(vibratoDepthHz = 20.0, vibratoLfoHz = 5.0)),
            Scenario("coupling", DuoConfig(couplingDepthHz = 15.0)),
            Scenario("feedback", DuoConfig(harmonicsA = 0.5, harmonicsB = 0.5)),
        )

        println("%-20s | %8s %8s %8s %8s".format("Scenario", "RMS_L", "Peak_L", "RMS_R", "Peak_R"))
        println("-".repeat(65))

        for (s in scenarios) {
            val buf = renderDuo(s.cfg)
            val numFrames = buf.size / 2
            val file = File(OUTPUT_DIR, "jsyn_fm_${s.name}.wav")
            writeWav(file, buf, numFrames, SR)

            // Deinterleave for stats
            val left = FloatArray(numFrames) { buf[it * 2] }
            val right = FloatArray(numFrames) { buf[it * 2 + 1] }
            val rmsL = computeRms(left)
            val rmsR = computeRms(right)
            val pkL = computePeak(left)
            val pkR = computePeak(right)

            println("%-20s | %8.4f %8.4f %8.4f %8.4f".format(s.name, rmsL, pkL, rmsR, pkR))
        }

        // Cross-comparison if C++ files exist
        println("\n--- JSyn vs C++ Comparison ---")
        println("%-20s | %10s %10s | %10s %10s | %8s".format(
            "Scenario", "JSyn RMS", "C++ RMS", "JSyn Peak", "C++ Peak", "Ratio"))
        println("-".repeat(85))

        for (s in scenarios) {
            val jsynFile = File(OUTPUT_DIR, "jsyn_fm_${s.name}.wav")
            val cppFile = File(OUTPUT_DIR, "cpp_fm_${s.name}.wav")
            if (!jsynFile.exists() || !cppFile.exists()) {
                println("%-20s | SKIP (run C++ tests first)".format(s.name))
                continue
            }
            val jsyn = readWavMono(jsynFile)
            val cpp = readWavMono(cppFile)
            val jRms = computeRms(jsyn.samples)
            val cRms = computeRms(cpp.samples)
            val jPk = computePeak(jsyn.samples)
            val cPk = computePeak(cpp.samples)
            val ratio = if (cRms > 0.0001f) jRms / cRms else 0f
            println("%-20s | %10.4f %10.4f | %10.4f %10.4f | %8.3f".format(
                s.name, jRms, cRms, jPk, cPk, ratio))
        }
        println()
    }

    // ── WAV I/O ─────────────────────────────────────────────────────────

    private fun writeWav(file: File, interleavedStereo: FloatArray, numFrames: Int, sampleRate: Int) {
        val channels = 2
        val bytesPerSample = 2
        val dataSize = numFrames * channels * bytesPerSample

        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(44 + dataSize - 8)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * bytesPerSample)
            header.putShort((channels * bytesPerSample).toShort())
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            raf.write(header.array())

            val sampleBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numFrames * channels) {
                val s = interleavedStereo[i].coerceIn(-1f, 1f)
                sampleBuf.putShort((s * 32767f).toInt().toShort())
            }
            raf.write(sampleBuf.array())
        }
        println("  Wrote ${file.name} ($numFrames frames, $sampleRate Hz)")
    }

    private data class WavData(val samples: FloatArray, val sampleRate: Int)

    private fun readWavMono(file: File): WavData {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(22)
            val channels = buf.short.toInt()
            val sampleRate = buf.int
            buf.position(40)
            val dataSize = buf.int
            val numFrames = dataSize / (channels * 2)
            val samples = FloatArray(numFrames)
            val sampleBuf = ByteArray(channels * 2)
            for (i in 0 until numFrames) {
                raf.readFully(sampleBuf)
                val sb = ByteBuffer.wrap(sampleBuf).order(ByteOrder.LITTLE_ENDIAN)
                samples[i] = sb.short.toFloat() / 32767f
            }
            return WavData(samples, sampleRate)
        }
    }

    private fun computeRms(buf: FloatArray): Float {
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s
        return sqrt(sum / buf.size).toFloat()
    }

    private fun computePeak(buf: FloatArray): Float {
        var peak = 0f
        for (s in buf) { val a = abs(s); if (a > peak) peak = a }
        return peak
    }
}
