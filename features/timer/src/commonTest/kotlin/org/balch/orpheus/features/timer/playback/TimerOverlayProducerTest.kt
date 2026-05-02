package org.balch.orpheus.features.timer.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.timer.TimerActions
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.features.timer.TimerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private class FakeTimer(initial: TimerUiState) : TimerFeature {
    override val stateFlow = MutableStateFlow(initial)
    override val actions = TimerActions.EMPTY
}

private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

class TimerOverlayProducerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun testScope() = AppCoroutineScope(TestDispatchers(UnconfinedTestDispatcher()))

    @Test fun `overlay is null when idle`() = runTest {
        val timer = FakeTimer(TimerUiState(status = TimerStatus.IDLE))
        val producer = TimerOverlayProducer(timer, testScope())
        assertNull(producer.overlayFlow.value)
    }

    @Test fun `overlay shows countdown when running`() = runTest {
        val running = TimerUiState(
            status = TimerStatus.RUNNING,
            remainingTime = 12.minutes,
        )
        val timer = FakeTimer(running)
        val producer = TimerOverlayProducer(timer, testScope())
        // 12 min + 1s rounded down = 12 min.
        assertEquals("Sleep Timer: 12 min remaining", producer.overlayFlow.value)
    }

    @Test fun `overlay rounds up by one second to match flip-clock display`() = runTest {
        // 14 min 59 sec → with +1s correction shows 15 min, matching what the
        // user sees on the on-screen flip clock at the same instant.
        val timer = FakeTimer(
            TimerUiState(
                status = TimerStatus.RUNNING,
                remainingTime = 14.minutes + 59.seconds,
            )
        )
        val producer = TimerOverlayProducer(timer, testScope())
        assertEquals("Sleep Timer: 15 min remaining", producer.overlayFlow.value)
    }

    @Test fun `overlay returns to null on stop`() = runTest {
        val timer = FakeTimer(TimerUiState(status = TimerStatus.RUNNING, remainingTime = 5.minutes))
        val producer = TimerOverlayProducer(timer, testScope())
        assertEquals(
            "Sleep Timer: 5 min remaining",
            producer.overlayFlow.value,
        )
        timer.stateFlow.value = TimerUiState(status = TimerStatus.IDLE)
        assertNull(producer.overlayFlow.value)
    }
}
