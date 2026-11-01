package org.balch.orpheus.features.cowbell.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.sqrt

/**
 * Gravity from `TYPE_GRAVITY` (else the accelerometer, low-passed) and linear acceleration from
 * `TYPE_LINEAR_ACCELERATION` (else the accelerometer minus gravity), at `SENSOR_DELAY_GAME` on
 * its own thread. A tap's spike is a few ms wide, so the [jolts] come from the raw accelerometer
 * (minus the gravity estimate) at `SENSOR_DELAY_FASTEST` on the same thread, not from that
 * slower, software-fused stream. Samples are stamped with [SystemClock.uptimeMillis] on receipt,
 * the clock Compose uses for `PointerInputChange.uptimeMillis`.
 */
internal class AndroidMotionSource(private val context: Context) : MotionSource {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linearSensor = sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override val available: Boolean = gravitySensor != null || accelSensor != null
    override val jolts: JoltWindow = JoltWindow()

    private val accumulator = MotionAccumulator()
    private var thread: HandlerThread? = null
    private var display: Display? = null

    // Written and read on the sensor thread only.
    private var gx = 0f
    private var gy = 0f
    private var gz = SensorManager.GRAVITY_EARTH
    private var gravitySeeded = false
    private var lastAccelNanos = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> setGravity(v[0], v[1], v[2])
                Sensor.TYPE_LINEAR_ACCELERATION -> kick(v[0], v[1], v[2])
                Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event.timestamp, v[0], v[1], v[2])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // The tap's jolt: the accelerometer at full rate, less the gravity estimate. Sensor thread only.
    private val joltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            val x = v[0] - gx
            val y = v[1] - gy
            val z = v[2] - gz
            jolts.record(SystemClock.uptimeMillis(), sqrt(x * x + y * y + z * z))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun start() {
        val manager = sensors ?: return
        if (!available || thread != null) return
        accumulator.reset()
        jolts.clear()
        gravitySeeded = false
        lastAccelNanos = 0L
        val t = HandlerThread("cowbell-motion").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        gravitySensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        linearSensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        // The accelerometer stands in for whichever of gravity and linear acceleration is missing.
        if (gravitySensor == null || linearSensor == null) {
            accelSensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        }
        accelSensor?.let { registerFast(manager, it, handler) }
    }

    // Above 200 Hz needs HIGH_SAMPLING_RATE_SENSORS from Android 12; without it, take 200 Hz.
    private fun registerFast(manager: SensorManager, sensor: Sensor, handler: Handler) {
        try {
            manager.registerListener(joltListener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } catch (_: SecurityException) {
            manager.registerListener(joltListener, sensor, FALLBACK_PERIOD_US, handler)
        }
    }

    override fun stop() {
        sensors?.unregisterListener(listener)
        sensors?.unregisterListener(joltListener)
        thread?.quitSafely()
        thread = null
    }

    override fun read(into: MotionSample) {
        if (!available) {
            into.rest()
            return
        }
        accumulator.drain(into, displayRotation())
    }

    private fun setGravity(x: Float, y: Float, z: Float) {
        gx = x
        gy = y
        gz = z
        accumulator.publishGravity(x / G, y / G, z / G)
    }

    private fun onAccelerometer(timestampNanos: Long, x: Float, y: Float, z: Float) {
        if (gravitySensor == null) {
            if (!gravitySeeded) {
                gravitySeeded = true
                setGravity(x, y, z)
            } else {
                val dt = (timestampNanos - lastAccelNanos) * 1e-9f
                val a = dt / (GRAVITY_TAU_SECONDS + dt)
                setGravity(gx + a * (x - gx), gy + a * (y - gy), gz + a * (z - gz))
            }
            lastAccelNanos = timestampNanos
        }
        if (linearSensor == null) kick(x - gx, y - gy, z - gz)
    }

    private fun kick(x: Float, y: Float, z: Float) {
        accumulator.addKick(x, y)
        // The fast accelerometer stream records the jolts; this one only stands in without it.
        if (accelSensor == null) jolts.record(SystemClock.uptimeMillis(), sqrt(x * x + y * y + z * z))
    }

    // UI thread. `Context.getDisplay` throws for a context without a display (an application
    // context), and `defaultDisplay` is deprecated from API 30 but still works.
    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val d = display ?: run {
            val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    context.display
                } catch (_: UnsupportedOperationException) {
                    null
                }
            } else {
                null
            } ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            display = found
            found
        }
        return d?.rotation ?: 0
    }

    private companion object {
        const val G = SensorManager.GRAVITY_EARTH
        const val GRAVITY_TAU_SECONDS = 0.1f
        const val FALLBACK_PERIOD_US = 5_000
    }
}

@Composable
actual fun rememberMotionSource(): MotionSource {
    val context = LocalContext.current
    val source = remember(context) { AndroidMotionSource(context) }
    LifecycleResumeEffect(source) {
        source.start()
        onPauseOrDispose { source.stop() }
    }
    return source
}
