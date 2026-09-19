package org.balch.orpheus.djapp

/**
 * Design canvas width the dock's chrome and panels are authored against. The panels have fixed
 * content widths and do not reflow: below this, labels wrap and knobs clip. Verified on a
 * Chromecast with Google TV by sweeping `adb shell wm density` — 960dp clips, 1097dp still
 * breaks GAIN, 1280dp renders everything.
 */
const val LargeScreenDesignWidthDp = 1280f

/**
 * `smallestScreenWidthDp` at or above which a screen is tablet-class and worth docking. This is
 * Android's own tablet qualifier, so it needs no dpi guesswork: a Galaxy Z Fold inner display
 * reports 752, its cover display 360, and an ordinary phone 360-420.
 */
const val TabletClassSmallestWidthDp = 600

/**
 * Density multiplier that widens the dp canvas until the dock's fixed-width panels fit.
 * Callers fold this into [androidx.compose.ui.unit.Density.density] and never into fontScale,
 * which would compound the user's own accessibility text-size setting.
 *
 * Scaling density *below* 1f grows the reported canvas (fixed pixels / smaller density = more
 * dp), which is what lets a screen that is physically large but dp-narrow reach both
 * [LargeScreenDesignWidthDp] and the [resolveLayout] thresholds.
 *
 * **Landscape only.** A tablet-class screen held in portrait keeps its native density and so
 * keeps the phone layout: on a foldable the two orientations are meant to be two different
 * products — unfold and turn sideways for the dock, hold it upright for the mobile view. The
 * density change is part of that transition rather than an interruption of it, because the
 * layout is being replaced in the same frame either way.
 *
 * On a Z Fold inner display (834.7 x 752dp landscape) the scale is 834.7/1280 = 0.652, yielding
 * a 1280 x 1153dp canvas. Portrait returns 1f and measures its native 752 x 834.7dp, which falls
 * under [LargeScreenMinWidth] exactly as the phone layout requires.
 *
 * Returns 1f — a no-op — off tablet-class hardware, and never returns a value above 1f: scaling
 * up would NARROW the canvas and push a device that already fits back over the cliff. A Pixel
 * Tablet (1280x800dp) computes exactly 1.0 and is left untouched, and a screen already wider
 * than the design width keeps whatever mode its native dp earned.
 */
fun largeScreenDensityScale(
    widthDp: Float,
    heightDp: Float,
    smallestWidthDp: Int,
    isTelevision: Boolean,
    tabletop: Boolean = false,
): Float {
    // Tabletop keeps native size: the flat half is where fingers are.
    if (tabletop) return 1f

    val eligible = isTelevision || smallestWidthDp >= TabletClassSmallestWidthDp
    if (!eligible) return 1f

    // Portrait keeps the mobile view. A television never reports portrait, so this costs TV
    // nothing while making the foldable's two orientations behave as two distinct layouts.
    if (heightDp > widthDp) return 1f

    val raw = widthDp / LargeScreenDesignWidthDp
    // Already at or above the design width: leave the canvas exactly as the platform reports it.
    if (raw >= 1f) return 1f

    return clampDensityScale(raw)
}

/**
 * Floor on how far [largeScreenDensityScale] may shrink the UI to win the dock.
 *
 * The scale is a straight trade: it buys dp canvas by making every control physically smaller.
 * At 1f a dp is its nominal 1/160 inch; at 0.652 (the Z Fold inner display) the whole UI renders
 * at roughly 74% of nominal, putting a 48dp touch target near 5.7mm against Android's ~9mm
 * guidance. That is a deliberate trade on a screen being used as a console, but it does not
 * survive being pushed much further.
 *
 * 0.6f puts the cutoff at a landscape width of 768dp (0.6 x 1280). Every tablet-class screen at
 * or above that widens to the full design canvas; anything narrower would have to shrink past
 * the point where the controls stay hittable.
 */
private const val MinimumDensityScale = 0.6f

/**
 * Applies [MinimumDensityScale], abandoning the scale entirely rather than half-applying it.
 *
 * Below the floor this returns 1f, so the device keeps whichever layout its native dp already
 * earned — on a small tablet-class screen that is the phone layout, which works. The rejected
 * alternative was clamping to the floor itself: that keeps the dock but hands it a canvas under
 * 1280dp, and the panels do not reflow (1097dp still breaks the GAIN label). A dock that does
 * not appear is a recoverable disappointment; a dock with clipped labels and unhittable knobs is
 * a broken screen.
 *
 * @param scale the unclamped scale, always in (0f, 1f).
 * @return [scale] when it clears the floor, otherwise 1f.
 */
internal fun clampDensityScale(scale: Float): Float =
    if (scale >= MinimumDensityScale) scale else 1f
