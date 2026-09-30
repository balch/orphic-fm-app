package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import kotlin.concurrent.Volatile

/** The saved vibe rotation: read at once, written through to the app's preferences in the background. */
interface VibePlaylistStore {
    val prefsFlow: StateFlow<VibePlaylistPrefs?>

    /** Applies [transform] to the current prefs now; the write to disk follows. */
    fun update(transform: (VibePlaylistPrefs?) -> VibePlaylistPrefs?)

    /** Memory only: the default for direct test construction and previews. */
    class InMemory(initial: VibePlaylistPrefs? = null) : VibePlaylistStore {
        private val flow = MutableStateFlow(initial)
        override val prefsFlow: StateFlow<VibePlaylistPrefs?> = flow.asStateFlow()
        override fun update(transform: (VibePlaylistPrefs?) -> VibePlaylistPrefs?) = flow.update(transform)
    }
}

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class AppVibePlaylistStore(
    private val repo: AppPreferencesRepository,
    scope: AppCoroutineScope,
) : VibePlaylistStore {
    private val flow = MutableStateFlow<VibePlaylistPrefs?>(null)
    override val prefsFlow: StateFlow<VibePlaylistPrefs?> = flow.asStateFlow()

    // An edit made before the saved playlist loads wins over it.
    @Volatile private var edited = false

    // Conflated and drained by one coroutine: a burst of edits writes only the latest, in order.
    private class Pending(val prefs: VibePlaylistPrefs?)
    private val writes = Channel<Pending>(Channel.CONFLATED)

    init {
        scope.launch {
            val saved = repo.load().vibePlaylist
            if (!edited) flow.compareAndSet(null, saved)
        }
        scope.launch {
            for (pending in writes) repo.update { it.copy(vibePlaylist = pending.prefs) }
        }
    }

    override fun update(transform: (VibePlaylistPrefs?) -> VibePlaylistPrefs?) {
        edited = true
        writes.trySend(Pending(flow.updateAndGet(transform)))
    }
}
