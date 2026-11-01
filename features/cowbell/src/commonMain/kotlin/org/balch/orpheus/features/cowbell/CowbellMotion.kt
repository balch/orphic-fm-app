package org.balch.orpheus.features.cowbell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.balch.orpheus.features.cowbell.motion.MotionSource
import org.balch.orpheus.features.cowbell.motion.rememberMotionSource

/** Swaps the platform's [MotionSource] for a fake in tests. Null means use the platform's. */
internal val LocalCowbellMotion = staticCompositionLocalOf<MotionSource?> { null }

/**
 * The motion source the bell reads, running while the lifecycle is RESUMED. The platform's own
 * source manages that itself; an injected one is started and stopped here the same way.
 */
@Composable
internal fun rememberCowbellMotion(): MotionSource {
    val injected = LocalCowbellMotion.current ?: return rememberMotionSource()
    LifecycleResumeEffect(injected) {
        injected.start()
        onPauseOrDispose { injected.stop() }
    }
    return injected
}
