package org.balch.orpheus.djapp

import androidx.compose.ui.window.PopupProperties

/**
 * For a popup placed from window bounds. Skiko's popups position inside the safe area by default,
 * which on iOS drops them by the top inset, onto what they float above.
 */
internal expect fun windowPopupProperties(focusable: Boolean): PopupProperties
