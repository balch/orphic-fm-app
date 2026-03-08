package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.TriggerState
import org.balch.orpheus.plugins.plaits.engine.AdditiveEngine
import org.balch.orpheus.plugins.plaits.engine.ChordEngine
import org.balch.orpheus.plugins.plaits.engine.FmEngine
import org.balch.orpheus.plugins.plaits.engine.GrainEngine
import org.balch.orpheus.plugins.plaits.engine.ModalEngine
import org.balch.orpheus.plugins.plaits.engine.NoiseEngine
import org.balch.orpheus.plugins.plaits.engine.ParticleEngine
import org.balch.orpheus.plugins.plaits.engine.SpeechEngine
import org.balch.orpheus.plugins.plaits.engine.StringEngine
import org.balch.orpheus.plugins.plaits.engine.SwarmEngine
import org.balch.orpheus.plugins.plaits.engine.VirtualAnalogEngine
import org.balch.orpheus.plugins.plaits.engine.WavetableEngine
import org.balch.orpheus.plugins.plaits.engine.WaveshapingEngine
import org.balch.orpheus.plugins.plaits.JsynPlaitsUnitFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Offline JSyn rendering tests that produce WAV snapshot files identical in format
 * to the C++ test_snapshots.cpp output. Writes to `liborpheus_dsp/test/output/`
 * with `jsyn_` prefix for direct comparison against `cpp_` prefixed files.
 *
 * The C++ engine renders at 48000 Hz via original Plaits (with LPG/decay).
 * The JSyn engine renders at 44100 Hz via Kotlin-ported Plaits (with DspVoice ADSR envelope).
 * These structural differences are expected; the snapshots enable qualitative comparison.
 */
class JsynSnapshotTest {

    companion object {
        private const val SR = 44100
        /** Output directory — same location as C++ test output. */
        private val OUTPUT_DIR = findOutputDir()

        private fun findOutputDir(): File {
            // Walk up from CWD to find liborpheus_dsp/test/output
            var dir = File(System.getProperty("user.dir"))
            for (i in 0..5) {
                val candidate = File(dir, "liborpheus_dsp/test/output")
                if (candidate.exists() || candidate.parentFile.exists()) {
                    candidate.mkdirs()
                    return candidate
                }
                dir = dir.parentFile ?: break
            }
            // Fallback: create relative to project root
            val fallback = File("build/test-output")
            fallback.mkdirs()
            return fallback
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
            plaitsUnitFactory = JsynPlaitsUnitFactory(),
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
    }

    // ── Plaits engine factory helper ─────────────────────────────────

    private fun createPlaitsEngine(id: PlaitsEngineId): PlaitsEngine {
        val engine: PlaitsEngine = when (id) {
            PlaitsEngineId.VIRTUAL_ANALOG -> VirtualAnalogEngine()
            PlaitsEngineId.WAVESHAPING -> WaveshapingEngine()
            PlaitsEngineId.FM -> FmEngine()
            PlaitsEngineId.GRAIN -> GrainEngine()
            PlaitsEngineId.ADDITIVE -> AdditiveEngine()
            PlaitsEngineId.WAVETABLE -> WavetableEngine()
            PlaitsEngineId.CHORD -> ChordEngine()
            PlaitsEngineId.SPEECH -> SpeechEngine()
            PlaitsEngineId.SWARM -> SwarmEngine()
            PlaitsEngineId.NOISE -> NoiseEngine()
            PlaitsEngineId.PARTICLE -> ParticleEngine()
            PlaitsEngineId.STRING -> StringEngine()
            PlaitsEngineId.MODAL -> ModalEngine()
            // TODO: Drum engines (ANALOG_BASS_DRUM, ANALOG_SNARE_DRUM, METALLIC_HI_HAT, FM_DRUM)
            // are not available as standalone PlaitsEngine instances; they live in the drum plugin.
            else -> throw IllegalArgumentException("No standalone PlaitsEngine for $id")
        }
        engine.init()
        return engine
    }

    // ── Full-chain JSyn engine render (via DspVoice) ──────────────────

    private fun renderEngine(
        engineId: PlaitsEngineId,
        note: Float = 60f,
        harmonics: Float = 0.5f,
        timbre: Float = 0.5f,
        morph: Float = 0.5f,
        durationSeconds: Float = 2f,
        gateSeconds: Float = 1f
    ): FloatArray {
        val engine = OfflineAudioEngine(SR)
        val factory = createFactory()
        val voice = DspVoice(engine, factory, pitchMultiplier = 1.0)

        val plaitsEngine = createPlaitsEngine(engineId)
        voice.plaits.setEngine(plaitsEngine)
        voice.setEngineActive(true)
        voice.plaits.setNote(note)
        voice.plaits.setTimbre(timbre)
        voice.plaits.setMorph(morph)
        voice.plaits.setHarmonics(harmonics)

        val freqHz = 440.0 * 2.0.pow((note - 69.0) / 12.0)
        voice.frequency.set(freqHz)
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

    // ── Raw engine render (no DspVoice, no JSyn, no envelope) ────────

    private fun renderRawEngine(
        engineId: PlaitsEngineId,
        note: Float = 60f,
        harmonics: Float = 0.5f,
        timbre: Float = 0.5f,
        morph: Float = 0.5f,
        durationSeconds: Float = 2f,
        triggerAtStart: Boolean = true
    ): FloatArray {
        val plaitsEngine = createPlaitsEngine(engineId)
        val blockSize = 24
        val totalSamples = (SR * durationSeconds).toInt()
        val mono = FloatArray(totalSamples)
        val block = FloatArray(blockSize)
        val params = EngineParameters()

        var offset = 0
        var firstBlock = true
        while (offset < totalSamples) {
            val chunk = minOf(blockSize, totalSamples - offset)
            params.set(
                trigger = if (firstBlock && triggerAtStart) TriggerState.RISING_EDGE else TriggerState.HIGH,
                note = note,
                timbre = timbre,
                morph = morph,
                harmonics = harmonics,
                accent = 0.8f
            )
            plaitsEngine.render(params, block, null, chunk)
            val gain = plaitsEngine.outGain
            for (i in 0 until chunk) {
                mono[offset + i] = block[i] * gain
            }
            offset += chunk
            firstBlock = false
        }

        // Convert mono to interleaved stereo
        val stereo = FloatArray(totalSamples * 2)
        for (i in 0 until totalSamples) {
            stereo[i * 2] = mono[i]
            stereo[i * 2 + 1] = mono[i]
        }
        return stereo
    }

    /**
     * Scenario 1: Single voice, MIDI note 60 (C4), Virtual Analog engine.
     * Gate on for 1 second, then off for 1 second.
     * Matches C++ `cpp_single_voice_c4`.
     */
    @Test
    fun singleVoiceC4() {
        val engine = OfflineAudioEngine(SR)
        val factory = createFactory()
        val voice = DspVoice(engine, factory, pitchMultiplier = 1.0)

        // Configure: Plaits Virtual Analog engine
        val vaEngine = VirtualAnalogEngine().also { it.init() }
        voice.plaits.setEngine(vaEngine)
        voice.setEngineActive(true) // Switch to Plaits source
        voice.plaits.setNote(60f)
        voice.plaits.setTimbre(0.5f)
        voice.plaits.setMorph(0.5f)
        voice.plaits.setHarmonics(0.5f)

        // Set frequency for C4 (261.63 Hz)
        voice.frequency.set(261.63)
        voice.setEnvelopeSpeed(0.5f) // moderate envelope

        // Connect voice output to stereo line out (mono → both channels)
        voice.output.connect(engine.lineOutLeft)
        voice.output.connect(engine.lineOutRight)

        engine.start()

        val totalFrames = SR * 2 // 2 seconds
        val buf = engine.renderStereo(totalFrames, chunkSize = 1024) { offset ->
            if (offset == 0) {
                voice.gate.set(1.0) // Gate ON at start
            }
            if (offset >= SR) {
                voice.gate.set(0.0) // Gate OFF after 1 second
            }
        }

        engine.stop()

        val rms = computeRms(buf)
        val peak = computePeak(buf)
        println("  jsyn_single_voice_c4: RMS=${"%.4f".format(rms)} Peak=${"%.4f".format(peak)}")
        assertTrue(peak > 0.001f, "Voice should produce audio (peak=$peak)")

        writeWav(File(OUTPUT_DIR, "jsyn_single_voice_c4.wav"), buf, totalFrames, SR)
    }

    /**
     * Scenario 2: 4-voice chord (C-E-G-C').
     * Matches C++ `cpp_4voice_chord`.
     */
    @Test
    fun fourVoiceChord() {
        val engine = OfflineAudioEngine(SR)
        val factory = createFactory()
        val notes = doubleArrayOf(261.63, 329.63, 392.00, 523.25) // C4, E4, G4, C5
        val midiNotes = floatArrayOf(60f, 64f, 67f, 72f)
        val voices = Array(4) { i ->
            DspVoice(engine, factory, pitchMultiplier = 1.0).also { voice ->
                val vaEngine = VirtualAnalogEngine().also { it.init() }
                voice.plaits.setEngine(vaEngine)
                voice.setEngineActive(true)
                voice.plaits.setNote(midiNotes[i])
                voice.plaits.setTimbre(0.5f)
                voice.plaits.setMorph(0.5f)
                voice.plaits.setHarmonics(0.5f)
                voice.frequency.set(notes[i])
                voice.setEnvelopeSpeed(0.5f)
                // All 4 voices mix into the same stereo bus
                voice.output.connect(engine.lineOutLeft)
                voice.output.connect(engine.lineOutRight)
            }
        }

        engine.start()

        val totalFrames = SR * 2
        val buf = engine.renderStereo(totalFrames, chunkSize = 1024) { offset ->
            if (offset == 0) {
                voices.forEach { it.gate.set(1.0) }
            }
            if (offset >= SR) {
                voices.forEach { it.gate.set(0.0) }
            }
        }

        engine.stop()

        val rms = computeRms(buf)
        val peak = computePeak(buf)
        println("  jsyn_4voice_chord: RMS=${"%.4f".format(rms)} Peak=${"%.4f".format(peak)}")
        assertTrue(peak > 0.001f, "Chord should produce audio (peak=$peak)")

        writeWav(File(OUTPUT_DIR, "jsyn_4voice_chord.wav"), buf, totalFrames, SR)
    }

    /**
     * Scenario 5: Single voice through reverb.
     * Gate on for 0.5s, then let reverb tail ring out for 3.5s.
     * Matches C++ `cpp_voice_reverb`.
     */
    @Test
    fun voiceReverb() {
        val engine = OfflineAudioEngine(SR)
        val factory = createFactory()
        val voice = DspVoice(engine, factory, pitchMultiplier = 1.0)

        val vaEngine = VirtualAnalogEngine().also { it.init() }
        voice.plaits.setEngine(vaEngine)
        voice.setEngineActive(true)
        voice.plaits.setNote(60f)
        voice.plaits.setTimbre(0.5f)
        voice.plaits.setMorph(0.5f)
        voice.plaits.setHarmonics(0.5f)
        voice.frequency.set(261.63)
        voice.setEnvelopeSpeed(0.5f)

        // Create reverb unit
        val reverb = factory.createReverbUnit()
        engine.addUnit(reverb as AudioUnit)
        reverb.setAmount(0.5f)
        reverb.setTime(0.7f)

        // Create mixer (dry + wet)
        val mixer = factory.createAdd()
        engine.addUnit(mixer)

        // Wire: voice → reverb input + mixer dry input
        voice.output.connect(reverb.inputLeft)
        voice.output.connect(reverb.inputRight)
        voice.output.connect(mixer.inputA)
        reverb.output.connect(mixer.inputB)

        // Mixer → stereo out
        mixer.output.connect(engine.lineOutLeft)
        reverb.outputRight.connect(engine.lineOutRight)

        engine.start()

        val totalFrames = SR * 4 // 4 seconds to capture reverb tail
        val buf = engine.renderStereo(totalFrames, chunkSize = 1024) { offset ->
            if (offset == 0) voice.gate.set(1.0)
            if (offset >= SR / 2) voice.gate.set(0.0) // Gate off at 0.5s
        }

        engine.stop()

        val rms = computeRms(buf)
        val peak = computePeak(buf)
        // Compute tail RMS (last 1 second)
        val tailStart = SR * 3 * 2 // frame 3s, stereo
        val tailRms = computeRms(buf, tailStart, buf.size - tailStart)
        println("  jsyn_voice_reverb: RMS=${"%.4f".format(rms)} Peak=${"%.4f".format(peak)} Tail=${"%.6f".format(tailRms)}")
        assertTrue(peak > 0.001f, "Voice+reverb should produce audio")

        writeWav(File(OUTPUT_DIR, "jsyn_voice_reverb.wav"), buf, totalFrames, SR)
    }

    // ── Phase 2: Per-engine JSyn snapshots (full DspVoice chain) ─────

    private val engineNames = mapOf(
        PlaitsEngineId.VIRTUAL_ANALOG to "virtual_analog",
        PlaitsEngineId.WAVESHAPING to "waveshaping",
        PlaitsEngineId.FM to "fm",
        PlaitsEngineId.GRAIN to "grain",
        PlaitsEngineId.ADDITIVE to "additive",
        PlaitsEngineId.WAVETABLE to "wavetable",
        PlaitsEngineId.CHORD to "chord",
        PlaitsEngineId.SPEECH to "speech",
        PlaitsEngineId.SWARM to "swarm",
        PlaitsEngineId.NOISE to "noise",
        PlaitsEngineId.PARTICLE to "particle",
        PlaitsEngineId.STRING to "string",
        PlaitsEngineId.MODAL to "modal",
        // TODO: Add drum engines when available as standalone PlaitsEngine
    )

    @Test
    fun allEngines() {
        for ((engineId, name) in engineNames) {
            println("  Rendering jsyn_engine_$name...")
            val buf = renderEngine(engineId)
            val totalFrames = SR * 2
            val rms = computeRms(buf)
            val peak = computePeak(buf)
            println("    RMS=${"%.4f".format(rms)} Peak=${"%.4f".format(peak)}")
            assertTrue(peak > 0.0001f, "Engine $name should produce audio (peak=$peak)")
            writeWav(File(OUTPUT_DIR, "jsyn_engine_$name.wav"), buf, totalFrames, SR)
        }
    }

    // ── Phase 4: Raw engine snapshots (no DspVoice, no envelope) ─────

    @Test
    fun allEnginesRaw() {
        for ((engineId, name) in engineNames) {
            println("  Rendering jsyn_raw_$name...")
            val buf = renderRawEngine(engineId)
            val totalFrames = SR * 2
            val rms = computeRms(buf)
            val peak = computePeak(buf)
            println("    RMS=${"%.4f".format(rms)} Peak=${"%.4f".format(peak)}")
            writeWav(File(OUTPUT_DIR, "jsyn_raw_$name.wav"), buf, totalFrames, SR)
        }
    }

    // ── Phase 3: Cross-engine comparison report ───────────────────────

    /**
     * Compare JSyn vs C++ WAV snapshots for all engines and full-chain scenarios.
     * Always passes — purely diagnostic output.
     */
    @Test
    fun crossEngineComparison() {
        val engines = engineNames.values.toList()
        val fullScenarios = listOf("single_voice_c4", "4voice_chord", "voice_reverb")

        println("\n=== Cross-Engine Comparison Report ===")
        println("%-20s | %8s %8s | %8s %8s | %8s".format(
            "Engine", "JSyn RMS", "JSyn Pk", "C++ RMS", "C++ Pk", "Ratio"))
        println("-".repeat(80))

        for (name in engines + fullScenarios) {
            val jsynPrefix = if (name in engines) "jsyn_engine_" else "jsyn_"
            val cppPrefix = if (name in engines) "cpp_engine_" else "cpp_"
            val jsynFile = File(OUTPUT_DIR, "$jsynPrefix$name.wav")
            val cppFile = File(OUTPUT_DIR, "$cppPrefix$name.wav")

            if (!jsynFile.exists() || !cppFile.exists()) {
                println("%-20s | %s".format(name, "SKIP (file missing)"))
                continue
            }

            val jsynWav = readWavMono(jsynFile)
            val cppWav = readWavMono(cppFile)
            val jsynRms = computeRms(jsynWav.samples)
            val cppRms = computeRms(cppWav.samples)
            val jsynPeak = computePeak(jsynWav.samples)
            val cppPeak = computePeak(cppWav.samples)
            val ratio = if (cppRms > 0.0001f) jsynRms / cppRms else 0f

            println("%-20s | %8.4f %8.4f | %8.4f %8.4f | %8.3f".format(
                name, jsynRms, jsynPeak, cppRms, cppPeak, ratio))
        }
        println()
    }

    // ── Phase 5: Normalized timbral comparison ────────────────────────

    /**
     * RMS-normalizes JSyn and C++ raw engine WAVs to the same level, then computes
     * timbral features (ZCR, crest factor) for side-by-side comparison.
     * Always passes — purely diagnostic output.
     */
    @Test
    fun normalizedComparison() {
        val engines = engineNames.values.toList()

        println("\n=== Normalized Timbral Comparison (raw engines) ===")
        println("  %-20s | %8s %8s | %8s %8s".format(
            "Engine", "JSyn ZCR", "C++ ZCR", "JSyn Crest", "C++ Crest"))
        println("  " + "-".repeat(70))

        for (name in engines) {
            val jsynFile = File(OUTPUT_DIR, "jsyn_raw_$name.wav")
            val cppFile = File(OUTPUT_DIR, "cpp_raw_$name.wav")
            if (!jsynFile.exists() || !cppFile.exists()) {
                println("  %-20s | SKIP (file missing)".format(name))
                continue
            }

            val jsyn = readWavMono(jsynFile)
            val cpp = readWavMono(cppFile)

            val jsynNorm = normalize(jsyn.samples, 0.1f)
            val cppNorm = normalize(cpp.samples, 0.1f)

            val jsynZcr = zeroCrossingRate(jsynNorm)
            val cppZcr = zeroCrossingRate(cppNorm)
            val jsynCrest = if (computeRms(jsynNorm) > 0f) computePeak(jsynNorm) / computeRms(jsynNorm) else 0f
            val cppCrest = if (computeRms(cppNorm) > 0f) computePeak(cppNorm) / computeRms(cppNorm) else 0f

            println("  %-20s | %8.4f %8.4f | %8.2f %8.2f".format(
                name, jsynZcr, cppZcr, jsynCrest, cppCrest))
        }
        println()
    }

    private fun normalize(samples: FloatArray, targetRms: Float): FloatArray {
        val rms = computeRms(samples)
        if (rms < 0.00001f) return samples.copyOf()
        val scale = targetRms / rms
        return FloatArray(samples.size) { samples[it] * scale }
    }

    private fun zeroCrossingRate(samples: FloatArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0f) != (samples[i - 1] >= 0f)) crossings++
        }
        return crossings.toFloat() / samples.size
    }

    // ── WAV writer (16-bit stereo, matching C++ write_wav format) ──────

    private fun writeWav(file: File, interleavedStereo: FloatArray, numFrames: Int, sampleRate: Int) {
        val channels = 2
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = numFrames * channels * bytesPerSample
        val fileSize = 44 + dataSize

        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(fileSize - 8)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // fmt chunk size
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * bytesPerSample) // byte rate
            header.putShort((channels * bytesPerSample).toShort()) // block align
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            raf.write(header.array())

            // Write samples
            val sampleBuf = ByteBuffer.allocate(numFrames * channels * bytesPerSample)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numFrames * channels) {
                val s = interleavedStereo[i].coerceIn(-1f, 1f)
                sampleBuf.putShort((s * 32767f).toInt().toShort())
            }
            raf.write(sampleBuf.array())
        }
        println("  Wrote ${file.absolutePath} ($numFrames frames, $sampleRate Hz)")
    }

    // ── WAV reader (16-bit stereo → mono left channel) ────────────────

    private data class WavData(val samples: FloatArray, val sampleRate: Int)

    private fun readWavMono(file: File): WavData {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            buf.position(22)
            val channels = buf.short.toInt()
            val sampleRate = buf.int
            buf.position(34)
            val bitsPerSample = buf.short.toInt()
            buf.position(40)
            val dataSize = buf.int

            val bytesPerSample = bitsPerSample / 8
            val totalSamples = dataSize / bytesPerSample
            val numFrames = totalSamples / channels

            val samples = FloatArray(numFrames)
            val sampleBuf = ByteArray(channels * bytesPerSample)
            for (i in 0 until numFrames) {
                raf.readFully(sampleBuf)
                val sb = ByteBuffer.wrap(sampleBuf).order(ByteOrder.LITTLE_ENDIAN)
                samples[i] = sb.short.toFloat() / 32767f // left channel only
            }
            return WavData(samples, sampleRate)
        }
    }

    // ── Analysis helpers ───────────────────────────────────────────────

    private fun computeRms(buf: FloatArray, start: Int = 0, count: Int = buf.size): Float {
        var sum = 0.0
        for (i in start until start + count) {
            sum += buf[i].toDouble() * buf[i].toDouble()
        }
        return sqrt(sum / count).toFloat()
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
