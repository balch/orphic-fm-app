package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeDecisionTest {
    @Test fun aRightDragPastTheThresholdIsNext() = assertEquals(SwipeDecision.Next, swipeDecision(32f, 0f))
    @Test fun aLeftDragPastTheThresholdIsPrevious() = assertEquals(SwipeDecision.Previous, swipeDecision(-32f, 0f))
    @Test fun justShortSpringsBack() = assertEquals(SwipeDecision.None, swipeDecision(31f, 0f))
    @Test fun justShortTheOtherWaySpringsBack() = assertEquals(SwipeDecision.None, swipeDecision(-31f, 0f))
    @Test fun aShortFastFlingRightIsNext() = assertEquals(SwipeDecision.Next, swipeDecision(12f, 900f))
    @Test fun aShortFastFlingLeftIsPrevious() = assertEquals(SwipeDecision.Previous, swipeDecision(-12f, -900f))
    @Test fun aTinyFlingDoesNot() = assertEquals(SwipeDecision.None, swipeDecision(11f, 900f))
    @Test fun aFlingAgainstTheDragDoesNot() = assertEquals(SwipeDecision.None, swipeDecision(20f, -900f))
    @Test fun aSlowShortDragDoesNot() = assertEquals(SwipeDecision.None, swipeDecision(20f, 100f))
}
