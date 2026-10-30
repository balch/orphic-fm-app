package org.balch.orpheus.features.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspPluginProvider
import org.balch.orpheus.core.audio.dsp.DspSynthEngine
import org.balch.orpheus.core.audio.dsp.DspVoiceManager
import org.balch.orpheus.core.audio.dsp.NativeDspBridge
import org.balch.orpheus.core.audio.dsp.WiringGraphProvider
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.DuoPatchBay
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.PATCH_URI
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.core.audio.patch.duoModSourceLegacyKey
import org.balch.orpheus.core.audio.patch.duoPatchKey
import org.balch.orpheus.core.audio.patch.legacyDuoPatch
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.VoicePlugin
import org.balch.orpheus.plugins.flux.FluxPlugin
import org.balch.orpheus.plugins.perstringbender.PerStringBenderPlugin
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.stereo.StereoPlugin
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** ./gradlew :features:voice:jvmTest --tests '*DuoPatchPresetSyncTest*' */
@OptIn(ExperimentalCoroutinesApi::class)
class DuoPatchPresetSyncTest {

    private fun loader(registry: PortRegistry = PortRegistry(emptySet())) =
        PresetLoader(registry, GlobalTempo(SilentAudioEngine()), SynthController())

    private fun TestScope.appScope() =
        AppCoroutineScope(SingleDispatcherProvider(StandardTestDispatcher(testScheduler)))

    @Test
    fun presetRestoresPatchWithoutPanel() = runTest {
        val loader = loader()
        val bay = FakeBay()
        val scope = appScope()
        DuoPatchPresetSync(loader, bay, scope)
        runCurrent()

        val p = DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.4f)
        loader.applyPreset(SynthPreset("saved", portValues = mapOf(duoPatchKey(2) to PortValue.StringValue(p.encode()))))
        runCurrent()

        assertEquals(p, bay.patches.value[2])
        scope.cancel()
    }

    @Test
    fun legacyPresetConverts() = runTest {
        val loader = loader()
        val bay = FakeBay()
        val scope = appScope()
        DuoPatchPresetSync(loader, bay, scope)
        runCurrent()

        loader.applyPreset(
            SynthPreset(
                "legacy",
                portValues = mapOf(
                    duoModSourceLegacyKey(2) to PortValue.IntValue(2),
                    VoiceSymbol.duoEngine(2).controlId.key to PortValue.IntValue(OrpheusEngineId.VA.id),
                ),
            )
        )
        runCurrent()

        assertEquals(legacyDuoPatch(LegacyModSource.LFO, onDx = false, duoIndex = 2), bay.patches.value[2])
        scope.cancel()
    }

    @Test
    fun patchChangeWritesFeatureValue() = runTest {
        val loader = loader()
        val bay = FakeBay()
        val scope = appScope()
        DuoPatchPresetSync(loader, bay, scope)
        runCurrent()

        val p = DuoPatch().connect(PatchSource.WAVES_2, PatchDest.TIMBRE, amount = 0.7f)
        bay.setPatch(1, p)
        runCurrent()

        assertEquals(
            PortValue.StringValue(p.encode()),
            loader.currentStateAsPreset("now").portValues[duoPatchKey(1)],
        )
        scope.cancel()
    }

    @Test
    fun savedWokenHarmonicsSurvivesPresetLoad() = runTest {
        val plugin = VoicePlugin(SilentAudioEngine())
        val loader = loader(PortRegistry(setOf(plugin)))
        val scope = appScope()
        DuoPatchPresetSync(loader, plugin, scope)
        runCurrent()

        val woken = legacyDuoPatch(LegacyModSource.LFO, onDx = true, duoIndex = 0)
            .wake(PatchSource.LFO_B, PatchDest.HARMONICS, VoiceSlot.BOTH)
        loader.applyPreset(
            SynthPreset(
                "woken",
                portValues = mapOf(
                    VoiceSymbol.duoEngine(0).controlId.key to PortValue.IntValue(OrpheusEngineId.DX.id),
                    duoPatchKey(0) to PortValue.StringValue(woken.encode()),
                ),
            )
        )
        runCurrent()

        assertEquals(OrpheusEngineId.DX, plugin.engineOf(0))
        val harmonics = plugin.patches.value[0].cables.single { it.dest == PatchDest.HARMONICS }
        assertFalse(harmonics.parked, "a saved woken HARMONICS cable on DX must stay awake")
        scope.cancel()
    }

    @Test
    fun presetChangingOneCableSendsOnlyThatDuosCells() = runTest {
        val writes = mutableListOf<Triple<String, String, Float>>()
        val audio = recordingBridge(writes) as AudioEngine
        val voice = VoicePlugin(audio)
        val resonator = ResonatorPlugin(audio)
        val plugins = setOf(
            voice, FluxPlugin(audio), StereoPlugin(audio), resonator, DrumPlugin(audio),
            PerStringBenderPlugin(audio, resonator),
        )
        val provider = DspPluginProvider(plugins)
        val controller = SynthController()
        val engineScope = AppCoroutineScope(SingleDispatcherProvider(Dispatchers.Unconfined))
        DspSynthEngine(
            audioEngine = audio,
            pluginProvider = provider,
            dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
            globalTempo = GlobalTempo(audio),
            voiceManager = DspVoiceManager(provider),
            synthController = controller,
            wiringGraphProvider = WiringGraphProvider { ByteArray(0) },
            appCoroutineScope = engineScope,
        )
        val loader = PresetLoader(PortRegistry(plugins), GlobalTempo(audio), controller)
        val scope = appScope()
        DuoPatchPresetSync(loader, voice, scope)
        runCurrent()
        val base = loader.currentStateAsPreset("base")
        loader.applyPreset(base)
        runCurrent()
        writes.clear()

        val changed = voice.patches.value[4].connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.6f)
        loader.applyPreset(base.copy(portValues = base.portValues + (duoPatchKey(4) to PortValue.StringValue(changed.encode()))))
        runCurrent()

        val patchWrites = writes.filter { it.first == PATCH_URI }
        assertEquals(listOf("p4_0_1_2", "p4_1_1_2"), patchWrites.map { it.second }.sorted())
        assertEquals(changed, voice.patches.value[4])
        scope.cancel()
        engineScope.cancel()
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

private class FakeBay : DuoPatchBay {
    private val _patches = MutableStateFlow(List(6) { DuoPatch() })
    override val patches: StateFlow<List<DuoPatch>> = _patches.asStateFlow()
    override fun setPatch(duo: Int, patch: DuoPatch) {
        _patches.value = _patches.value.toMutableList().also { it[duo] = patch }
    }
    override fun update(duo: Int, transform: (DuoPatch) -> DuoPatch) = setPatch(duo, transform(_patches.value[duo]))
    override fun applyLegacySource(duo: Int, source: LegacyModSource) =
        setPatch(duo, legacyDuoPatch(source, onDx = false, duoIndex = duo))
    override fun engineOf(duo: Int): OrpheusEngineId? = null
}

private class SingleDispatcherProvider(d: CoroutineDispatcher) : DispatcherProvider {
    override val main = d
    override val io = d
    override val default = d
    override val unconfined = d
}

private class SilentAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning = false
    override val sampleRate = 48_000
    override fun getCpuLoad() = 0f
    override fun getCurrentTime() = 0.0
}
