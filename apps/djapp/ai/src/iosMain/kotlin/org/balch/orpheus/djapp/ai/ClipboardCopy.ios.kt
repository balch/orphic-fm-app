package org.balch.orpheus.djapp.ai

import androidx.compose.ui.platform.Clipboard
import platform.UIKit.UIPasteboard

internal actual suspend fun Clipboard.copyPlainText(text: String, sensitive: Boolean) {
    UIPasteboard.generalPasteboard.string = text
}
