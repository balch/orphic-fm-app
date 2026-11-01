package org.balch.orpheus.features.cowbell

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType

actual fun PointerEvent.contactSize(change: PointerInputChange): Float? {
    if (change.type != PointerType.Touch) return null
    val event = motionEvent ?: return null
    val index = event.findPointerIndex(change.id.value.toInt())
    if (index < 0) return null
    return event.getTouchMajor(index).takeIf { it > 0f }
}
