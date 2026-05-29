package org.balch.djapp.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.LocalSize
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import org.balch.orpheus.djapp.widget.DjWidgetSnapshot

/**
 * Android Studio previews for [DjWidget], kept in the `debug` source set so the
 * preview tooling (`glance-preview` + `glance-appwidget-preview`, both
 * `debugImplementation`) never ships in a release build.
 *
 * These use the `@Preview` from `androidx.glance.preview` — NOT Compose's
 * `androidx.compose.ui.tooling.preview.Preview`, which cannot render Glance
 * composables. `widthDp`/`heightDp` size the preview surface; we additionally
 * pin [LocalSize] so [DjWidget.Content]'s compact-vs-tall branch
 * (`LocalSize.current.height < TALL_THRESHOLD`) is deterministic regardless of
 * how the host seeds it.
 *
 * Caveat: the timer countdown (an `AndroidRemoteViews` Chronometer) and decoded
 * album art (`ImageProvider(Bitmap)`) only render on-device — the preview host
 * has no live binder, so the chip is blank and artwork falls back to the
 * launcher icon. Previews are for the layout/text/transport-pill arrangement.
 */

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 220, heightDp = 50)
@Composable
private fun DjWidgetCompactPreview() {
    CompositionLocalProvider(LocalSize provides DpSize(220.dp, 50.dp)) {
        DjWidget().Content(sampleSnapshot(isPlaying = true, timerRunning = true))
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 220, heightDp = 110)
@Composable
private fun DjWidgetTallPreview() {
    CompositionLocalProvider(LocalSize provides DpSize(220.dp, 110.dp)) {
        DjWidget().Content(sampleSnapshot(isPlaying = false, timerRunning = false))
    }
}

private fun sampleSnapshot(isPlaying: Boolean, timerRunning: Boolean) = DjWidgetSnapshot(
    currentVibe = "Dog House",
    albumTitle = "Stealth",
    nextVibe = "Dust Groove",
    isPlaying = isPlaying,
    timerRunning = timerRunning,
    timerRemainingSeconds = 754L,
    timerStatus = if (timerRunning) "RUNNING" else "IDLE",
    artworkPng = null,
)
