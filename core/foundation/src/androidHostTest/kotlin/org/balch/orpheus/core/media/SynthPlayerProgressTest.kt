package org.balch.orpheus.core.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SynthPlayerProgressTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun progressPublishesDurationAndPosition() {
        val player = SynthPlayer(app)
        player.updateProgress(PlaybackProgress(positionMs = 2_000, durationMs = 8_000))
        idle()
        assertEquals(8_000, player.duration)
        assertEquals(2_000, player.currentPosition)
    }

    @Test
    fun noProgressMeansNoDuration() {
        val player = SynthPlayer(app)
        player.updateProgress(null)
        idle()
        assertEquals(C.TIME_UNSET, player.duration)
    }

    @Test
    fun progressDoesNotReplaceTheMediaItem() {
        // Position-only updates leave the media item alone. A new duration still re-sends the
        // artwork (it is a timeline change), which is why the producer holds it steady.
        val player = SynthPlayer(app)
        player.updateMetadata("Bell Tolls", "RIF", byteArrayOf(1, 2, 3))
        idle()
        val before = player.currentMediaItem
        player.updateProgress(PlaybackProgress(positionMs = 1_000, durationMs = 8_000))
        idle()
        assertSame(before, player.currentMediaItem)
    }

    @Test
    fun theSeekBarIsDisplayOnly() {
        val player = SynthPlayer(app)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_BACK))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
    }

    @Test
    fun positionMovesWhilePlayingAndFreezesWhenPaused() {
        val player = SynthPlayer(app)
        player.updatePlayState(true)
        player.updateProgress(PlaybackProgress(positionMs = 1_000, durationMs = 60_000))
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(1_500, player.currentPosition)
        player.updatePlayState(false)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(1_500, player.currentPosition)
    }
}
