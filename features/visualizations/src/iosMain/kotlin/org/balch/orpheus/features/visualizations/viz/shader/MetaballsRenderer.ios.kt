@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.balch.orpheus.ui.viz.Blob

/**
 * iOS stub for MetaballsRenderer.
 *
 * A full implementation can use Metal shaders via Skiko or a custom
 * Metal pipeline in the future.
 */
actual class MetaballsRenderer {
    actual fun isSupported(): Boolean = false
    actual fun dispose() {}
}

/**
 * iOS stub for MetaballsCanvas — renders nothing for now.
 */
@Composable
actual fun MetaballsCanvas(
    modifier: Modifier,
    blobs: List<Blob>,
    config: MetaballsConfig,
    lfoModulation: Float,
    masterEnergy: Float,
    time: Float
) {
    Box(modifier = modifier)
}
