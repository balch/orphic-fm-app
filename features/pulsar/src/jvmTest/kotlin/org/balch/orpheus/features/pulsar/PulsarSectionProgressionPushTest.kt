package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.FadeCurve
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.anonmalies.Anomaly
import org.balch.orpheus.features.pulsar.anonmalies.StormAnomaly
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.ChordStep
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.ScratchEffect
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SectionWeather
import org.balch.orpheus.features.pulsar.models.StrikeEffect
import org.balch.orpheus.features.pulsar.models.TapeStopEffect
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the per-section override push path in [PulsarViewModel.pushArrangement].
 * The reviewer flagged a (false-positive) concern that section-level
 * customProgression / chordsPerBar / compingHumanization were silently dropped;
 * these tests pin the behavior so a future refactor can't regress it.
 *
 * Each test loads a vibe whose Section carries a non-default override field,
 * then asserts that the corresponding `section_progression_*`,
 * `section_chords_per_bar_*`, or `section_comping_humanization_*` symbol
 * received the right value through the SynthController.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSectionProgressionPushTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()
    private val arrangementFlow =
        MutableStateFlow<PulsarArrangementState?>(ARRANGEMENT_STATE_UNKNOWN)

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun makeViewModel(vibe: Vibe): PulsarViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value ->
                    ports["${id.uri}:${id.symbol}"] = value
                    true
                },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val tempo = GlobalTempo(PushTestAudioEngine())
        val portRegistry = PortRegistry(emptySet())
        val engine = PushTestSynthEngine(arrangementFlow)
        val appScope = makeAppCoroutineScope(testDispatcher)
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, appScope, PushTestDispatchers(testDispatcher)),
            globalTempo = tempo,
            appPreferencesRepository = PushTestPrefs(),
            presetLoader = PresetLoader(portRegistry, tempo, controller),
            dispatcherProvider = PushTestDispatchers(testDispatcher),
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(PushTestVibeProvider(vibe)),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
        )
    }

    private fun port(symbol: String): PortValue? =
        ports["$PULSAR_URI:$symbol"]

    private fun intPort(symbol: String): Int? = (port(symbol) as? IntValue)?.value
    private fun floatPort(symbol: String): Float? = (port(symbol) as? FloatValue)?.value

    @Test
    fun `section customProgression and chordsPerBar reach controller`() = runTest(testDispatcher) {
        // Section 0: glide-rich progression with explicit chordsPerBar=4.
        // Section 1: no overrides — should serialize as length=0 / chordsPerBar=0.
        val s0Progression = listOf(
            ChordStep(0),
            ChordStep(3, glideRate = 0.45f),
            ChordStep(5),
            ChordStep(4),
        )
        val vibe = pushTestVibe(
            sections = listOf(
                Section(
                    name = "verse",
                    barsMin = 4, barsMax = 4,
                    customProgression = s0Progression,
                    chordsPerBar = 4,
                ),
                Section(name = "chorus", barsMin = 4, barsMax = 4),
            ),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        // Section 0: length encodes the override count; degree+glide arrays match.
        assertEquals(s0Progression.size, intPort("section_progression_active_0"),
            "section 0 progression length should be ${s0Progression.size}")
        assertEquals(4, intPort("section_chords_per_bar_0"),
            "section 0 chordsPerBar override should be 4")
        s0Progression.forEachIndexed { i, step ->
            assertEquals(step.degree, intPort("section_progression_degree_${i}"),
                "section 0 degree[$i]")
            assertEquals(step.glideRate, floatPort("section_progression_glide_${i}"),
                "section 0 glide[$i]")
        }

        // Section 1: no overrides — sentinels are 0 (no override) per engine.h:864/870.
        assertEquals(0, intPort("section_progression_active_1"),
            "section 1 progression length should be 0 (no override)")
        assertEquals(0, intPort("section_chords_per_bar_1"),
            "section 1 chordsPerBar override should be 0 (inherit vibe default)")
    }

    @Test
    fun `section compingHumanization override reaches controller`() = runTest(testDispatcher) {
        val humanization = CompingHumanization(
            dropProbability = 0.18f,
            ghostProbability = 0.22f,
            octaveJumpProbability = 0.12f,
            extensionProbability = 0.15f,
        )
        val vibe = pushTestVibe(
            sections = listOf(
                Section(
                    name = "loose",
                    barsMin = 4, barsMax = 4,
                    compingHumanization = humanization,
                ),
                Section(name = "tight", barsMin = 4, barsMax = 4),
            ),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        // Section 0: active=1, all four floats packed in order.
        assertEquals(1, intPort("section_comping_humanization_active_0"),
            "section 0 humanization active flag should be 1")
        assertEquals(humanization.dropProbability,    floatPort("section_comping_humanization_data_0"))
        assertEquals(humanization.ghostProbability,   floatPort("section_comping_humanization_data_1"))
        assertEquals(humanization.octaveJumpProbability, floatPort("section_comping_humanization_data_2"))
        assertEquals(humanization.extensionProbability,  floatPort("section_comping_humanization_data_3"))

        // Section 1: active=0; data slots stay at their initialized 0.
        assertEquals(0, intPort("section_comping_humanization_active_1"),
            "section 1 humanization active flag should be 0")
    }

    @Test
    fun `section jamCarry is pushed to section_data slot 16`() = runTest(testDispatcher) {
        val sections = listOf(
            Section(name = "verse", barsMin = 2, barsMax = 2,
                transitions = listOf(SectionTransition(1, 1f))),
            Section(name = "jam", barsMin = 2, barsMax = 2, jamCarry = true,
                transitions = listOf(SectionTransition(0, 1f))),
        )
        val vibe = pushTestVibe(sections = sections)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0f, floatPort("section_data_${0 * Arrangement.SECTION_DATA_FIELDS + 16}"))
        assertEquals(1f, floatPort("section_data_${1 * Arrangement.SECTION_DATA_FIELDS + 16}"))
    }

    /**
     * One push exercising all four storm-weather banks together: a section weather bed,
     * a strike effect on a transition edge, a declared StormAnomaly, and a breathe
     * override on one track. Pins exact floats at exact wire indices, plus zero-fill of
     * every unauthored slot (stale-bank hygiene, matching the existing anomaly banks).
     */
    @Test
    fun `storm weather trans-fx and breathe banks reach the controller`() = runTest(testDispatcher) {
        val weather = SectionWeather(
            rain = 0.6f, rumble = 0.4f, strikeChance = 0.25f, distance = 0.3f, rainLevel = 0.7f,
        )
        val strike = StrikeEffect(intensity = 0.9f, distance = 0.1f, offsetBars = -2f, delayMs = 250)
        val storm = StormAnomaly(
            probability = 0.15f, durationBarsMin = 1, durationBarsMax = 3,
            intensity = 0.8f, distance = 0.35f,
        )
        val breathe = TrackSectionOverride(breatheBars = 4, breatheFloor = 0.2f, breatheTimbreSpan = 0.5f)

        val vibe = pushTestVibe(
            sections = listOf(
                Section(
                    name = "storm-verse", barsMin = 4, barsMax = 4,
                    weather = weather,
                    trackOverrides = mapOf(2 to breathe),
                    transitions = listOf(SectionTransition(targetIndex = 1, weight = 1f, effects = listOf(strike))),
                ),
                Section(name = "calm", barsMin = 4, barsMax = 4),
            ),
            anomalies = listOf(storm),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        // --- Section weather: section 0 slots 21-25, section 1 all-zero (no weather). ---
        val stride = Arrangement.SECTION_DATA_FIELDS
        assertEquals(weather.rain, floatPort("section_data_${0 * stride + 21}"))
        assertEquals(weather.rumble, floatPort("section_data_${0 * stride + 22}"))
        assertEquals(weather.strikeChance, floatPort("section_data_${0 * stride + 23}"))
        assertEquals(weather.distance, floatPort("section_data_${0 * stride + 24}"))
        assertEquals(weather.rainLevel, floatPort("section_data_${0 * stride + 25}"))
        assertEquals(0f, floatPort("section_data_${1 * stride + 21}"), "section 1 declares no weather")
        assertEquals(0f, floatPort("section_data_${1 * stride + 24}"), "section 1 declares no weather")
        // rainLevel's authoring default is 1f, but an absent bed marshals the whole row
        // as zero — the encoding C++ reads as "no weather", not "silent rain at rate 0".
        assertEquals(0f, floatPort("section_data_${1 * stride + 25}"), "section 1 declares no weather")

        // --- Trans-fx: row 0 = [section=0, edge=0, type=STRIKE, offset=-2, intensity, distance,
        // delayMs]. p2 is the strike's sub-bar delay in MILLISECONDS, not samples or beats:
        // C++ converts at the sample rate this side has no view of. ---
        assertEquals(0f, floatPort("trans_fx_data_0"), "row 0 section")
        assertEquals(0f, floatPort("trans_fx_data_1"), "row 0 edge")
        assertEquals(TransitionFxWire.TYPE_STRIKE.toFloat(), floatPort("trans_fx_data_2"))
        assertEquals(strike.offsetBars, floatPort("trans_fx_data_3"))
        assertEquals(strike.intensity, floatPort("trans_fx_data_4"))
        assertEquals(strike.distance, floatPort("trans_fx_data_5"))
        assertEquals(strike.delayMs.toFloat(), floatPort("trans_fx_data_6"), "strike delay ms -> p2")
        // Every remaining row must be zero-filled padding (stale-bank hygiene).
        for (i in TransitionFxWire.ROW_FIELDS until TransitionFxWire.BANK_SIZE) {
            assertEquals(0f, floatPort("trans_fx_data_$i"), "trans_fx_data_$i should be zeroed padding")
        }

        // --- Storm anomaly bank: [probability, durMin, durMax, intensity, distance, declared=1]. ---
        assertEquals(storm.probability, floatPort("storm_data_0"))
        assertEquals(storm.durationBarsMin.toFloat(), floatPort("storm_data_1"))
        assertEquals(storm.durationBarsMax.toFloat(), floatPort("storm_data_2"))
        assertEquals(storm.intensity, floatPort("storm_data_3"))
        assertEquals(storm.distance, floatPort("storm_data_4"))
        assertEquals(1f, floatPort("storm_data_5"), "declared flag")

        // --- Breathe: track 2/section 0 carries the override; track 3/section 0 stays zero. ---
        val overriddenIdx = 0 * 8 + 2
        assertEquals(breathe.breatheBars.toFloat(), floatPort("section_track_breathe_bars_$overriddenIdx"))
        assertEquals(breathe.breatheFloor, floatPort("section_track_breathe_floor_$overriddenIdx"))
        assertEquals(breathe.breatheTimbreSpan, floatPort("section_track_breathe_timbre_span_$overriddenIdx"))
        val plainIdx = 0 * 8 + 3
        assertEquals(0f, floatPort("section_track_breathe_bars_$plainIdx"))
        assertEquals(0f, floatPort("section_track_breathe_floor_$plainIdx"))
        assertEquals(0f, floatPort("section_track_breathe_timbre_span_$plainIdx"))
    }

    /**
     * `Section.exitEffects` fires on EVERY outgoing edge, so it costs exactly one wildcard
     * row (edge = -1) no matter how many transitions the section declares — pre-expanding
     * one row per edge would burn the 24-row bank on a section with a fan-out.
     */
    @Test
    fun `section exitEffects emit one wildcard row ahead of the per-edge rows`() = runTest(testDispatcher) {
        val exit = TapeStopEffect(ms = 650)
        val edgeStrike = StrikeEffect(intensity = 0.7f, distance = 0.3f, offsetBars = -1f)
        val vibe = pushTestVibe(
            sections = listOf(
                Section(
                    name = "exit", barsMin = 4, barsMax = 4,
                    exitEffects = listOf(exit),
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 1f, effects = listOf(edgeStrike)),
                        SectionTransition(targetIndex = 2, weight = 1f),
                    ),
                ),
                Section(name = "a", barsMin = 4, barsMax = 4),
                Section(name = "b", barsMin = 4, barsMax = 4),
            ),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        // Row 0 is the section row: emitted before the per-edge rows so it wins a pending
        // slot first if the 24-row cap ever truncates.
        assertEquals(0f, floatPort("trans_fx_data_0"), "row 0 section")
        assertEquals(-1f, floatPort("trans_fx_data_1"), "row 0 edge must be the -1 wildcard")
        assertEquals(TransitionFxWire.TYPE_TAPE_STOP.toFloat(), floatPort("trans_fx_data_2"))
        assertEquals(0f, floatPort("trans_fx_data_3"), "tape stop fires at the flip")
        assertEquals(exit.ms.toFloat(), floatPort("trans_fx_data_4"))
        assertEquals(0f, floatPort("trans_fx_data_5"))
        assertEquals(0f, floatPort("trans_fx_data_6"))

        // Row 1 is the edge row on edge 0 — two transitions, but the wildcard did not expand.
        val r1 = TransitionFxWire.ROW_FIELDS
        assertEquals(0f, floatPort("trans_fx_data_$r1"), "row 1 section")
        assertEquals(0f, floatPort("trans_fx_data_${r1 + 1}"), "row 1 edge 0")
        assertEquals(TransitionFxWire.TYPE_STRIKE.toFloat(), floatPort("trans_fx_data_${r1 + 2}"))
        assertEquals(edgeStrike.offsetBars, floatPort("trans_fx_data_${r1 + 3}"))
        assertEquals(edgeStrike.intensity, floatPort("trans_fx_data_${r1 + 4}"))
        assertEquals(edgeStrike.distance, floatPort("trans_fx_data_${r1 + 5}"))

        // Exactly two rows: everything past row 1 is zeroed padding.
        for (i in 2 * TransitionFxWire.ROW_FIELDS until TransitionFxWire.BANK_SIZE) {
            assertEquals(0f, floatPort("trans_fx_data_$i"), "trans_fx_data_$i should be zeroed padding")
        }
    }

    /**
     * `Section.entryEffects` fires on every ARRIVAL, so like `exitEffects` it costs exactly
     * one row (edge = -2) no matter how many edges lead into the section — pre-expanding one
     * row per inbound edge would burn the 24-row bank on a section everything routes to.
     */
    @Test
    fun `section entryEffects emit one entry-sentinel row regardless of inbound edges`() = runTest(testDispatcher) {
        val entry = ScratchEffect(ms = 320)
        val vibe = pushTestVibe(
            sections = listOf(
                Section(
                    name = "a", barsMin = 4, barsMax = 4,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 1f),
                        SectionTransition(targetIndex = 2, weight = 1f),
                    ),
                ),
                Section(
                    name = "b", barsMin = 4, barsMax = 4,
                    transitions = listOf(SectionTransition(targetIndex = 2, weight = 1f)),
                ),
                // Two inbound edges (from a and from b), one entry row.
                Section(name = "arrival", barsMin = 4, barsMax = 4, entryEffects = listOf(entry)),
            ),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(2f, floatPort("trans_fx_data_0"), "row 0 names the ARRIVING section")
        assertEquals(-2f, floatPort("trans_fx_data_1"), "row 0 edge must be the -2 entry sentinel")
        assertEquals(TransitionFxWire.TYPE_SCRATCH.toFloat(), floatPort("trans_fx_data_2"))
        assertEquals(0f, floatPort("trans_fx_data_3"), "scratch fires on the section's downbeat")
        assertEquals(entry.ms.toFloat(), floatPort("trans_fx_data_4"))

        // Exactly one row: the three inbound-edge writes did not each add their own.
        for (i in TransitionFxWire.ROW_FIELDS until TransitionFxWire.BANK_SIZE) {
            assertEquals(0f, floatPort("trans_fx_data_$i"), "trans_fx_data_$i should be zeroed padding")
        }
    }

    /**
     * A vibe with NO storm anomaly, weather, or breathe overrides must still zero every
     * slot of all four banks — guards the "declared => probability 0" / "null => all-zero"
     * hygiene against a future refactor that only writes on presence.
     */
    @Test
    fun `absent storm weather and breathe banks are all zero`() = runTest(testDispatcher) {
        val vibe = pushTestVibe(
            sections = listOf(
                Section(name = "plain", barsMin = 4, barsMax = 4),
            ),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        for (i in 0 until TransitionFxWire.BANK_SIZE) {
            assertEquals(0f, floatPort("trans_fx_data_$i"), "trans_fx_data_$i")
        }
        // Only probability[0] and declared[5] go to 0 when absent — indices 1-4 fall back
        // to StormAnomaly's OWN ship defaults, same convention as every sibling anomaly
        // bank (e.g. WahAnomaly absent still writes its default rateDivision, not zero).
        assertEquals(0f, floatPort("storm_data_0"), "probability")
        assertEquals(1f, floatPort("storm_data_1"), "durationBarsMin default")
        assertEquals(2f, floatPort("storm_data_2"), "durationBarsMax default")
        assertEquals(0.7f, floatPort("storm_data_3"), "intensity default")
        assertEquals(0.4f, floatPort("storm_data_4"), "distance default")
        assertEquals(0f, floatPort("storm_data_5"), "declared flag")
        val stride = Arrangement.SECTION_DATA_FIELDS
        for (field in 21..25) {
            assertEquals(0f, floatPort("section_data_${0 * stride + field}"), "weather field $field")
        }
        for (field in listOf("bars", "floor", "timbre_span")) {
            assertEquals(0f, floatPort("section_track_breathe_${field}_0"), "breathe $field")
        }
    }

    /**
     * Regression: `pushArrangement`'s no-arrangement early return used to skip the
     * trans-fx flush entirely, so a PREVIOUS vibe's staged strike/scratch rows stayed
     * resident in `pulsar_trans_fx_data` after switching to an arrangement-less vibe —
     * contradicting the bank's own writer contract (no count field of its own; every
     * apply must zero every unauthored row, see the comment on `pushTransFxBank`).
     */
    @Test
    fun `switching to an arrangement-less vibe zeroes the trans-fx bank`() = runTest(testDispatcher) {
        val stormy = pushTestVibe(
            sections = listOf(
                Section(
                    name = "storm-verse", barsMin = 4, barsMax = 4,
                    transitions = listOf(
                        SectionTransition(
                            targetIndex = 1, weight = 1f,
                            effects = listOf(StrikeEffect(intensity = 0.9f, distance = 0.1f, offsetBars = -2f)),
                        ),
                    ),
                ),
                Section(name = "calm", barsMin = 4, barsMax = 4),
            ),
        )
        val vm = makeViewModel(stormy)
        vm.actions.setVibe(stormy)
        advanceUntilIdle()
        // Precondition: the strike row actually landed before the no-arrangement switch,
        // otherwise the assertions below would trivially pass on a bank that was never
        // dirtied in the first place.
        assertEquals(
            TransitionFxWire.TYPE_STRIKE.toFloat(), floatPort("trans_fx_data_2"),
            "precondition: the stormy vibe must actually stage a trans-fx row",
        )

        vm.actions.setVibe(noArrangementTestVibe())
        advanceUntilIdle()

        for (i in 0 until TransitionFxWire.BANK_SIZE) {
            assertEquals(
                0f, floatPort("trans_fx_data_$i"),
                "trans_fx_data_$i must be zeroed after switching to an arrangement-less vibe",
            )
        }
    }

    /**
     * A vibe switch must apply the incoming vibe's opening section even though the section index
     * did not change — the collector is `distinctUntilChanged` on sectionIndex and nothing resets
     * `_arrangementState` on load, so section 0 -> section 0 is suppressed.
     *
     * The two vibes cut DIFFERENT slots, which is what makes this discriminating: without the
     * synchronous push in [PulsarViewModel.applyVibe] the recipe restores every track to its base
     * volume and slot 3 stays audible.
     */
    @Test
    fun `switching vibes on the same section index still applies the incoming intro`() =
        runTest(testDispatcher) {
            fun vibeCutting(slot: Int) = pushTestVibe(
                sections = listOf(
                    Section(
                        name = "intro", barsMin = 4, barsMax = 4,
                        trackOverrides = mapOf(slot to TrackSectionOverride(volume = 0f)),
                    ),
                    Section(name = "groove", barsMin = 4, barsMax = 4),
                ),
            )
            val cutsLead = vibeCutting(4)
            val cutsBass = vibeCutting(3)
            val baseVolume = cutsLead.tracks[4].engineEdm.volume
            assertTrue(baseVolume > 0f, "precondition: the fixture's base volume must be audible")

            val vm = makeViewModel(cutsLead)
            vm.actions.setVibe(cutsLead)
            advanceUntilIdle()

            // Put the engine on section 0 and let the collector apply the first vibe's intro.
            arrangementFlow.value = PulsarArrangementState(
                sectionIndex = 0, barsElapsed = 0, barsTotal = 4,
                soloActive = false, soloTrack = -1, soloMode = 0,
            )
            advanceUntilIdle()
            assertEquals(0f, floatPort("track_4_volume"), "the first vibe's intro must cut slot 4")

            // Switch vibes WITHOUT the engine ever leaving section 0 — the suppressed case.
            vm.actions.setVibe(cutsBass)
            advanceUntilIdle()

            assertEquals(
                0f, floatPort("track_3_volume"),
                "the incoming vibe's intro must cut slot 3; a non-zero here means its opening " +
                    "section was never applied",
            )
            assertEquals(
                baseVolume, floatPort("track_4_volume"),
                "slot 4 must be restored, not left at the previous vibe's 0",
            )
        }

    /**
     * The vibe-load push deliberately skips the pattern-generation inputs. `density` and the hold
     * parameters reach the engine only through `load_vibe` and the déjà-vu regeneration, so the
     * opening section's sparseness would outlive that section by up to a déjà-vu interval.
     */
    @Test
    fun `the vibe-load push leaves the pattern-generation inputs at the vibe's base values`() =
        runTest(testDispatcher) {
            val vibe = pushTestVibe(
                sections = listOf(
                    Section(
                        name = "intro", barsMin = 4, barsMax = 4,
                        trackOverrides = mapOf(
                            5 to TrackSectionOverride(volume = 0f, density = 0f),
                        ),
                    ),
                    Section(name = "groove", barsMin = 4, barsMax = 4),
                ),
            )
            val baseDensity = vibe.tracks[5].density
            assertTrue(baseDensity > 0f, "precondition: the fixture's base density must be non-zero")

            makeViewModel(vibe).actions.setVibe(vibe)
            advanceUntilIdle()  // no arrangement emission at all — only the synchronous push ran

            assertEquals(
                0f, floatPort("track_5_volume"),
                "slot 5 must be silenced by the vibe-load push",
            )
            assertEquals(
                baseDensity, floatPort("genre_density_5"),
                "slot 5's density must stay at the vibe's base so load_vibe builds the full " +
                    "pattern; the intro's 0 would persist into every later section",
            )
        }

    /**
     * C++ resolves volume and sends with PULSAR_PICK, reading the `_space` port whenever the
     * track's Space engine is the live one. An override written only to the EDM port is
     * silently ignored on such a track, so both slots must carry it.
     */
    @Test
    fun `section overrides reach the Space slot as well as the EDM slot`() =
        runTest(testDispatcher) {
            val vibe = pushTestVibe(
                sections = listOf(
                    Section(
                        name = "intro", barsMin = 4, barsMax = 4,
                        trackOverrides = mapOf(
                            6 to TrackSectionOverride(volume = 0f, delaySend = 0f, reverbSend = 0f),
                        ),
                    ),
                    Section(name = "groove", barsMin = 4, barsMax = 4),
                ),
            )
            assertTrue(
                vibe.tracks[6].engineSpace.volume > 0f,
                "precondition: the fixture's Space-slot volume must be audible",
            )

            makeViewModel(vibe).actions.setVibe(vibe)
            advanceUntilIdle()

            assertEquals(
                0f, floatPort("track_6_volume_space"),
                "the Space slot must be silenced too; C++ reads it whenever the Space engine " +
                    "is live, so an EDM-only write leaves the track audible",
            )
            assertEquals(0f, floatPort("track_6_delay_send_space"))
            assertEquals(0f, floatPort("track_6_reverb_send_space"))
        }
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

private fun pushTestVibe(sections: List<Section>, anomalies: List<Anomaly> = emptyList()): Vibe = Vibe(
    name = "Section Push Test",
    bpm = 120f,
    rootNote = RootNote.C,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f, ghostProbability = 0f,
        noteRangeLow = 36, noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    tracks = List(8) {
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
        )
    },
    arrangement = Arrangement(sections = sections),
    anomalies = anomalies,
)

/** A vibe with `arrangement = null` (its default) — exercises pushArrangement's early return. */
private fun noArrangementTestVibe(): Vibe = Vibe(
    name = "No Arrangement Push Test",
    bpm = 120f,
    rootNote = RootNote.C,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f, ghostProbability = 0f,
        noteRangeLow = 36, noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    tracks = List(8) {
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
        )
    },
)

private class PushTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}

private class PushTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class PushTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class PushTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

// SynthEngine stub. Mirrors the no-op pattern used by PulsarSectionBpmTest.
private class PushTestSynthEngine(
    private val arrangement: MutableStateFlow<PulsarArrangementState?>,
) : SynthEngine {
    override val pulsarArrangementStateFlow: StateFlow<PulsarArrangementState?> get() = arrangement
    override fun start() = Unit
    override fun stop() = Unit
    override fun setVoiceTune(index: Int, tune: Float) = Unit
    override fun setVoiceGate(index: Int, active: Boolean) = Unit
    override fun setVoiceFeedback(index: Int, amount: Float) = Unit
    override fun setVoiceFmDepth(index: Int, amount: Float) = Unit
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) = Unit
    override fun setDuoSharpness(duoIndex: Int, sharpness: Float) = Unit
    override fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) = Unit
    override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) = Unit
    override fun triggerDrum(type: Int, accent: Float) = Unit
    override fun setQuadPitch(quadIndex: Int, pitch: Float) = Unit
    override fun setQuadHold(quadIndex: Int, amount: Float) = Unit
    override fun setQuadVolume(quadIndex: Int, volume: Float) = Unit
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) = Unit
    override fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) = Unit
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) = Unit
    override fun getQuadPitch(quadIndex: Int): Float = 0f
    override fun getQuadHold(quadIndex: Int): Float = 0f
    override fun getQuadVolume(quadIndex: Int): Float = 0f
    override fun getQuadTriggerSource(quadIndex: Int): Int = 0
    override fun getQuadPitchSource(quadIndex: Int): Int = 0
    override fun getQuadEnvelopeTriggerMode(quadIndex: Int): Boolean = false
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) = Unit
    override fun setVoiceHold(index: Int, amount: Float) = Unit
    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) = Unit
    override fun setDrive(amount: Float) = Unit
    override fun setDistortionMix(amount: Float) = Unit
    override fun setMasterVolume(amount: Float) = Unit
    override fun fadeMasterVolume(target: Float, durationMs: Int, curve: FadeCurve) = Unit
    override fun masterTapeStop(durationMs: Int) = Unit
    override fun masterScratch(durationMs: Int) = Unit
    override fun masterFilter(durationMs: Int) = Unit
    override fun setDelayTime(index: Int, time: Float) = Unit
    override fun setDelayFeedback(amount: Float) = Unit
    override fun setDelayMix(amount: Float) = Unit
    override fun setDelayModDepth(index: Int, amount: Float) = Unit
    override fun setHyperLfoFreq(index: Int, frequency: Float) = Unit
    override fun setHyperLfoMode(mode: Int) = Unit
    override fun setHyperLfoLink(active: Boolean) = Unit
    override fun getHyperLfoFreq(index: Int): Float = 0f
    override fun getHyperLfoMode(): Int = 0
    override fun getHyperLfoLink(): Boolean = false
    override fun setDuoModSource(duoIndex: Int, source: ModSource) = Unit
    override fun setFmStructure(crossQuad: Boolean) = Unit
    override fun setTotalFeedback(amount: Float) = Unit
    override fun setVibrato(amount: Float) = Unit
    override fun setVoiceCoupling(amount: Float) = Unit
    override fun setBend(amount: Float) = Unit
    override fun getBend(): Float = 0f
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) = Unit
    override fun releaseStringBend(stringIndex: Int): Int = 0
    override fun setSlideBar(yPosition: Float, xPosition: Float) = Unit
    override fun releaseSlideBar() = Unit
    override fun resetStringBenders() = Unit
    override fun playTestTone(frequency: Float) = Unit
    override fun stopTestTone() = Unit
    override fun getPeak(): Float = 0f
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
    override val peakFlow = MutableStateFlow(0f)
    override val cpuLoadFlow = MutableStateFlow(0f)
    override val voiceLevelsFlow = MutableStateFlow(FloatArray(8))
    override val lfoOutputFlow = MutableStateFlow(0f)
    override val lfoAOutputFlow = MutableStateFlow(0f)
    override val lfoBOutputFlow = MutableStateFlow(0f)
    override val masterLevelFlow = MutableStateFlow(0f)
    override val bendFlow = MutableStateFlow(0f)
    override fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean = false
    override fun getPluginPort(pluginUri: String, symbol: String): PortValue? = null
    override fun getVoiceTune(index: Int): Float = 0f
    override fun getVoiceFmDepth(index: Int): Float = 0f
    override fun getVoiceEnvelopeSpeed(index: Int): Float = 0f
    override fun getDuoSharpness(duoIndex: Int): Float = 0f
    override fun getDuoModSource(duoIndex: Int): ModSource = ModSource.OFF
    override fun getFmStructureCrossQuad(): Boolean = false
    override fun getTotalFeedback(): Float = 0f
    override fun getVibrato(): Float = 0f
    override fun getVoiceCoupling(): Float = 0f
    override fun getDelayTime(index: Int): Float = 0f
    override fun getDelayFeedback(): Float = 0f
    override fun getDelayMix(): Float = 0f
    override fun getDelayModDepth(index: Int): Float = 0f
    override fun getDrive(): Float = 0f
    override fun getDistortionMix(): Float = 0f
    override fun getMasterVolume(): Float = 0f
    override fun setVoicePan(index: Int, pan: Float) = Unit
    override fun getVoicePan(index: Int): Float = 0f
    override fun setMasterPan(pan: Float) = Unit
    override fun getMasterPan(): Float = 0f
    override fun setStereoMode(mode: StereoMode) = Unit
    override fun getStereoMode(): StereoMode = StereoMode.VOICE_PAN
    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) = Unit
    override fun clearParameterAutomation(controlId: String) = Unit
    override fun getDrumFrequency(type: Int): Float = 0f
    override fun getDrumTone(type: Int): Float = 0f
    override fun getDrumDecay(type: Int): Float = 0f
    override fun getDrumP4(type: Int): Float = 0f
    override fun getDrumP5(type: Int): Float = 0f
    override fun loadTtsAudio(samples: FloatArray, sampleRate: Int) = Unit
    override fun playTts() = Unit
    override fun stopTts() = Unit
    override fun isTtsPlaying(): Boolean = false
    override fun setLooperRecord(recording: Boolean) = Unit
    override fun setLooperPlay(playing: Boolean) = Unit
    override fun setLooperOverdub(overdub: Boolean) = Unit
    override fun setLooperQuantize(enabled: Boolean) = Unit
    override fun setLooperLevel(level: Float) = Unit
    override fun clearLooper() = Unit
    override fun getLooperPosition(): Float = 0f
    override fun getLooperDuration(): Double = 0.0
}
