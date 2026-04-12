package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging

/**
 * Kotlin-side JNI bridge to the C++ DesktopEngine + liborpheus_dsp.
 * Audio rendering uses a pull model: Kotlin calls [nativeProcess] from the audio thread.
 */
class DesktopDspBridge {
    companion object {
        private val log = logging("DesktopDspBridge")

        init {
            loadNativeLibrary()
        }

        private fun loadNativeLibrary() {
            // First try java.library.path (dev workflow: -Djava.library.path=...)
            try {
                System.loadLibrary("orpheus_desktop")
                log.info { "Loaded orpheus_desktop via System.loadLibrary" }
                return
            } catch (e: UnsatisfiedLinkError) {
                log.info { "System.loadLibrary failed, trying JAR resource fallback: ${e.message}" }
            }

            // Fallback: extract from JAR resource to temp dir
            val osName = System.getProperty("os.name", "").lowercase()
            val archName = System.getProperty("os.arch", "").lowercase()

            val os = when {
                "mac" in osName || "darwin" in osName -> "darwin"
                "win" in osName -> "windows"
                "linux" in osName -> "linux"
                else -> error("Unsupported OS: $osName")
            }
            val arch = when {
                archName == "aarch64" || archName == "arm64" -> "aarch64"
                archName == "amd64" || archName == "x86_64" -> "x86_64"
                else -> error("Unsupported architecture: $archName")
            }

            val libName = when (os) {
                "darwin" -> "liborpheus_desktop.dylib"
                "linux" -> "liborpheus_desktop.so"
                "windows" -> "orpheus_desktop.dll"
                else -> error("Unsupported OS: $os")
            }

            val resourcePath = "/native/$os-$arch/$libName"
            val stream = DesktopDspBridge::class.java.getResourceAsStream(resourcePath)
                ?: error("Native library not found in JAR at $resourcePath")

            val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "orpheus-native")
            tempDir.mkdirs()
            val tempFile = java.io.File(tempDir, libName)

            stream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            System.load(tempFile.absolutePath)
            log.info { "Loaded orpheus_desktop from JAR resource: $resourcePath" }
        }
    }

    // -- Lifecycle ------------------------------------------------------------
    external fun nativeOpen(sampleRate: Int)
    external fun nativeClose()
    external fun nativeProcess(buffer: FloatArray)
    external fun nativeLoadGraph(serialized: ByteArray): Int

    // -- Query ----------------------------------------------------------------
    external fun nativeIsRunning(): Boolean
    external fun nativeGetSampleRate(): Float
    external fun nativeGetCpuLoad(): Double

    // -- Parameter control (called from UI thread) ----------------------------
    external fun nativeSetPort(uri: String, symbol: String, value: Float)
    external fun nativeGetPort(uri: String, symbol: String): Float
    external fun nativeSetVoiceGate(index: Int, active: Boolean)
    external fun nativeSetVoiceTune(index: Int, tune: Float)
    external fun nativeSetVoiceEngine(index: Int, engineIndex: Int)
    external fun nativeSetVoiceHarmonics(index: Int, value: Float)
    external fun nativeSetVoiceTimbre(index: Int, value: Float)
    external fun nativeSetVoiceMorph(index: Int, value: Float)
    external fun nativeSetVoiceDecay(index: Int, value: Float)
    external fun nativeSetVoiceActive(index: Int, active: Boolean)
    external fun nativeSetVoiceHold(index: Int, level: Float)
    external fun nativeTriggerDrum(drumIndex: Int, accent: Float)
    external fun nativeSetMasterVolume(value: Float)
    external fun nativeSetDrive(value: Float)
    external fun nativeSetDelayMix(value: Float)
    external fun nativeSetVibrato(value: Float)
    external fun nativeSetVibratoRate(value: Float)
    external fun nativeSetBend(value: Float)
    external fun nativeGetMonitor(out: FloatArray)
    external fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int
    external fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray)
    external fun nativeSetAutomation(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int)
    external fun nativeClearAutomation(target: Int, voiceIndex: Int)
    external fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int)
    external fun nativePlayTts()
    external fun nativeStopTts()
    external fun nativeIsTtsPlaying(): Int
    external fun nativeGetPulsarViz(
        gatesOut: BooleanArray,
        velocitiesOut: FloatArray,
        playheadsOut: IntArray,
        stepCountsOut: IntArray,
    )
    external fun nativeGetPulsarArrangement(out: IntArray)

    // miniaudio audio device control
    external fun nativeStartAudio(): Boolean
    external fun nativeStopAudio()
}
