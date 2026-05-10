package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository

/**
 * Read/write surface for the master "auto-end songs" preference.
 * Backed by [AppPreferences.pulsarSongEndingEnabled].
 */
interface SongEndingPreferences {
    val enabledFlow: StateFlow<Boolean>
    suspend fun setEnabled(value: Boolean)
}

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class SongEndingPreferencesImpl(
    private val repo: AppPreferencesRepository,
    scope: AppCoroutineScope,
) : SongEndingPreferences {

    // Initial value matches the AppPreferences default so a cold-start read
    // before the async repo load below sees the correct state for new
    // installs (where no value has been persisted yet).
    private val _flow = MutableStateFlow(AppPreferences().pulsarSongEndingEnabled)
    override val enabledFlow: StateFlow<Boolean> = _flow.asStateFlow()

    init {
        scope.launch { _flow.value = repo.load().pulsarSongEndingEnabled }
    }

    override suspend fun setEnabled(value: Boolean) {
        _flow.value = value
        repo.update { it.copy(pulsarSongEndingEnabled = value) }
    }
}
