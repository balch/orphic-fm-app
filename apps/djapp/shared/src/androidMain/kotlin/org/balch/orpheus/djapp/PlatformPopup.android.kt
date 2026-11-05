package org.balch.orpheus.djapp

import androidx.compose.ui.window.PopupProperties

internal actual fun windowPopupProperties(focusable: Boolean): PopupProperties =
    PopupProperties(focusable = focusable)
