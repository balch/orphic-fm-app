package org.balch.orpheus.core

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition local providing the [SynthFeatureRegistry] to all composables.
 * Provided at the app root in the App composable.
 */
val LocalSynthFeatures = staticCompositionLocalOf<SynthFeatureRegistry> {
    error("No SynthFeatureRegistry provided")
}
