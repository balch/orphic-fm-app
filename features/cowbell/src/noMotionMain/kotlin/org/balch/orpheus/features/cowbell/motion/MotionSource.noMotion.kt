package org.balch.orpheus.features.cowbell.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** JVM desktop and wasm: no sensors, so [read] leaves the sample at rest. */
internal class NoMotionSource : MotionSource {
    override val available: Boolean = false
    override val jolts: JoltWindow = JoltWindow(capacity = 1)

    override fun start() = Unit

    override fun stop() = Unit

    override fun read(into: MotionSample) = into.rest()
}

@Composable
actual fun rememberMotionSource(): MotionSource = remember { NoMotionSource() }
