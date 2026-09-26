package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.core.media.PlaybackProgress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartRuleTest {
    @Test fun unknownProgressGoesBack() = assertFalse(shouldRestartOnPrevious(null))
    @Test fun justUnderTheThresholdGoesBack() = assertFalse(shouldRestartOnPrevious(PlaybackProgress(RestartAfterMs - 1, 200_000)))
    @Test fun atTheThresholdRestarts() = assertTrue(shouldRestartOnPrevious(PlaybackProgress(RestartAfterMs, 200_000)))
}
