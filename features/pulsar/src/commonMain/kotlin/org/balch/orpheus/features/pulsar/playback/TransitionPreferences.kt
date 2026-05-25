package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository

/**
 * Read/write surface for the global default [TransitionSpec] used for
 * song-to-song handoffs. Backed by [AppPreferences.pulsarTransitionDefault].
 * Per-vibe overrides live on `Arrangement.transitionOut`.
 */
interface TransitionPreferences {
    val defaultFlow: StateFlow<TransitionSpec>
    suspend fun setDefault(value: TransitionSpec)
}

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class TransitionPreferencesImpl(
    private val repo: AppPreferencesRepository,
    scope: AppCoroutineScope,
) : TransitionPreferences {

    // Initial value matches the AppPreferences default so a cold-start read
    // before the async repo load below sees the correct state for new
    // installs (where no value has been persisted yet).
    private val _flow = MutableStateFlow(AppPreferences().pulsarTransitionDefault)
    override val defaultFlow: StateFlow<TransitionSpec> = _flow.asStateFlow()

    init {
        scope.launch { _flow.value = repo.load().pulsarTransitionDefault }
    }

    override suspend fun setDefault(value: TransitionSpec) {
        _flow.value = value
        repo.update { it.copy(pulsarTransitionDefault = value) }
    }
}
