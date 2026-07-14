package org.balch.orpheus.djapp.ai

import androidx.compose.ui.platform.Clipboard

/**
 * Copy plain text to the system clipboard via the non-deprecated [Clipboard] API.
 *
 * Per-platform actuals exist because CMP 1.11.1 exposes no COMMON plain-text ClipEntry
 * factory — each platform builds its native entry (AWT Transferable / Android ClipData /
 * UIPasteboard). Fold back into common code when CMP ships a common
 * ClipEntry.withPlainText.
 */
internal expect suspend fun Clipboard.copyPlainText(text: String)
