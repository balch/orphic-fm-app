package org.balch.orpheus.features.cowbell.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIWindowScene
import kotlin.math.sqrt

/**
 * `CMMotionManager.deviceMotion` at 100 Hz on its own queue. CoreMotion reports gravity and
 * `userAcceleration` in g with the opposite sign to Android's sensors, so both are negated and
 * the acceleration scaled to m/s² to match. Stamps use `CACurrentMediaTime`, the clock behind
 * the `UITouch.timestamp` that Compose turns into `PointerInputChange.uptimeMillis`
 * (`(timestamp * 1e3).toLong()` in `ComposeSceneMediator`).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosMotionSource : MotionSource {
    private val manager = CMMotionManager()
    private val accumulator = MotionAccumulator()
    private var queue: NSOperationQueue? = null

    override val available: Boolean get() = manager.deviceMotionAvailable
    override val jolts: JoltWindow = JoltWindow()

    override fun start() {
        if (!available || queue != null) return
        accumulator.reset()
        jolts.clear()
        val q = NSOperationQueue().also { it.name = "cowbell-motion" }
        queue = q
        manager.deviceMotionUpdateInterval = 1.0 / 100.0
        manager.startDeviceMotionUpdatesToQueue(q) { motion, _ ->
            if (motion == null) return@startDeviceMotionUpdatesToQueue
            val stamp = (CACurrentMediaTime() * 1e3).toLong()
            motion.gravity.useContents { accumulator.publishGravity(-x.toFloat(), -y.toFloat(), -z.toFloat()) }
            motion.userAcceleration.useContents {
                val kx = (-x * G).toFloat()
                val ky = (-y * G).toFloat()
                val kz = (-z * G).toFloat()
                accumulator.addKick(kx, ky)
                jolts.record(stamp, sqrt(kx * kx + ky * ky + kz * kz))
            }
        }
    }

    override fun stop() {
        if (queue == null) return
        manager.stopDeviceMotionUpdates()
        queue = null
    }

    override fun read(into: MotionSample) {
        if (!available) {
            into.rest()
            return
        }
        accumulator.drain(into, interfaceRotation())
    }

    // Main thread. Maps UIInterfaceOrientation onto Android's Display.rotation numbering.
    private fun interfaceRotation(): Int {
        val scene = UIApplication.sharedApplication.connectedScenes
            .firstOrNull { it is UIWindowScene } as? UIWindowScene
        return when (scene?.interfaceOrientation) {
            UIInterfaceOrientationLandscapeRight -> 1
            UIInterfaceOrientationPortraitUpsideDown -> 2
            UIInterfaceOrientationLandscapeLeft -> 3
            else -> 0
        }
    }

    private companion object {
        const val G = 9.81
    }
}

@Composable
actual fun rememberMotionSource(): MotionSource {
    val source = remember { IosMotionSource() }
    LifecycleResumeEffect(source) {
        source.start()
        onPauseOrDispose { source.stop() }
    }
    return source
}
