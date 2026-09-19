package org.balch.orpheus.djapp

import androidx.compose.runtime.mutableStateOf

/**
 * The fold as the Swift host reports it. Kotlin/Native's UIKit bindings predate the iOS 27.1
 * reserved-region API, so Swift reads it and pushes primitives here. Main thread only.
 */
object IosFold {
    private val state = mutableStateOf<Hinge?>(null)

    /** Snapshot state: reading it in composition recomposes on every change. */
    val hinge: Hinge? get() = state.value

    /** One fold division in the root view's space, in points; [scale] is the screen scale. */
    fun report(
        active: Boolean,
        topPt: Double,
        bottomPt: Double,
        widthPt: Double,
        viewHeightPt: Double,
        scale: Double,
    ) {
        state.value = tabletopHingeOfPoints(active, topPt, bottomPt, widthPt, viewHeightPt, scale)
    }

    /** No fold division in the view: a slab iPhone, an iPad, or the closed outer display. */
    fun clear() {
        state.value = null
    }
}
