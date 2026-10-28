package org.balch.orpheus.ui.widgets.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.pushRecent
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The last distinct engine picks shared by every picker in an app, as [EngineCatalog.key] strings.
 * With [prefs] they load once and save on each pick; without, they live in memory only.
 */
@Stable
class EngineRecents(
    private val prefs: AppPreferencesRepository?,
    private val scope: CoroutineScope,
) {
    private val _keys = MutableStateFlow<List<String>>(emptyList())
    val keys: StateFlow<List<String>> = _keys.asStateFlow()

    init {
        if (prefs != null) {
            scope.launch {
                val saved = prefs.load().recentEngines
                // A pick made while loading stays in front of the saved list.
                _keys.update { picked -> (picked + saved.filter { it !in picked }).take(RECENT_CAP) }
            }
        }
    }

    fun record(choice: EngineChoice) {
        _keys.update { pushRecent(it, choice, RECENT_CAP) }
        val prefs = prefs ?: return
        scope.launch { prefs.update { it.copy(recentEngines = _keys.value) } }
    }

    private companion object {
        const val RECENT_CAP = 8
    }
}

private val InMemoryEngineRecents = EngineRecents(null, CoroutineScope(EmptyCoroutineContext))

/** Unprovided in previews and tests: one process-wide in-memory list. App roots provide a persisted one. */
val LocalEngineRecents: ProvidableCompositionLocal<EngineRecents> =
    staticCompositionLocalOf { InMemoryEngineRecents }

/** An [EngineRecents] saved through [prefs], for an app root to provide as [LocalEngineRecents]. */
@Composable
fun rememberEngineRecents(prefs: AppPreferencesRepository?): EngineRecents {
    val scope = rememberCoroutineScope()
    return remember(prefs, scope) { EngineRecents(prefs, scope) }
}
