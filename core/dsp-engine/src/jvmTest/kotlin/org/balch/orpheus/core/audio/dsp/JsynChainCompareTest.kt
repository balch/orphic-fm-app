package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.plaits.engine.VirtualAnalogEngine
import org.balch.orpheus.plugins.resonator.JsynResonatorUnitFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Full-chain comparison tests matching the C++ test_chain_compare.cpp scenarios.
 *
 * Each scenario renders a voice through effects (resonator, drive, delay, reverb)
 * and writes a WAV file. Outputs are compared against C++ equivalents using
 * compare_chains.py for numerical and spectral analysis.
 *
 * C++ renders at 48000 Hz, JSyn at 44100 Hz — sample rate difference is expected.
 * The comparison focuses on qualitative similarity: RMS levels, spectral shape,
 * and parameter response curves (not sample-level identity).
 */
class JsynChainCompareTest {

    companion object {
        private const val SR = 44100

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

    private fun createFactory(): DspFactory {
        return DspFactoryImpl(
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
            resonatorUnitFactory = JsynResonatorUnitFactory(),
            grainsUnitFactory = org.balch.orpheus.plugins.grains.JsynGrainsUnitFactory(),
            looperUnitFactory = JsynLooperUnitFactory(),
            warpsUnitFactory = org.balch.orpheus.plugins.warps.JsynWarpsUnitFactory(),
            clockUnitFactory = JsynClockUnitFactory(),
            fluxUnitFactory = org.balch.orpheus.plugins.flux.JsynFluxUnitFactory(),
            reverbUnitFactory = org.balch.orpheus.plugins.reverb.JsynReverbUnitFactory(),
            ttsPlayerUnitFactory = JsynTtsPlayerUnitFactory(),
            speechEffectsUnitFactory = JsynSpeechEffectsUnitFactory(),
        )
    }

    // ── Render: Voice only (no effects) ──────────────────────────────

    private fun renderVoiceOnly(
        note: Float = 60f,
        gateSeconds: Float = 1f,
        durationSeconds: Float = 2f
    ): FloatArray {
        val engine = OfflineAudioEngine(SR)
        val factory = createFactory()
        val voice = DspVoice(engine, factory, pitchMultiplier = 1.0)
        val vaEngine = VirtualAnalogEngine()
        vaEngine.init()
        voice.plaits.setEngine(vaEngine)
        voice.setEngineActive(true)
        voice.plaits.setNote(note)
        voice.plaits.setTimbre(0.5f)
        voice.plaits.setMorph(0.5f)
        voice.plaits.setHarmonics(0.5f)
        voice.frequency.set(440.0 * 2.0.pow((note - 69.0) / 12.0))
        voice.setEnvelopeSpeed(0.5f)
        voice.output.connect(engine.lineOutLeft)
        voice.output.connect(engine.lineOutRight)
        engine.start()
        val totalFrames = (SR * durationSeconds).toInt()
        val gateFrames = (SR * gateSeconds).toInt()
        val buf = engine.renderStereo(totalFrames, chunkSize = 1024) { offset ->
            if (offset == 0) voice.gate.set(1.0)
            if (offset >= gateFrames) voice.gate.set(0.0)
        }
        engine.stop()
        return buf
    }

    // ── Render: Voice + Resonator ────────────────────────────────────

    private fun renderVoiceWithResonator(
        note: Float = 60f,
        gateSeconds: Float = 1f,
        durationSeconds: Float = 3f,
        resonatorMode: Int = 0,
        structure: Float = 0.25f,
        brightness: Float = 0.5f,
        damping: Float = 0.3f,
        position: Float = 0.5f,
        resoMix: Float = 1.0f
    ): FloatArray {
        val engine = OfflineAudioEngine(SR)
        val factory = createFactory()
        val voice = DspVoice(engine, factory, pitchMultiplier = 1.0)
        val vaEngine = VirtualAnalogEngine()
        vaEngine.init()
        voice.plaits.setEngine(vaEngine)
        voice.setEngineActive(true)
        voice.plaits.setNote(note)
        voice.plaits.setTimbre(0.5f)
        voice.plaits.setMorph(0.5f)
        voice.plaits.setHarmonics(0.5f)
        voice.frequency.set(440.0 * 2.0.pow((note - 69.0) / 12.0))
        voice.setEnvelopeSpeed(0.5f)

        // Create resonator
        val resonator = factory.createResonatorUnit()
        engine.addUnit(resonator)
        resonator.setResonatorEnabled(true)
        resonator.setMode(resonatorMode)
        resonator.setStructure(structure)
        resonator.setBrightness(brightness)
        resonator.setDamping(damping)
        resonator.setPosition(position)

        // Strum at the note frequency
        val freqHz = (440.0 * 2.0.pow((note - 69.0) / 12.0)).toFloat()
        resonator.strum(freqHz)

        // Wet/dry mixing
        val wetGain = factory.createMultiply()
        val dryGain = factory.createMultiply()
        engine.addUnit(wetGain)
        engine.addUnit(dryGain)

        // Voice → resonator input AND dry path
        voice.output.connect(resonator.input)
        resonator.output.connect(wetGain.inputA)
        wetGain.inputB.set(resoMix.toDouble())

        voice.output.connect(dryGain.inputA)
        dryGain.inputB.set((1.0 - resoMix).toDouble())

        // Sum wet + dry
        val sum = factory.createPassThrough()
        engine.addUnit(sum)
        wetGain.output.connect(sum.input)
        dryGain.output.connect(sum.input)

        sum.output.connect(engine.lineOutLeft)
        sum.output.connect(engine.lineOutRight)

        engine.start()
        val totalFrames = (SR * durationSeconds).toInt()
        val gateFrames = (SR * gateSeconds).toInt()
        val buf = engine.renderStereo(totalFrames, chunkSize = 1024) { offset ->
            if (offset == 0) voice.gate.set(1.0)
            if (offset >= gateFrames) voice.gate.set(0.0)
        }
        engine.stop()
        return buf
    }

    // ── Test: Chain Comparison ────────────────────────────────────────

    @Test
    fun chainComparison() {
        println("\n=== Full-Chain Comparison (JSyn vs C++) ===")

        data class Scenario(
            val name: String,
            val render: () -> FloatArray
        )

        val scenarios = listOf(
            Scenario("voice_va_dry") { renderVoiceOnly() },
            Scenario("voice_reso_modal") {
                renderVoiceWithResonator(resonatorMode = 0, resoMix = 1.0f)
            },
            Scenario("voice_reso_string") {
                renderVoiceWithResonator(resonatorMode = 1, resoMix = 1.0f)
            },
            Scenario("voice_reso_sympathetic") {
                renderVoiceWithResonator(resonatorMode = 2, resoMix = 1.0f)
            },
            Scenario("voice_reso_modal_half") {
                renderVoiceWithResonator(resonatorMode = 0, resoMix = 0.5f)
            },
            // Parameter sweeps
            Scenario("reso_struct_low") {
                renderVoiceWithResonator(structure = 0.1f)
            },
            Scenario("reso_struct_high") {
                renderVoiceWithResonator(structure = 0.9f)
            },
            Scenario("reso_bright_low") {
                renderVoiceWithResonator(brightness = 0.1f)
            },
            Scenario("reso_bright_high") {
                renderVoiceWithResonator(brightness = 0.9f)
            },
            Scenario("reso_damp_low") {
                renderVoiceWithResonator(damping = 0.1f)
            },
            Scenario("reso_damp_high") {
                renderVoiceWithResonator(damping = 0.9f)
            },
        )

        val csvFile = File(OUTPUT_DIR, "chain_comparison.csv")
        csvFile.printWriter().use { pw ->
            pw.println("scenario,jsyn_rms,jsyn_peak,cpp_rms,cpp_peak,rms_ratio,peak_ratio")

            for (sc in scenarios) {
                val buf = sc.render()
                val jsynRms = computeRms(buf)
                val jsynPeak = computePeak(buf)

                writeWav(File(OUTPUT_DIR, "jsyn_chain_${sc.name}.wav"), buf, buf.size / 2, SR)

                // Look for C++ counterpart
                val cppFile = File(OUTPUT_DIR, "cpp_chain_${sc.name}.wav")
                if (cppFile.exists()) {
                    val cppWav = readWavMono(cppFile)
                    val cppRms = computeRms(cppWav.samples)
                    val cppPeak = computePeak(cppWav.samples)
                    val rmsRatio = if (jsynRms > 0.001f) cppRms / jsynRms else 0f
                    val peakRatio = if (jsynPeak > 0.001f) cppPeak / jsynPeak else 0f

                    println("  %-35s JSyn rms=%.4f peak=%.4f  C++ rms=%.4f peak=%.4f  ratio=%.2f/%.2f".format(
                        sc.name, jsynRms, jsynPeak, cppRms, cppPeak, rmsRatio, peakRatio))
                    pw.println("${sc.name},${"%.6f".format(jsynRms)},${"%.6f".format(jsynPeak)},${"%.6f".format(cppRms)},${"%.6f".format(cppPeak)},${"%.4f".format(rmsRatio)},${"%.4f".format(peakRatio)}")
                } else {
                    println("  %-35s JSyn rms=%.4f peak=%.4f  C++: (run C++ tests first)".format(
                        sc.name, jsynRms, jsynPeak))
                    pw.println("${sc.name},${"%.6f".format(jsynRms)},${"%.6f".format(jsynPeak)},,,")
                }
            }
        }
        println("\n  CSV: ${csvFile.absolutePath}")
        println("  Compare: python3 ${OUTPUT_DIR.absolutePath}/compare_chains.py")
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun writeWav(file: File, interleavedStereo: FloatArray, numFrames: Int, sampleRate: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataSize = numFrames * 2 * 2
            val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray())
            buf.putInt(36 + dataSize)
            buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray())
            buf.putInt(16)
            buf.putShort(1)
            buf.putShort(2)
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 4)
            buf.putShort(4)
            buf.putShort(16)
            buf.put("data".toByteArray())
            buf.putInt(dataSize)
            for (i in 0 until numFrames * 2) {
                val s = interleavedStereo[i].coerceIn(-1f, 1f)
                buf.putShort((s * 32767f).toInt().toShort())
            }
            raf.write(buf.array())
        }
    }

    private data class WavData(val samples: FloatArray, val sampleRate: Int)

    private fun readWavMono(file: File): WavData {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.read(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(22)
            val channels = buf.short.toInt()
            val sr = buf.int
            buf.position(34)
            val bitsPerSample = buf.short.toInt()
            buf.position(40)
            val dataSize = buf.int
            val bytesPerSample = bitsPerSample / 8
            val totalSamples = dataSize / bytesPerSample
            val samples = FloatArray(totalSamples / channels)
            val dataBuf = ByteArray(dataSize)
            raf.read(dataBuf)
            val data = ByteBuffer.wrap(dataBuf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                val s = data.short.toFloat() / 32768f
                if (channels > 1) data.short // skip right channel
                samples[i] = s
            }
            return WavData(samples, sr)
        }
    }

    private fun computeRms(buf: FloatArray): Float {
        if (buf.isEmpty()) return 0f
        var sum = 0.0
        for (s in buf) sum += s * s
        return sqrt(sum / buf.size).toFloat()
    }

    private fun computePeak(buf: FloatArray): Float {
        var peak = 0f
        for (s in buf) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        return peak
    }
}
