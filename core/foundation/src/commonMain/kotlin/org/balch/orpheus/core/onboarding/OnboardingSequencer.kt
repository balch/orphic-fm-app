package org.balch.orpheus.core.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.OnboardingProgress

/** One thing the app teaches: shown at most once a launch, [maxShows] launches in all, until learned. */
class OnboardingStep(
    val id: String,
    val maxShows: Int = Int.MAX_VALUE,
    /** How long the steps after this one wait for it to begin before it gives this launch up. */
    val holdMillis: Long = 5_000L,
    /** Once this shows, nothing after it in the queue shows this launch. */
    val ownsLaunch: Boolean = false,
    /** A saved answer from before this step existed. */
    val learnedAlready: (AppPreferences) -> Boolean = { false },
)

/**
 * Runs an app's onboarding one step at a time, so two hints never compete. Steps in [queue] take
 * their turn in order; a step outside it is a hint tied to a moment of its own, which [pending]
 * answers for. Hold one per process: what has run this launch lives here, the rest in preferences.
 */
open class OnboardingSequencer(
    val queue: List<OnboardingStep>,
    private val preferences: AppPreferencesRepository,
    private val scope: CoroutineScope,
) {
    private enum class Phase { Running, Over, Skipped }

    private val loadLock = Mutex()
    private var saved: AppPreferences? = null

    // A step with no phase has not had this launch's turn yet.
    private val phases = MutableStateFlow<Map<String, Phase>>(emptyMap())
    private val learnedNow = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Waits for the steps ahead of [step] to finish, then returns how many launches have shown it,
     * or null if it has nothing to show this launch. Claims nothing: [run] does, after whatever
     * [step]'s owner still needs to get ready.
     */
    suspend fun awaitTurn(step: OnboardingStep): Int? {
        val saved = load()
        val ahead = queue.indexOfFirst { it.id == step.id }
        queue.take(ahead.coerceAtLeast(0)).forEach { waitOut(it, saved) }
        return showsIfDue(step, saved)
    }

    /**
     * Shows [step] if it is due and nobody else got there first: counts the show, runs [block] with
     * how many launches showed it before this one, and lets the next step go when [block] ends or is
     * cancelled. False if it did not run.
     */
    suspend fun run(step: OnboardingStep, block: suspend (shows: Int) -> Unit): Boolean {
        val shows = showsIfDue(step, load()) ?: return false
        val before = phases.getAndUpdate { if (step.id in it) it else it + (step.id to Phase.Running) }
        if (step.id in before) return false
        try {
            record(step) { it.copy(shows = shows + 1) }
            block(shows)
        } finally {
            phases.update { it + (step.id to Phase.Over) }
        }
        return true
    }

    /** Gives [step] up for this launch without counting a show; the next step goes at once. */
    fun skip(step: OnboardingStep) {
        phases.update { if (step.id in it) it else it + (step.id to Phase.Skipped) }
    }

    /** The user knows this one now: never again, and any later steps stop waiting for it. */
    fun learn(step: OnboardingStep) {
        if (step.id in learnedNow.value) return
        learnedNow.update { it + step.id }
        skip(step)
        record(step) { it.copy(learned = true) }
    }

    /** Whether [step] still has something to teach, for a hint that shows at a moment of its own. */
    fun pending(step: OnboardingStep): Flow<Boolean> = flow {
        val saved = load()
        emitAll(learnedNow.map { hasMoreToShow(step, saved, it) })
    }.distinctUntilChanged()

    private suspend fun waitOut(ahead: OnboardingStep, saved: AppPreferences) {
        if (showsIfDue(ahead, saved) == null && phases.value[ahead.id] != Phase.Running) return
        withTimeoutOrNull(ahead.holdMillis) { phases.first { ahead.id in it } } ?: skip(ahead)
        if (ahead.ownsLaunch) return
        phases.first { it[ahead.id] != Phase.Running }
    }

    private fun launchTaken(step: OnboardingStep): Boolean {
        val ahead = queue.indexOfFirst { it.id == step.id }
        return queue.take(ahead.coerceAtLeast(0)).any {
            it.ownsLaunch && phases.value[it.id].let { phase -> phase != null && phase != Phase.Skipped }
        }
    }

    private fun showsIfDue(step: OnboardingStep, saved: AppPreferences): Int? {
        if (step.id in phases.value || launchTaken(step) || !hasMoreToShow(step, saved, learnedNow.value)) return null
        return saved.onboarding[step.id]?.shows ?: 0
    }

    private fun hasMoreToShow(step: OnboardingStep, saved: AppPreferences, learned: Set<String>): Boolean {
        val progress = saved.onboarding[step.id]
        return step.id !in learned && progress?.learned != true && !step.learnedAlready(saved) &&
            (progress?.shows ?: 0) < step.maxShows
    }

    private suspend fun load(): AppPreferences =
        loadLock.withLock { saved ?: preferences.load().also { saved = it } }

    // On the app's scope: a caller's composition can leave before the write lands.
    private fun record(step: OnboardingStep, change: (OnboardingProgress) -> OnboardingProgress) {
        scope.launch {
            preferences.update { prefs ->
                val progress = prefs.onboarding[step.id] ?: OnboardingProgress()
                prefs.copy(onboarding = prefs.onboarding + (step.id to change(progress)))
            }
        }
    }
}
