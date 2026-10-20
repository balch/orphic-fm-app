package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.vibes.VibeCatalogScan
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * First half of the vibe recorder: drives the real [PulsarViewModel] through a vibe load
 * with Mode One on (a name-hash seed, so every run rolls the same song) and writes every
 * port write, in order, to `features/pulsar/build/vibe-recordings/<slug>.ports`. The C++
 * `vibe_record` suite replays the file into the DJ graph and writes per-track WAVs.
 *
 * Kotlin re-pushes some track settings (volume, morph, envelope, sends) when a section
 * starts, so those are dumped per section as `#section` rows for the replay to apply on
 * each flip.
 *
 * RECORD_VIBE="Orphic Lore" ./gradlew :features:pulsar:jvmTest \
 *     --tests '*VibePortDumpRenderHarness*' -PrenderHarness --rerun
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VibePortDumpRenderHarness {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()
    private val writes = mutableListOf<String>()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun dumpVibePortWrites() = runTest(testDispatcher) {
        val name = System.getenv("RECORD_VIBE")?.takeIf { it.isNotBlank() } ?: "Orphic Lore"
        val provider = VibeCatalogScan.allProviders().firstOrNull { it.vibe.name == name }
            ?: error("No vibe named '$name'")
        val vibe = provider.vibe
        val engine = SongEndingStubSynthEngine()
        val vm = makeViewModel(engine, provider)

        vm.actions.onToggleModeOne()
        vm.actions.setVibe(vibe)
        advanceUntilIdle()

        val out = StringBuilder()
        writes.forEach { out.append(it).append('\n') }
        writes.clear()

        // Feed each section index through the engine's arrangement poll and keep what Kotlin
        // re-pushes for it.
        val sections = vibe.arrangement?.sections.orEmpty()
        for (s in sections.indices) {
            engine.pulsarArrangementStateFlow.value = PulsarArrangementState(
                sectionIndex = s, barsElapsed = 0, barsTotal = 0,
                soloActive = false, soloTrack = -1, soloMode = 0,
            )
            advanceUntilIdle()
            writes.forEach { out.append("#section\t").append(s).append('\t').append(it).append('\n') }
            writes.clear()
        }

        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val dir = File("build/vibe-recordings").apply { mkdirs() }
        val file = File(dir, "$slug.ports")
        file.writeText(out.toString())
        File(dir, "$slug.tsv").writeText(
            "name\t$name\nbpm\t${vibe.bpm}\nsections\t${sections.joinToString(",") { it.name }}\n"
        )
        println("Wrote ${file.absolutePath}")
    }

    private fun makeViewModel(engine: SongEndingStubSynthEngine, provider: org.balch.orpheus.features.pulsar.models.VibeProvider): PulsarViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value ->
                    ports["${id.uri}:${id.symbol}"] = value
                    val f = when (value) {
                        is PortValue.FloatValue -> value.value
                        is PortValue.IntValue -> value.value.toFloat()
                        is PortValue.BoolValue -> if (value.value) 1f else 0f
                        else -> null
                    }
                    if (f != null) writes += "${id.uri}\t${id.symbol}\t$f"
                    true
                },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val tempo = GlobalTempo(RecorderAudioEngine())
        val dispatchers = FixturesDispatchers(testDispatcher)
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), dispatchers),
            globalTempo = tempo,
            appPreferencesRepository = RecorderPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(provider),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
        )
    }
}

private class RecorderAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 48000
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class RecorderPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}
