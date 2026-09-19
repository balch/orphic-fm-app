package org.balch.orpheus.djapp

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.map

/**
 * The tabletop [Hinge] for [activity]'s window, or null. Starts null so composition never waits on
 * the tracker. Call before any canvas scale is provided: it reads the native density.
 */
@Composable
fun rememberTabletopHinge(activity: Activity): Hinge? {
    val density = LocalDensity.current.density
    val hingeFlow = remember(activity, density) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            val windowHeightPx = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(activity).bounds.height()
            info.displayFeatures.filterIsInstance<FoldingFeature>().firstNotNullOfOrNull { fold ->
                tabletopHingeOf(
                    halfOpened = fold.state == FoldingFeature.State.HALF_OPENED,
                    horizontal = fold.orientation == FoldingFeature.Orientation.HORIZONTAL,
                    separating = fold.isSeparating,
                    topPx = fold.bounds.top,
                    bottomPx = fold.bounds.bottom,
                    windowHeightPx = windowHeightPx,
                    density = density,
                )
            }
        }
    }
    val hinge by hingeFlow.collectAsStateWithLifecycle(initialValue = null)
    return hinge
}
