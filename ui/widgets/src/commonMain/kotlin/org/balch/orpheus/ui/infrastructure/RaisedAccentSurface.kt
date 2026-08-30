package org.balch.orpheus.ui.infrastructure

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * LAYOUT signal: whether the current composition draws the 10-foot / TV-style dock chrome — real
 * Android TV, but ALSO a sufficiently large or fullscreen desktop/tablet window (see
 * DjAppScreen.kt's TV/LargeScreen layout), which needs the same D-pad-focus-readable-from-across-
 * a-room treatment even though it isn't a television. Shared widgets (e.g. the rotary knob dial)
 * branch their focus treatment on this so every other platform (phone, tablet, desktop keyboard
 * focus) keeps its existing look untouched. Provided `true` only around that layout; defaults to
 * `false` everywhere else.
 *
 * This does NOT mean "physically on a television" — a fast fullscreen desktop window sets it too.
 * Use [LocalTelevisionHardware] for anything that must be gated on real TV hardware instead (e.g.
 * [TvGlassEnabled]); conflating the two was exactly the bug that switch's hardware gate fixes.
 */
val LocalTvFocusChrome = compositionLocalOf { false }

/**
 * HARDWARE signal: true only when actually running on television hardware (Chromecast with Google
 * TV, Android TV boxes — see `Context.isTelevision()` in TvMode.android.kt), never on a phone,
 * tablet, or desktop no matter how large or fullscreen the window gets. Provided by DjAppScreen.kt
 * alongside [LocalTvFocusChrome]; defaults to `false`.
 *
 * Distinct on purpose from [LocalTvFocusChrome], which is a LAYOUT signal that a wide/fullscreen
 * window also sets regardless of hardware. Use this one when a decision hinges on the device's
 * actual GPU/CPU headroom (e.g. [TvGlassEnabled]) — a desktop window entering the same layout can
 * afford far more than a television can.
 */
val LocalTelevisionHardware = compositionLocalOf { false }

/**
 * Single on/off switch for every liquid-glass / translucency effect on real television hardware
 * (currently: [org.balch.orpheus.ui.infrastructure.panelGlassChrome]'s blur+tint fill on every
 * docked panel). Measured back to back on a real Chromecast with Google TV: glass OFF averaged 326
 * frames/10s (~33fps, 86.5% janky); glass ON averaged 271 frames/10s (~27fps, 100% janky) —
 * roughly 6ms of added frame time. `false` is the shipping default because a television cannot
 * spare that; flip to `true` only to force glass back on for measurement. Gated on
 * [LocalTelevisionHardware] — NOT [LocalTvFocusChrome] — so a wide/fullscreen desktop window
 * entering the same TV/LargeScreen layout keeps glass (it renders that path at ~117fps, glass is
 * free there); phone, tablet, and windowed desktop never see this switch move at all.
 */
const val TvGlassEnabled = false

// On a dark theme a "raised" look reads through bevel + gradient, not Material elevation
// (drop shadows are near-invisible on black): a lit TOP edge + dark BOTTOM edge, a top→bottom
// convex fill, and a soft tinted glow.

/**
 * Fixed app-chrome base for [orpheusRaisedPlate] — the same cosmicPurple→deepPurple family it
 * wore before the selected visualization drove any part of its fill. A compile-time constant
 * again (not a function of [VisualizationLiquidEffects]), so it is hoisted back to a file-level
 * val instead of being rebuilt every recomposition.
 */
private val raisedPlateBase = Brush.verticalGradient(
    listOf(
        OrpheusColors.cosmicPurple.copy(alpha = 0.70f),
        OrpheusColors.deepPurple.copy(alpha = 0.96f),
    )
)

/** How strongly [orpheusRaisedPlate]'s [Modifier.orpheusRaisedPlate.accent] wash reads over the
 * fixed chrome base. Tuned by eye (DjLayoutRenderHarness's renderTvTopBarVizPalettes /
 * renderTvBottomBarVizPalettes) so the active visualization is obvious at a glance — Heartbeat's
 * pink vs. Lava Lamp's green must look unmistakably different — while the plate still reads as
 * chrome, not a flat color swatch.
 */
private const val PlateWashAlpha = 0.42f

/**
 * The dark app-chrome (cosmicPurple→deepPurple) fill with [accent] washed over it, extracted as
 * its own reusable piece so more than one TV element can wear the exact same "chrome tinted by
 * the visualization" fill — not just a similar-looking one picked independently — without every
 * caller also paying for [orpheusRaisedPlate]'s shadow+border. [washAlpha] defaults to
 * [PlateWashAlpha]; a caller with a smaller/cheaper surface (e.g. a bottom-bar item's docked wash,
 * which intentionally skips the shadow/border for cost reasons) can pass its own.
 *
 * The wash is a second, translucent [accent] fill composited OVER the fixed base, not a
 * replacement for it — an earlier version passed a viz color as the WHOLE fill, which turned the
 * surface into a flat color swatch that stopped reading as chrome. A flat wash alone (no dark
 * base underneath) was ALSO tried and rejected: over a busy/bright backdrop it let the backdrop's
 * own hue bleed through and muddy the intended accent (e.g. a green wash reading as olive over an
 * orange visualization) — the dark base is what keeps the result reading as "this hue," not
 * "whatever's behind it, tinted."
 */
fun Modifier.orpheusChromeWash(
    shape: Shape,
    accent: Color,
    washAlpha: Float = PlateWashAlpha,
): Modifier = this
    .background(raisedPlateBase, shape)
    .background(accent.copy(alpha = washAlpha), shape)

/**
 * [org.balch.orpheus.ui.widgets.AppTitleTreatment]'s own idle plate — [orpheusChromeWash] under a
 * lit [accent] bevel and a matching-tinted shadow — shared so every TV top/bottom-bar element
 * wears the same idle TREATMENT. Callers should source [accent] from the selected visualization's
 * own [VisualizationLiquidEffects.title]`.titleColor` (the same color phone/desktop chrome uses
 * for its title text) so the plate re-themes with whatever's on screen instead of wearing one
 * fixed brand color regardless of it. Default reproduces [VisualizationLiquidEffects.Default]'s
 * own title color — the same neutral chrome every other panel falls back to — for a caller with
 * no visualization in scope.
 *
 * Always fully opaque overall (layering a translucent wash over an already largely-opaque base
 * only ever raises total coverage) so a bright/busy visualization never bleeds through —
 * forceRaised (AppTitleTreatment) exists specifically for that.
 *
 * Pairs with [raisedAccentSurface] for the focused state: idle is this shared plate; focus is a
 * brighter, more saturated accent-tinted plate with no chrome base underneath — both opaque, same
 * shape, so nothing changes size or jumps around when focus arrives.
 */
fun Modifier.orpheusRaisedPlate(
    shape: Shape,
    elevation: Dp = 6.dp,
    accent: Color = VisualizationLiquidEffects.Default.title.titleColor,
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = accent,
        spotColor = accent,
    )
    .orpheusChromeWash(shape = shape, accent = accent)
    .border(width = 1.5.dp, brush = raisedBevelBrush(accent), shape = shape)

/**
 * "Raised on filled" chrome for a TV-focused control: an opaque bevel-gradient plate plus a
 * colored glow, generalizing the dark-theme raised language [AppTitleTreatment] already uses
 * for its clickable state to any accent color. Because the plate is opaque (not just a tint),
 * it stays legible over a bright/busy background, not only against flat black.
 */
fun Modifier.raisedAccentSurface(
    accent: Color,
    shape: Shape,
    elevation: Dp = 6.dp,
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = accent,
        spotColor = accent,
    )
    .background(
        Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.82f)),
        ),
        shape,
    )
    .border(width = 1.5.dp, brush = raisedBevelBrush(accent), shape = shape)

/**
 * Lit-[accent]-to-dark bevel border shared by [orpheusRaisedPlate] and [raisedAccentSurface].
 * Unlike [raisedPlateBase] (the fixed chrome fill, hoisted to a file-level val above), this
 * can't be hoisted the same way: [accent] follows whichever visualization is currently active,
 * so it's a genuine per-call input, not a compile-time constant. Factored into one function
 * instead of being duplicated inline in both callers, so there's only one place building it.
 */
private fun raisedBevelBrush(accent: Color): Brush = Brush.verticalGradient(
    listOf(accent.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.55f)),
)

/** How long the TV region-focus borders stay fully shown after the last D-pad key event, before
 * starting to fade. Named and hoisted here specifically so it is easy to find and retune once
 * this is judged on a real remote — see [TvFocusRegionHolder.activityTick].
 */
const val TvFocusIdleTimeoutMs = 5_000L

/** Fade-out duration once [TvFocusIdleTimeoutMs] elapses with no activity. Deliberately much
 * shorter than the reverse direction: [TvFocusRegionHolder.notifyActivity] snaps back to fully
 * shown instantly (no animation) because a user pressing a direction must never wonder where
 * focus went — only fading OUT is worth animating.
 */
const val TvFocusFadeOutMs = 500

/**
 * Tracks which ONE "container" — a docked panel, or the top/bottom TV nav bar — currently holds
 * D-pad focus, so at most one region-focus border is ever visible at a time. Every container
 * routes through this single shared value instead of deciding independently from its own
 * onFocusChanged: two containers' focus-gained/focus-lost callbacks can arrive in either order
 * during a transition, so an old container's stale "I lost focus" must not clobber a newer
 * container's "I gained focus" — [setFocused]'s identity check ([current] only clears when the
 * caller IS the current token) makes that race structurally impossible rather than merely rare.
 *
 * Also the single source of truth for the idle-fade: [alpha] is the multiplier
 * [tvFocusRegionBorder] applies to whichever container's border is currently drawn, and
 * [activityTick] is what a D-pad-idle watcher (see DjAppScreen's TV branch) keys a `LaunchedEffect`
 * on to restart the "show now, fade after [TvFocusIdleTimeoutMs]" cycle. Both live here, next to
 * [current], rather than as a parallel per-container mechanism, since at most one container's
 * border is ever visible anyway — one shared fade for whichever one that is.
 */
class TvFocusRegionHolder {
    var current: Any? by mutableStateOf(null)

    fun setFocused(token: Any, focused: Boolean) {
        if (focused) current = token
        else if (current === token) current = null
    }

    /** 1f = fully shown, 0f = faded out after D-pad idle. Read ONLY inside
     * [tvFocusRegionBorder]'s draw phase — an [Animatable] is a [androidx.compose.runtime.State],
     * so reading `.value` there scopes invalidation to that draw call alone, the same trick
     * [current] already relies on. Never read this from a composable body: doing so would
     * recompose that composable (and everything it calls) on every animation frame of the fade,
     * which is exactly the cost this design avoids.
     */
    val alpha = Animatable(1f)

    /** Bumped by the TV layout root's `onPreviewKeyEvent` for every D-pad key. A composable
     * watcher keys a `LaunchedEffect` on this value so each new key event cancels any in-flight
     * fade, snaps [alpha] back to 1f immediately, and restarts the idle countdown — see
     * DjAppScreen.kt's `TvFocusIdleWatcher`.
     */
    var activityTick: Int by mutableIntStateOf(0)
        private set

    fun notifyActivity() {
        activityTick++
    }
}

/**
 * Null everywhere except the DJ app's TV/LargeScreen layout, which provides a real
 * [TvFocusRegionHolder] around its top bar + dock + bottom bar. Every other layout draws no
 * region-focus border at all.
 */
val LocalTvFocusRegion = compositionLocalOf<TvFocusRegionHolder?> { null }

/**
 * Cheap, exclusive region-focus border. [holder]'s current token AND its idle-fade [TvFocusRegionHolder.alpha]
 * are both read inside the DRAW phase ([drawWithContent]), not composition or layout, so neither
 * ever recomposes or re-lays-out a container or its content — the container's content lambda is
 * never re-invoked because of this. The two reads are NOT scoped equally, though: [holder.current]
 * is read unconditionally by every container wearing this modifier (the `===` check below is
 * itself a read), so a focus change repaints every one of them, not just the previously- and
 * newly-focused pair. [holder.alpha] is read only once `holder.current === token` is already
 * known true, so the short-circuit DOES keep a pure fade-animation frame (focus unchanged, only
 * the idle fade ticking) scoped to the one container currently focused. In practice the fan-out on
 * focus change costs nothing extra here — the visualization already forces a full frame every
 * frame — but it is real, not the narrow two-containers-touched read this might otherwise suggest.
 * [token] should be a stable per-container identity — `remember { Any() }` at that container's
 * call site is enough, since equality here is by reference, not structural.
 */
fun Modifier.tvFocusRegionBorder(
    holder: TvFocusRegionHolder?,
    token: Any,
    color: Color,
    shape: Shape,
    width: Dp = 2.dp,
): Modifier {
    if (holder == null) return this
    return this.drawWithContent {
        drawContent()
        // holder.alpha.value is read ONLY once current === token is already known true, so
        // short-circuit && keeps every OTHER container's draw scope from subscribing to the fade
        // at all — only the one container actually drawing a border repaints on each fade frame.
        if (holder.current === token) {
            val fade = holder.alpha.value
            if (fade > 0f) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = color.copy(alpha = color.alpha * fade),
                    style = Stroke(width.toPx()),
                )
            }
        }
    }
}
