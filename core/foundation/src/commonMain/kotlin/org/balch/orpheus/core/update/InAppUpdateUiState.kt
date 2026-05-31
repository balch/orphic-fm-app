package org.balch.orpheus.core.update

/**
 * Banner-facing state for the Flexible in-app update flow. Android maps Play's update flow to
 * this; the shared banner composable renders it. Immediate updates are handled entirely by
 * Play's full-screen UI and never produce a visible state here.
 */
sealed interface InAppUpdateUiState {
    /** No update banner shown (no update, or Immediate in progress, or download not yet started). */
    data object Hidden : InAppUpdateUiState

    /** A Flexible update is downloading in the background. [progress] is 0f..1f, or null if unknown. */
    data class Downloading(val progress: Float?) : InAppUpdateUiState

    /** A Flexible update finished downloading and is awaiting a restart to install. */
    data object Ready : InAppUpdateUiState
}
