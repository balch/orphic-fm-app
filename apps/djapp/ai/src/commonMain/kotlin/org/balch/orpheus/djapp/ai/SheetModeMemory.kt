package org.balch.orpheus.djapp.ai

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.preferences.AppPreferencesRepository

/** The saved sheet mode, falling back to [AiSheetMode.CHAT] for a missing or unknown value. */
internal fun sheetModeFromPref(saved: String?): AiSheetMode =
    AiSheetMode.entries.firstOrNull { it.name == saved } ?: AiSheetMode.CHAT

/** The loaded mode, only while the UI still shows the default: a toggle that beat the load wins. */
internal fun withSavedSheetMode(s: DjAiUiState, saved: AiSheetMode): DjAiUiState =
    if (s.sheetMode == DjAiUiState().sheetMode) s.copy(sheetMode = saved) else s

/**
 * Keeps [DjAiUiState.sheetMode] across launches: the saved mode loads at start, and every later change is saved,
 * the Open Mixer card's included.
 */
internal class SheetModeMemory(
    state: MutableStateFlow<DjAiUiState>,
    preferences: AppPreferencesRepository,
    scope: CoroutineScope,
    context: CoroutineContext,
) {
    init {
        scope.launch(context) {
            val saved = sheetModeFromPref(preferences.load().aiSheetMode)
            state.update { withSavedSheetMode(it, saved) }
            var stored = saved
            state.map { it.sheetMode }.distinctUntilChanged().collect { mode ->
                if (mode == stored) return@collect
                stored = mode
                preferences.update { it.copy(aiSheetMode = mode.name) }
            }
        }
    }
}
