package org.balch.orpheus.features.pulsar.playback

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.time.Instant

/** One saved AI vibe: the newest save under its [name]. */
@Immutable
data class SavedVibe(val name: String, val savedAtMillis: Long, val vibe: Vibe)

/**
 * The AI vibes saved on this device, newest first and one per name. Pickable from the vibe lists but
 * never part of the rotation. The DJ app's AI edition replaces [NoSavedVibes]; everywhere else it is empty.
 */
interface SavedVibeLibrary {
    val saved: StateFlow<List<SavedVibe>>

    /** Sets aside every save of [name] (any case); the list drops it once that is done. */
    suspend fun delete(name: String)

    /** A fixed list in memory, for tests and previews. */
    class InMemory(initial: List<SavedVibe> = emptyList()) : SavedVibeLibrary {
        private val flow = MutableStateFlow(initial)
        override val saved: StateFlow<List<SavedVibe>> = flow.asStateFlow()
        override suspend fun delete(name: String) = flow.update { list -> list.filterNot { it.name.equals(name, ignoreCase = true) } }
    }
}

/** No saved vibes: iOS, the core edition and the Orpheus app. */
@ContributesBinding(AppScope::class)
@Inject
class NoSavedVibes : SavedVibeLibrary {
    override val saved: StateFlow<List<SavedVibe>> = EmptySavedVibes
    override suspend fun delete(name: String) {}
}

/** Shared never-changing empty list, for [NoSavedVibes] and the feature's stub default. */
internal val EmptySavedVibes: StateFlow<List<SavedVibe>> = MutableStateFlow<List<SavedVibe>>(emptyList()).asStateFlow()

/** "today", "yesterday", else "Oct 21" ("Oct 21 2025" in another year), in [zone]. */
fun savedVibeDate(savedAtMillis: Long, nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val day = Instant.fromEpochMilliseconds(savedAtMillis).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    return when (day) {
        today -> "today"
        today.minus(1, DateTimeUnit.DAY) -> "yesterday"
        else -> {
            val month = day.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            if (day.year == today.year) "$month ${day.day}" else "$month ${day.day} ${day.year}"
        }
    }
}
