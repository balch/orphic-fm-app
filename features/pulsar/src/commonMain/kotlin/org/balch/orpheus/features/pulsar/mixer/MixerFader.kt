package org.balch.orpheus.features.pulsar.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.infrastructure.liquefiableVizEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.roundToInt

/** Tolerance (in fader travel units) for the unity snap detent. ±0.025 ≈ 2.5%
 *  of throw, narrow enough to leave fine control near unity, wide enough to
 *  feel like a deliberate detent rather than an accidental snap. */
private const val UNITY_SNAP_TOLERANCE = 0.025f

/**
 * Vertical fader with an integrated LED meter ladder rendered inside the track,
 * topped by a glowing band-tinted thumb. The thumb visually wraps the track edges
 * (it is wider than the track) and floats over the LEDs without constraining them.
 *
 * The track + LEDs render as the liquid source; a sibling overlay applies the
 * liquid glass refraction/tint. The thumb is drawn last so it floats above the
 * glass and remains crisp for interaction.
 *
 * @param value Current fader position 0..1 (0 = bottom, 1 = top).
 * @param onValueChange Continuous callback during drag.
 * @param accentColor Band accent (drives lit-LED color and thumb gradient + glow).
 * @param meterLevel Display fraction 0..1 controlling how many LEDs are lit (already
 *   dB-scaled by the caller).
 * @param glowIntensity 0..1, scales thumb shadow elevation. Drive off live RMS.
 * @param dimmed When true, draws the fader at reduced opacity (muted state).
 * @param alwaysHot When true, all lit LEDs use [accentColor] (no red hot-zone) —
 *   used by DIST where the "meter" reflects the user's drive setting.
 * @param peakStyle When true, lit LEDs use stoplight zones (green/yellow/red by
 *   vertical position) rather than [accentColor] / red hot-zone — used by DIST so
 *   the meter reads as a classic VU display.
 * @param glassTint Color of the liquid glass overlay. Defaults to [accentColor].
 *   DIST overrides with red so the strip reads as "in the red."
 * @param glassTintAlpha Alpha of the glass tint (0..1). Higher = more colored glass.
 * @param unityTravel If non-null, draws a horizontal tick across the track at
 *   this travel position (0..1) — the unity (0 dB) reference for console-style
 *   band faders. DIST leaves it null since drive has no unity convention.
 */
@Composable
internal fun MixerFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    accentColor: Color,
    meterLevel: Float,
    glowIntensity: Float,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    trackHeight: Int = 140,
    trackWidth: Int = 20,
    thumbWidth: Int = 38,
    thumbHeight: Int = 14,
    ledCount: Int = 18,
    hotZoneCount: Int = 4,
    alwaysHot: Boolean = false,
    peakStyle: Boolean = false,
    glassTint: Color = accentColor,
    glassTintAlpha: Float = 0.15f,
    unityTravel: Float? = null,
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { trackHeight.dp.toPx() }
    val thumbHeightPx = with(density) { thumbHeight.dp.toPx() }
    val usableRange = trackHeightPx - thumbHeightPx

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Bottom of thumb: 0 == fully bottom, usableRange == fully top.
    val thumbBottomPx = if (dragging) dragOffset
        else (value.coerceIn(0f, 1f) * usableRange)
    val thumbY = (usableRange - thumbBottomPx).roundToInt()

    val effectiveAlpha = if (dimmed) 0.4f else 1f
    val glowDp = (4 + glowIntensity * 14).dp

    val litCount = if (dimmed) 0
        else (meterLevel.coerceIn(0f, 1f) * ledCount).toInt().coerceAtMost(ledCount)
    val unlitColor = Color(0xFF15101A)

    // Per-fader liquid state: the LED ladder is the liquid source; a sibling
    // overlay reads from this state to refract/tint what's beneath the glass.
    val faderLiquidState = rememberLiquidState()

    val unityNotchY: Int? = unityTravel?.let {
        val notchHeightPx = with(density) { 2.dp.toPx() }
        val unityCenter = (usableRange - it.coerceIn(0f, 1f) * usableRange) + thumbHeightPx / 2f
        (unityCenter - notchHeightPx / 2f).roundToInt()
    }

    Box(
        modifier = modifier
            .width((thumbWidth + 16).dp)
            .height(trackHeight.dp)
            .pointerInput(Unit) {
                // detectDragGestures waits for ~16dp of touch slop before firing
                // onDragStart, so a pure tap never moves the thumb. Use
                // awaitEachGesture + awaitFirstDown so the thumb snaps to the
                // touch position the instant the finger lands, then drag()
                // streams subsequent moves with no slop budget to spend.
                //
                // Unity snap: when a unity-travel reference exists, any
                // candidate value within UNITY_SNAP_TOLERANCE of it clamps to
                // exactly the unity point — feels like a tactile detent. The
                // finger has to travel past the band edge to "break free,"
                // because the thumb stays pinned at unity until the finger
                // leaves the tolerance window.
                fun travelFromY(y: Float): Float {
                    // Finger Y → thumb-top Y by subtracting half the thumb,
                    // so the thumb CENTERS on the touch point. Without the
                    // offset the thumb's *top* aligns with the finger,
                    // visually placing the thumb half a thumb-height below
                    // where the user expected.
                    val thumbTopY = y - thumbHeightPx / 2f
                    val raw = ((usableRange - thumbTopY) / usableRange).coerceIn(0f, 1f)
                    val snapTo = unityTravel ?: return raw
                    return if (kotlin.math.abs(raw - snapTo) <= UNITY_SNAP_TOLERANCE) snapTo else raw
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    val downTravel = travelFromY(down.position.y)
                    dragOffset = downTravel * usableRange
                    currentOnValueChange(downTravel)
                    down.consume()

                    drag(down.id) { change ->
                        change.consume()
                        val travel = travelFromY(change.position.y)
                        dragOffset = travel * usableRange
                        currentOnValueChange(travel)
                    }
                    dragging = false
                }
            }
    ) {
        // 1. Unity notch (behind the track). Drawn BEFORE the LED ladder so
        //    its center is covered by the track backdrop — only the tick
        //    edges that extend past the track width show. Sized to extend
        //    just 4 dp past the track on each side: enough to read as a
        //    "scale mark on the chassis," small enough to stay subtle.
        if (unityNotchY != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, unityNotchY) }
                    .size((trackWidth + 8).dp, 2.dp)
                    .background(Color.White.copy(alpha = 0.35f * effectiveAlpha)),
            )
        }

        // 2. LED ladder — the SOURCE for the liquid effect. The track box hosts
        //    the gradient backdrop; .liquefiable(state) marks it as the visual
        //    that the glass overlay will refract.
        //
        // ORDER MATTERS: `.liquefiable(state)` must come BEFORE any draw
        // modifiers (clip / background / border / padding). The library only
        // records draw operations that happen *after* the liquefiable node —
        // so if .background sits earlier in the chain, the gradient backdrop
        // never reaches the glass overlay's sample buffer and the glass
        // refracts (effectively) empty content.
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .width(trackWidth.dp)
                .height(trackHeight.dp)
                .liquefiableVizEffects(faderLiquidState)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF18101E),
                            Color(0xFF07050B),
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.05f * effectiveAlpha), RoundedCornerShape(5.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp),
        ) {
            // Top-down: index 0 is top of ladder.
            for (i in 0 until ledCount) {
                val ladderIndex = ledCount - 1 - i  // bottom-up lighting count
                val isLit = ladderIndex < litCount
                val isHotZone = i < hotZoneCount
                // Bottom of ladder reads dimmer; top reads brighter — like a real meter where
                // quiet activity sits low and loud peaks dominate visually.
                val positionFraction = ladderIndex.toFloat() / (ledCount - 1).coerceAtLeast(1)
                val ledAlpha = effectiveAlpha * (0.4f + positionFraction * 0.6f)
                val color = when {
                    !isLit -> unlitColor
                    peakStyle -> stoplightAt(positionFraction).copy(alpha = ledAlpha)
                    alwaysHot -> accentColor.copy(alpha = ledAlpha)
                    isHotZone -> OrpheusColors.mixerDistRed.copy(alpha = ledAlpha)
                    else -> accentColor.copy(alpha = ledAlpha)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
                )
            }
        }

        // 3. Glass overlay — sibling of the liquefiable source. Sits over the
        //    track and refracts/tints the LEDs beneath. Same shape/size as the
        //    track; thumb passes over this and remains visually crisp.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(trackWidth.dp)
                .height(trackHeight.dp)
                .liquidVizEffects(
                    liquidState = faderLiquidState,
                    scope = VisualizationLiquidScope(
                        refraction = 0.3f,
                        saturation = 1.5f,
                        dispersion = 0.5f,
                        curve = 0.1f,
                        edge = .01f,
                        contrast = .8f,
                    ),
                    frostAmount = 0.dp,
                    color = glassTint,
                    tintAlpha = glassTintAlpha * effectiveAlpha,
                    shape = RoundedCornerShape(5.dp),
                )
        )

        // 4. Glowing thumb — drawn LAST so it floats above the glass and stays
        //    crisp for interaction.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbY) }
                .shadow(
                    elevation = glowDp,
                    shape = RoundedCornerShape(6.dp),
                    ambientColor = accentColor,
                    spotColor = accentColor,
                )
                .size(thumbWidth.dp, thumbHeight.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = effectiveAlpha),
                            accentColor.copy(alpha = 0.4f * effectiveAlpha),
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.25f * effectiveAlpha), RoundedCornerShape(6.dp))
        )
    }
}

/**
 * Classic VU meter zones used by [MixerFader]'s peakStyle mode for the
 * DIST peak meter: green for safe levels, yellow for hot, red for clipping.
 * @param fraction LED position in the ladder, 0 = bottom, 1 = top.
 */
private fun stoplightAt(fraction: Float): Color = when {
    fraction >= 0.85f -> OrpheusColors.mixerDistRed
    fraction >= 0.60f -> OrpheusColors.mixerFxAmber
    else -> OrpheusColors.synthGreen
}

@Preview(widthDp = 420, heightDp = 240)
@Composable
private fun MixerFaderPreview() {
    LiquidPreviewContainerWithGradient(
        modifier = Modifier.size(420.dp, 240.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            FaderColumn("BASS\nidle", OrpheusColors.neonCyan) {
                MixerFader(
                    value = 0.55f,
                    onValueChange = {},
                    accentColor = OrpheusColors.neonCyan,
                    meterLevel = 0.18f,
                    glowIntensity = 0.15f,
                    unityTravel = 0.75f,
                )
            }
            FaderColumn("KEYS\nactive", OrpheusColors.synthGreen) {
                MixerFader(
                    value = 0.75f,
                    onValueChange = {},
                    accentColor = OrpheusColors.synthGreen,
                    meterLevel = 0.55f,
                    glowIntensity = 0.45f,
                    unityTravel = 0.75f,
                )
            }
            FaderColumn("PERC\nhot", OrpheusColors.mixerPercPink) {
                MixerFader(
                    value = 0.92f,
                    onValueChange = {},
                    accentColor = OrpheusColors.mixerPercPink,
                    meterLevel = 0.92f,
                    glowIntensity = 0.9f,
                    unityTravel = 0.75f,
                )
            }
            FaderColumn("GAIN\npeak", OrpheusColors.mixerDistRed) {
                MixerFader(
                    value = 0.5f,
                    onValueChange = {},
                    accentColor = OrpheusColors.mixerDistRed,
                    meterLevel = 0.7f,
                    glowIntensity = 0.6f,
                    glassTint = OrpheusColors.brightSilver,
                    glassTintAlpha = 0.20f,
                )
            }
            FaderColumn("FX\nmuted", OrpheusColors.mixerFxAmber) {
                MixerFader(
                    value = 0.5f,
                    onValueChange = {},
                    accentColor = OrpheusColors.mixerFxAmber,
                    meterLevel = 0f,
                    glowIntensity = 0f,
                    dimmed = true,
                )
            }
        }
    }
}

@Composable
private fun FaderColumn(
    label: String,
    accent: Color,
    fader: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        fader()
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            textAlign = TextAlign.Center,
            text = label,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}
