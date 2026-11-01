package org.balch.orpheus.djapp.vibepresets

import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature

/** Presets exist only in the OG edition; the AI edition has its own mix tools. */
fun interface VibePresetsAvailability {
    fun isAvailable(): Boolean
}

/** Wall-clock time for a capture. A fun interface because Metro reads `() -> Long` as a provider. */
fun interface PresetClock {
    fun nowMs(): Long
}

data class PresetRow(val id: Int, val name: String, val capturedAtMs: Long)

/**
 * The capture sheet's contents. [count] is the slot this capture takes ("4 of 5"); when [full],
 * the sheet asks which preset to replace, starting at [replaceDefaultId], the oldest.
 */
data class CaptureDraft(
    val suggestedName: String,
    val chips: List<String>,
    val count: Int,
    val full: Boolean,
    val replaceDefaultId: Int?,
)

data class VibePresetsUiState(
    val available: Boolean = false,
    val barShown: Boolean = false,
    val vibeName: String = "",
    val presets: List<PresetRow> = emptyList(),
    val selectedId: Int? = null,
    val changed: Boolean = false,
    val draft: CaptureDraft? = null,
)

data class VibePresetsActions(
    val setBarShown: (Boolean) -> Unit,
    val openCapture: () -> Unit,
    val cancelCapture: () -> Unit,
    val saveCapture: (name: String, replaceId: Int?) -> Unit,
    val select: (Int) -> Unit,
    val loadOriginal: () -> Unit,
    val rename: (Int, String) -> Unit,
    val delete: (Int) -> Unit,
) {
    companion object {
        val EMPTY = VibePresetsActions(
            setBarShown = {},
            openCapture = {},
            cancelCapture = {},
            saveCapture = { _, _ -> },
            select = {},
            loadOriginal = {},
            rename = { _, _ -> },
            delete = {},
        )
    }
}

interface VibePresetsFeature : SynthFeature<VibePresetsUiState, VibePresetsActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId("VIBE_PRESETS")
            override val title = "Vibe Presets"
            override val markdown = "Saved mixes per vibe. The selected one loads each time its vibe plays."
            override val portControlKeys = emptyMap<String, String>()
        }
    }
}
