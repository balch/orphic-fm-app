package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.Dispatchers
import org.balch.orpheus.core.audio.patch.PATCH_CELLS
import org.balch.orpheus.core.audio.patch.PATCH_URI
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.VoicePlugin
import org.balch.orpheus.plugins.flux.FluxPlugin
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.stereo.StereoPlugin
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Patch cells resend in full only for a fresh native engine; the preset native sync relies on
 * the bay's diff. Also pins the live FM structure toggle reaching C++ fm_cross_quad.
 *
 * ./gradlew :core:dsp-engine:jvmTest --tests '*DspSynthEnginePatchSyncTest*'
 */
class DspSynthEnginePatchSyncTest {

    private val writes = mutableListOf<Triple<String, String, Float>>()
    private val bridge = recordingBridge(writes)
    private val voicePlugin = VoicePlugin(bridge as AudioEngine)
    private val controller = SynthController()
    private val engine = buildEngine()

    private fun patchWrites() = writes.filter { it.first == PATCH_URI }

    @Test
    fun presetNativeSyncSendsNoPatchCells() {
        writes.clear()
        controller.nativeSyncCallback!!.invoke()
        assertEquals(0, patchWrites().size)
    }

    @Test
    fun nativeRestartResendsEveryCell() {
        writes.clear()
        engine.syncToNative()
        assertEquals(PATCH_CELLS * 6, patchWrites().size)
    }

    @Test
    fun fmStructureToggleReachesNative() {
        writes.clear()
        voicePlugin.setPortValue(VoiceSymbol.FM_STRUCTURE_CROSS_QUAD.symbol, PortValue.BoolValue(true))
        assertTrue(
            Triple(VoicePlugin.MODULATION_URI, "fm_cross_quad", 1f) in writes,
            "the live toggle must write fm_cross_quad, saw $writes",
        )
    }

    private fun buildEngine(): DspSynthEngine {
        val audio = bridge as AudioEngine
        val pluginProvider = DspPluginProvider(
            setOf(voicePlugin, FluxPlugin(audio), StereoPlugin(audio), ResonatorPlugin(audio), DrumPlugin(audio)),
        )
        val dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val unconfined = Dispatchers.Unconfined
        }
        return DspSynthEngine(
            audioEngine = audio,
            pluginProvider = pluginProvider,
            dispatcherProvider = dispatchers,
            globalTempo = GlobalTempo(audio),
            voiceManager = DspVoiceManager(pluginProvider),
            synthController = controller,
            wiringGraphProvider = WiringGraphProvider { ByteArray(0) },
            appCoroutineScope = AppCoroutineScope(dispatchers),
        )
    }
}

/** An inert AudioEngine + NativeDspBridge that records AudioEngine.setPort calls. */
private fun recordingBridge(writes: MutableList<Triple<String, String, Float>>): Any =
    Proxy.newProxyInstance(
        AudioEngine::class.java.classLoader,
        arrayOf(AudioEngine::class.java, NativeDspBridge::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "setPort" -> { writes += Triple(args[0] as String, args[1] as String, args[2] as Float); null }
            "getSampleRate" -> 48_000
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            "toString" -> "RecordingBridge"
            else -> when (method.returnType) {
                Int::class.javaPrimitiveType -> 0
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
    }
