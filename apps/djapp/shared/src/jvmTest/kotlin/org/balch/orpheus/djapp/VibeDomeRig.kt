package org.balch.orpheus.djapp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarSession
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.playback.PulsarTransitionRunner
import org.balch.orpheus.features.pulsar.playback.TransitionPreferences
import org.balch.orpheus.features.pulsar.playback.VibeMove
import org.balch.orpheus.features.pulsar.playback.VibeNavigator
import org.balch.orpheus.features.pulsar.playback.VibeRequest

// The playing one short enough for the rail's slot: a longer name would scroll, and a still dome ask for frames.
internal val RigVibeNames = listOf("Dog House", "Drift", "Stay Asleep")

/** Three vibes the real navigator steps through, starting on the middle one; the nav state follows. */
internal class RigPulsarFeature(
    base: PulsarFeature = PulsarViewModel.previewFeature(),
) : PulsarFeature by base {
    private val vibes = RigVibeNames.map { base.vibeFlow.value.copy(name = it) }
    override val vibeNames: List<String> = RigVibeNames
    override val vibeFlow = MutableStateFlow(vibes[1])
    private val nav = MutableStateFlow(navOf(vibes[1].name))
    override val vibeNavFlow: StateFlow<VibeNavState> = nav

    private fun navOf(name: String): VibeNavState {
        val i = RigVibeNames.indexOf(name).coerceAtLeast(0)
        val n = RigVibeNames.size
        return VibeNavState(name, RigVibeNames[(i + n - 1) % n], RigVibeNames[(i + 1) % n])
    }

    override fun applyVibe(vibe: Vibe) {
        vibeFlow.value = vibe
        nav.value = navOf(vibe.name)
    }

    override fun applyVibeByName(name: String): Boolean {
        applyVibe(vibes.firstOrNull { it.name == name } ?: return false)
        return true
    }
}

/** The app's preferences store in memory, counting its loads. */
internal class MemoryPreferences(domeSwiped: Boolean = false) : BaseAppPreferencesRepository() {
    var prefs = AppPreferences(domeSwiped = domeSwiped)
    var loads = 0
        private set

    override suspend fun load(): AppPreferences {
        loads++
        return prefs
    }

    override suspend fun save(preferences: AppPreferences) {
        prefs = preferences
    }
}

/**
 * The real [VibeNavigator] and [PulsarSession] over [RigPulsarFeature], every coroutine on
 * [dispatcher]; a transition swaps the vibe [swapAfterMs] in. [cues] are the ones DjApp provides,
 * over this session and [preferences]: by default a user who has swiped before, so no wiggle.
 */
internal class VibeDomeRig(
    dispatcher: CoroutineDispatcher,
    private val swapAfterMs: Long = 0L,
    val preferences: MemoryPreferences = MemoryPreferences(domeSwiped = true),
) {
    private val dispatchers = object : DispatcherProvider {
        override val main get() = dispatcher
        override val io get() = dispatcher
        override val default get() = dispatcher
        override val unconfined get() = dispatcher
    }
    private val scope = AppCoroutineScope(dispatchers)
    val feature = RigPulsarFeature()
    val session = PulsarSession(RenderProbeSynthEngine(), scope, dispatchers)
    private val runner = object : PulsarTransitionRunner {
        override val activeStyle: StateFlow<TransitionStyle?> = MutableStateFlow(null)
        override suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit) {
            if (swapAfterMs > 0) delay(swapAfterMs)
            applyNext()
        }
    }
    val navigator = VibeNavigator({ feature }, session, runner, CutPreferences, scope)
    val cues = VibeDomeCues(
        moves = session.vibeMoves,
        requestSwipe = { next -> session.requestVibe(VibeRequest.DomeSwipe(next)) },
        preferences = preferences,
        scope = scope,
        wiggle = DomeWiggleLaunch(),
    )

    /** Every move the navigator announced, in order. */
    val moves = mutableListOf<VibeMove>()

    init {
        scope.launch { session.vibeMoves.collect { moves += it } }
    }

    fun close() = scope.cancel()

    private object CutPreferences : TransitionPreferences {
        override val defaultFlow: StateFlow<TransitionSpec> = MutableStateFlow(TransitionSpec(TransitionStyle.CUT))
        override suspend fun setDefault(value: TransitionSpec) {}
    }
}
