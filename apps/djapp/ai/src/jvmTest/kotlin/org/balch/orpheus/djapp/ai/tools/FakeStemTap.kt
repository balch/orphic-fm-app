package org.balch.orpheus.djapp.ai.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.audio.dsp.StemLayout
import org.balch.orpheus.core.audio.dsp.StemTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.StemRecorder

/**
 * A trimmed copy of the pulsar module's FakeStemTap (its jvmTest isn't visible here): an in-memory stem ring
 * recording while enabled, armed and [gate] is on. Float j of frame k holds `j * 1000 + k`, k counted from enable.
 */
internal class FakeStemTap(var gate: () -> Boolean = { true }) : StemTap {
    private val ring = ArrayDeque<Float>()
    private var enabled = false
    private var remaining = 0
    private var nextFrame = 0
    /** True plays a closed engine: the native pass-throughs read 0 and nothing renders. */
    @Volatile var engineClosed = false

    @Synchronized
    override fun setEnabled(on: Boolean): Boolean {
        enabled = on
        ring.clear()
        remaining = 0
        nextFrame = 0
        return true
    }

    @Synchronized
    override fun arm(frames: Int): Boolean {
        if (!enabled || remaining > 0) return false
        remaining = frames
        return true
    }

    @Synchronized
    override fun drain(out: FloatArray): Int {
        if (engineClosed) return 0
        val n = minOf(out.size, ring.size) / StemLayout.FLOATS_PER_FRAME
        for (i in 0 until n * StemLayout.FLOATS_PER_FRAME) out[i] = ring.removeFirst()
        return n
    }

    @Synchronized
    override fun remaining(): Int = if (engineClosed) 0 else remaining

    @Synchronized
    override fun dropped(): Long = 0

    @Synchronized
    fun render(frames: Int) {
        if (engineClosed || !enabled || !gate() || remaining == 0) return
        val take = minOf(frames, remaining)
        repeat(take) {
            repeat(StemLayout.FLOATS_PER_FRAME) { j -> ring.addLast(j * 1000f + nextFrame) }
            nextFrame++
        }
        remaining -= take
    }

    fun renderIn(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            render(480)
            delay(10)
        }
    }
}

/** A real recorder over [tap] at 48 kHz, its controller metering for [pulsar] in the test's background scope. */
internal suspend fun TestScope.stemRecorder(pulsar: FakePulsar, tap: FakeStemTap = FakeStemTap()): StemRecorder {
    val controller = MixBenchController(pulsar, SynthController(), NoMeterTap, backgroundScope).also {
        it.start()
        runCurrent()
    }
    tap.gate = { controller.isMetering }
    tap.renderIn(backgroundScope)
    return StemRecorder(controller, { pulsar }, tap, 48_000, OneDispatcher(StandardTestDispatcher(testScheduler)))
}

/** A [FakePulsar] playing its first vibe, in section 1 ("verse"). */
internal fun playingPulsar(vararg names: String) = FakePulsar(names.map { vibe(it) }).apply {
    stateFlow.value = stateFlow.value.copy(playing = true)
    section(1)
}
