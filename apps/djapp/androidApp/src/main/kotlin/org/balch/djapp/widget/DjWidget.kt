package org.balch.djapp.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.djapp.MainActivity
import org.balch.djapp.R
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.widget.DjWidgetSnapshot
import org.balch.orpheus.djapp.widget.DjWidgetSnapshotBuilder
import org.balch.orpheus.features.timer.TimerStatus

class DjWidget : GlanceAppWidget() {

    // Compact (4x1) single-row vs. taller two-row. Glance picks the largest
    // bucket that fits the placed/resized widget; we branch on the resolved
    // height in Content().
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Resolve the live state flows ONCE. getFeature() can throw during the
        // cold-start race (launcher renders before features register) or after a
        // misconfiguration; fall back to no sources so the widget renders an idle
        // snapshot rather than erroring out of provideGlance.
        val sources = runCatching { DjWidgetData.sources(context) }.getOrNull()
        provideContent {
            // Read state INSIDE the composition (collectAsState) — never as a value
            // captured before provideContent. Glance does not re-run provideGlance
            // on update()/updateAll(); it only recomposes this content. Observing
            // the flows here is what makes taps + background changes re-render.
            val snapshot = if (sources != null) rememberWidgetSnapshot(sources) else IDLE_SNAPSHOT
            Content(snapshot)
        }
    }

    /**
     * Fold the live graph flows into a [DjWidgetSnapshot]. Each `collectAsState()`
     * registers its flow as observed Compose state, so a change to playback / vibe
     * / artwork / timer-status recomposes this widget and regenerates the
     * RemoteViews the launcher draws.
     *
     * The two `StateFlow.value` reads below are deliberate (and the lint check is a
     * false-positive here): one supplies collectAsState's synchronous initial so
     * the first RemoteViews push is correct (no cold-start flash), the other reads
     * the *current* remaining time on every recompose to re-seed the host-side
     * Chronometer. Carrying timer state through distinctUntilChangedBy instead
     * would re-seed the Chronometer with a stale value on unrelated recomposes
     * (e.g. a vibe skip), making the countdown jump backward.
     */
    @SuppressLint("StateFlowValueCalledInComposition")
    @Composable
    private fun rememberWidgetSnapshot(sources: DjWidgetData.Sources): DjWidgetSnapshot {
        val title by sources.title.collectAsState()
        val subtitle by sources.subtitle.collectAsState()
        val artwork by sources.artworkPng.collectAsState()
        val playback by sources.playback.collectAsState()
        // Observe only timer STATUS transitions, not the per-second remainingTime
        // ticks: TimerChip's Chronometer ticks on the host, so recomposing every
        // second would be wasted RemoteViews churn (mirrors DjWidgetUpdater). We
        // read remainingTime non-reactively below to (re)seed the Chronometer on
        // each status change.
        val timerStatus by remember(sources) {
            sources.timer.map { it.status }.distinctUntilChanged()
        }.collectAsState(initial = sources.timer.value.status)

        return DjWidgetSnapshotBuilder.build(
            currentVibe = title,
            albumTitle = subtitle,
            vibeNames = emptyList(),
            isPlaying = playback == PlaybackState.Playing,
            timerRunning = timerStatus == TimerStatus.RUNNING,
            timerRemainingSeconds = sources.timer.value.remainingTime.inWholeSeconds,
            timerStatus = timerStatus.name,
            artworkPng = artwork,
        )
    }

    @Composable
    internal fun Content(
        snapshot: DjWidgetSnapshot
    ) {
        // Full-bleed layout: art → scrim → content stacked in a Box.
        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(20.dp)) {
            Image(
                provider = artworkProvider(snapshot.artworkPng),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
            Box(modifier = GlanceModifier.fillMaxSize().background(SCRIM)) {}

            if (LocalSize.current.height < TALL_THRESHOLD) {
                CompactContent(snapshot)
            } else {
                TallContent(snapshot)
            }
        }
    }

    /** 4x1: title + (timer + stop when active) + transport, all on one row. */
    @Composable
    private fun CompactContent(snapshot: DjWidgetSnapshot) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                if (snapshot.timerActive()) {
                    TimerChip(snapshot, compact = true)
                    Spacer(GlanceModifier.width(8.dp))
                }
                Text(
                    text = snapshot.currentVibe,
                    maxLines = 1,
                    style = TextStyle(color = TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                )
                if (snapshot.albumTitle.isNotBlank()) {
                    Text(
                        text = snapshot.albumTitle,
                        maxLines = 1,
                        style = TextStyle(color = MUTED, fontSize = 10.sp),
                    )
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            TransportGroup(snapshot, compact = true)
        }
    }

    /** Taller: row 1 = title/album (+ timer + stop), row 2 = centered transport. */
    @Composable
    private fun TallContent(snapshot: DjWidgetSnapshot) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                    Text(
                        text = snapshot.currentVibe,
                        maxLines = 1,
                        style = TextStyle(color = TEXT, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                    )
                    if (snapshot.albumTitle.isNotBlank()) {
                        Text(
                            text = snapshot.albumTitle,
                            maxLines = 1,
                            style = TextStyle(color = MUTED, fontSize = 11.sp),
                        )
                    }
                }
                if (snapshot.timerActive()) {
                    Spacer(GlanceModifier.width(8.dp))
                    TimerChip(snapshot, compact = false)
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportGroup(snapshot, compact = false)
            }
        }
    }

    /** Countdown + a stop button that cancels the timer (shown only while the
     *  timer is active). The countdown is a self-ticking [android.widget.Chronometer]
     *  embedded via [AndroidRemoteViews]: it ticks on the launcher/host, so it
     *  keeps counting even while the app process is frozen in the background —
     *  unlike a Glance Text, which would only update when DjWidgetUpdater pushed
     *  a refresh (it doesn't, per second, by design). */
    @Composable
    private fun TimerChip(snapshot: DjWidgetSnapshot, compact: Boolean) {
        val context = LocalContext.current
        val stopSize = if (compact) 26.dp else 30.dp
        Row(
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chrono = RemoteViews(context.packageName, R.layout.widget_chrono).apply {
                val base = SystemClock.elapsedRealtime() + snapshot.timerRemainingSeconds * 1_000L
                // started = running: ticks down when RUNNING, frozen when PAUSED.
                setChronometer(R.id.widget_chronometer, base, null, snapshot.timerRunning)
                setChronometerCountDown(R.id.widget_chronometer, true)
            }
            AndroidRemoteViews(remoteViews = chrono)
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .size(stopSize)
                    .cornerRadius(stopSize / 2)
                    .background(PILL)
                    .clickable(actionRunCallback<TimerStopAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = TextStyle(color = TEXT, fontSize = 12.sp))
            }
        }
    }

    /** The three transport pills with tight, fixed spacing (no weight spread). */
    @Composable
    private fun TransportGroup(snapshot: DjWidgetSnapshot, compact: Boolean) {
        val w = if (compact) 46.dp else 56.dp
        val h = if (compact) 32.dp else 38.dp
        TransportButton("◀◀", actionRunCallback<SkipPrevAction>(), w, h)
        Spacer(GlanceModifier.width(8.dp))
        TransportButton(
            label = if (snapshot.isPlaying) "❚❚" else "▶",
            action = actionRunCallback<PlayPauseAction>(),
            w = w,
            h = h,
            accent = true,
        )
        Spacer(GlanceModifier.width(8.dp))
        TransportButton("▶▶", actionRunCallback<SkipNextAction>(), w, h)
    }

    /** A pill-shaped (rounded-rectangle) transport button. */
    @Composable
    private fun TransportButton(
        label: String,
        action: Action,
        w: Dp,
        h: Dp,
        accent: Boolean = false,
    ) {
        Box(
            modifier = GlanceModifier
                .width(w)
                .height(h)
                .cornerRadius(h / 2)
                .background(if (accent) ACCENT else PILL)
                .clickable(action)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = if (accent) ON_ACCENT else TEXT,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    private fun DjWidgetSnapshot.timerActive(): Boolean =
        timerRunning || timerStatus == "RUNNING" || timerStatus == "PAUSED"

    /** Decode + downscale artwork so it stays under the RemoteViews/Binder
     *  transaction limit (full-size album PNGs silently fail to render). */
    private fun artworkProvider(png: ByteArray?): ImageProvider {
        val bmp = png?.let { decodeScaled(it) }
        return if (bmp != null) ImageProvider(bmp) else ImageProvider(R.mipmap.ic_launcher)
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= TARGET_ART_PX && h / (sample * 2) >= TARGET_ART_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    private companion object {
        val COMPACT = DpSize(220.dp, 48.dp)
        val TALL = DpSize(220.dp, 110.dp)
        val TALL_THRESHOLD = 90.dp
        const val TARGET_ART_PX = 288

        /** Rendered when the app graph isn't resolvable yet (cold-start race). */
        val IDLE_SNAPSHOT = DjWidgetSnapshot(
            currentVibe = "—",
            albumTitle = "",
            nextVibe = "—",
            isPlaying = false,
            timerRunning = false,
            timerRemainingSeconds = 0L,
            timerStatus = "IDLE",
            artworkPng = null,
        )

        val SCRIM = fixedColor(0x99000000) // ~60% — lets the art show through
        val PILL = fixedColor(0x33FFFFFF)
        val ACCENT = fixedColor(0xFF7C4DFF) // cosmic purple
        val ON_ACCENT = fixedColor(0xFFFFFFFF)
        val TEXT = fixedColor(0xFFFFFFFF)
        val MUTED = fixedColor(0xFFCCCCDD)

        /**
         * Wrap a literal ARGB color in a Glance [ColorProvider].
         *
         * `ColorProvider(Color)` is the *public* Glance API (`FixedColorProvider`)
         * and the right choice for a widget — it bakes in the literal color value,
         * avoiding the launcher-vs-app process color-resolution ambiguity that the
         * resource-id overload's own docs warn against. The IDE's `RestrictedApi`
         * check is a false-positive: it resolves the call at the `ColorProviderKt`
         * level and trips on the `@RestrictTo` `resId` sibling overload, while the
         * Kotlin compiler resolves the public overload correctly (hence the build
         * passes). Suppression is scoped to this single call site.
         */
        @SuppressLint("RestrictedApi")
        private fun fixedColor(argb: Long): ColorProvider = ColorProvider(Color(argb))
    }
}
