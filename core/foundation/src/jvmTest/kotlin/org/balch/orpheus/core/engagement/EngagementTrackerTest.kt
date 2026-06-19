package org.balch.orpheus.core.engagement

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementTrackerTest {

    @Test
    fun emitsRecordedActions() = runTest(UnconfinedTestDispatcher()) {
        val tracker = DefaultEngagementTracker(TestTimeSource())
        val seen = mutableListOf<EngagementAction>()
        val job = launch { tracker.events.collect { seen += it } }

        tracker.record(EngagementAction.PAUSE)
        tracker.record(EngagementAction.VIBE_SELECT)

        assertEquals(listOf(EngagementAction.PAUSE, EngagementAction.VIBE_SELECT), seen)
        job.cancel()
    }

    @Test
    fun throttlesSameTypeWithinWindowButAllowsAfter() = runTest(UnconfinedTestDispatcher()) {
        val clock = TestTimeSource()
        val tracker = DefaultEngagementTracker(clock)
        val seen = mutableListOf<EngagementAction>()
        val job = launch { tracker.events.collect { seen += it } }

        tracker.record(EngagementAction.GAIN_ADJUST) // emit
        tracker.record(EngagementAction.GAIN_ADJUST) // within window -> dropped
        clock += 2.seconds
        tracker.record(EngagementAction.GAIN_ADJUST) // window elapsed -> emit

        assertEquals(2, seen.size)
        job.cancel()
    }

    @Test
    fun throttleWindowBoundaryIsInclusiveOfTheWindowEdge() = runTest(UnconfinedTestDispatcher()) {
        // Leading-edge throttle: a same-type record exactly THROTTLE (1500ms) after the
        // last EMIT is allowed (the guard is `elapsedNow() < THROTTLE`), while one just
        // under is dropped. Pins the `<` (vs `<=`) contract.
        val clock = TestTimeSource()
        val tracker = DefaultEngagementTracker(clock)
        val seen = mutableListOf<EngagementAction>()
        val job = launch { tracker.events.collect { seen += it } }

        tracker.record(EngagementAction.GAIN_ADJUST) // emit (1)
        clock += 1_499.milliseconds
        tracker.record(EngagementAction.GAIN_ADJUST) // just under window -> dropped
        clock += 1.milliseconds                      // now exactly 1500ms since the last emit
        tracker.record(EngagementAction.GAIN_ADJUST) // at the window edge -> emit (2)

        assertEquals(2, seen.size)
        job.cancel()
    }

    @Test
    fun differentTypesAreNotThrottledAgainstEachOther() = runTest(UnconfinedTestDispatcher()) {
        val tracker = DefaultEngagementTracker(TestTimeSource())
        val seen = mutableListOf<EngagementAction>()
        val job = launch { tracker.events.collect { seen += it } }

        tracker.record(EngagementAction.PAUSE)
        tracker.record(EngagementAction.INSTRUMENTS_OPEN) // different type, same instant -> emit

        assertEquals(2, seen.size)
        job.cancel()
    }
}
