package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where every part of the television sits in the window. Pure geometry, no drawing. */
internal data class TvLayout(
    val ears: Rect,      // bounding box of the antenna, above the cabinet
    val cabinet: Rect,
    val screen: Rect,    // the 4:3 glass, inside the cabinet's left part
    val controls: Rect,  // dial + grille column, right of the screen
    val inset: Rect,     // the face box, upper right of the screen
    val banner: Rect,    // lower third
    val ticker: Rect,    // bottom strip of the screen
)

private const val CABINET_ASPECT = 1.42f      // width : height of the wooden box
private const val EARS_WIDTH = 0.70f          // of the cabinet width
private const val EARS_HEIGHT = 0.32f         // of the cabinet height
private const val BEZEL = 0.06f               // of the cabinet height
private const val CONTROLS_WIDTH = 0.20f      // of the cabinet width
private const val TICKER_HEIGHT = 0.09f       // of the screen height
private const val BANNER_HEIGHT = 0.13f       // of the screen height
private const val INSET_WIDTH = 0.36f         // of the screen width
private const val INSET_ASPECT = 1f / 1.12f   // width : height
private const val INSET_RIGHT = 0.05f         // gap from the screen's right edge
private const val INSET_TOP = 0.07f           // gap from the screen's top edge

/** Margin left around the set on every edge, as a share of the stage's shorter side. */
internal const val STAGE_INSET = 0.04f

/** Half-width of the story box's frame, as a share of the screen height. */
internal const val INSET_BORDER = 0.006f

/** Depth of the caption tab under the story box, as a share of the box height. */
internal const val INSET_TAB = 0.17f

/**
 * [stage] is the area the host has left for the picture, in the same coordinates as [window]:
 * the set is scaled to fit it and centred in it. Null, degenerate or off-window stages fall back
 * to the whole window. Degenerate windows collapse to zero-size rects at the origin, not to NaN.
 */
internal fun tvLayout(window: Size, stage: Rect? = null): TvLayout {
    val w = if (window.width.isFinite()) window.width.coerceAtLeast(0f) else 0f
    val h = if (window.height.isFinite()) window.height.coerceAtLeast(0f) else 0f

    val area = usableStage(stage, Rect(0f, 0f, w, h))
    val pad = min(area.width, area.height) * STAGE_INSET
    val fit = Rect(area.left + pad, area.top + pad, area.right - pad, area.bottom - pad)

    // The antenna is part of the silhouette, so the fit measures cabinet plus ears.
    val cabinetH = min(fit.height / (1f + EARS_HEIGHT), fit.width / CABINET_ASPECT).coerceAtLeast(0f)
    val cabinetW = cabinetH * CABINET_ASPECT

    val earsH = cabinetH * EARS_HEIGHT
    val blockTop = fit.top + (fit.height - (cabinetH + earsH)) / 2f
    val cabinetLeft = fit.left + (fit.width - cabinetW) / 2f
    val cabinet = Rect(cabinetLeft, blockTop + earsH, cabinetLeft + cabinetW, blockTop + earsH + cabinetH)

    val earsW = cabinetW * EARS_WIDTH
    val ears = Rect(cabinet.center.x - earsW / 2f, blockTop, cabinet.center.x + earsW / 2f, cabinet.top)

    val bezel = cabinetH * BEZEL
    val controls = Rect(
        cabinet.right - bezel - cabinetW * CONTROLS_WIDTH, cabinet.top + bezel,
        cabinet.right - bezel, cabinet.bottom - bezel,
    )
    // The glass is the largest 4:3 rect in what the bezel and the control column leave, centred
    // in it, so a wide cabinet gains bezel rather than stretching the picture.
    val glassBox = Rect(cabinet.left + bezel, cabinet.top + bezel, controls.left - bezel, cabinet.bottom - bezel)
    val screenW = min(glassBox.width, glassBox.height * 4f / 3f)
    val screenH = screenW * 3f / 4f
    val screen = Rect(
        glassBox.left, glassBox.top + (glassBox.height - screenH) / 2f,
        glassBox.left + screenW, glassBox.top + (glassBox.height + screenH) / 2f,
    )

    val ticker = Rect(screen.left, screen.bottom - screenH * TICKER_HEIGHT, screen.right, screen.bottom)
    val banner = Rect(screen.left, ticker.top - screenH * BANNER_HEIGHT, screen.right, ticker.top)
    // Every inset fraction is relative to the screen, so this clears the banner at any size.
    val insetW = screenW * INSET_WIDTH
    val insetH = insetW / INSET_ASPECT
    val insetRight = screen.right - screenW * INSET_RIGHT
    val insetTop = screen.top + screenH * INSET_TOP
    val inset = Rect(insetRight - insetW, insetTop, insetRight, insetTop + insetH)

    return TvLayout(ears, cabinet, screen, controls, inset, banner, ticker)
}

/** What the story box covers once its frame and caption tab are drawn, not just the face. */
internal fun TvLayout.insetFrame(): Rect {
    val border = screen.height * INSET_BORDER
    return Rect(
        inset.left - border, inset.top - border,
        inset.right + border, inset.bottom + border + inset.height * INSET_TAB,
    )
}

/**
 * The part of [window] the set may use. A stage that is missing, not finite, or leaves nothing to
 * draw into once clipped to the window is no constraint at all, so the whole window is returned.
 */
private fun usableStage(stage: Rect?, window: Rect): Rect {
    if (stage == null) return window
    if (!stage.left.isFinite() || !stage.top.isFinite() ||
        !stage.right.isFinite() || !stage.bottom.isFinite()
    ) {
        return window
    }
    val clipped = Rect(
        max(stage.left, window.left), max(stage.top, window.top),
        min(stage.right, window.right), min(stage.bottom, window.bottom),
    )
    return if (clipped.width > MIN_STAGE_PX && clipped.height > MIN_STAGE_PX) clipped else window
}

/** Under this a stage has nothing usable in it, whatever the numbers say. */
private const val MIN_STAGE_PX = 1f

/** Corner radius of the glass, as a share of the screen height. */
private const val GLASS_CORNER = 0.07f

private val WalnutLight = Color(0xFF7A5130)
private val WalnutDark = Color(0xFF32200F)
private val BezelDark = Color(0xFF1E1206)
private val Chrome = Color(0xFFB9B2A6)
private val ChromeDark = Color(0xFF4A443C)

/** The rounded 4:3 glass, so the picture can be clipped to exactly what the tube shows. */
internal fun TvLayout.glassPath(): Path = Path().apply {
    addRoundRect(RoundRect(screen, CornerRadius(screen.height * GLASS_CORNER)))
}

/**
 * Everything about the set that depends only on the layout: paths, brushes and the grain and
 * grille tables. Built once per layout so no frame allocates them. Both glows live here because
 * a kick can flip colour and mono at 3 Hz, and a rebuild per flip would redo the scanline path.
 */
internal class TvSetArt(val layout: TvLayout, window: Size, glowColour: Color, glowMono: Color) {
    private val ch = layout.cabinet.height
    private val cabinet = layout.cabinet
    private val screen = layout.screen

    val glass: Path = layout.glassPath()
    val bezelRect: Rect = Rect(
        screen.left - ch * 0.03f, screen.top - ch * 0.03f,
        screen.right + ch * 0.03f, screen.bottom + ch * 0.03f,
    )
    val bezelRadius = CornerRadius(screen.height * GLASS_CORNER + ch * 0.03f)
    val cabinetRadius = CornerRadius(ch * 0.07f)
    val glassRadius = CornerRadius(screen.height * GLASS_CORNER)

    private val glowRadius = (maxOf(window.width, window.height) * 0.78f).coerceAtLeast(1f)
    private val glowColourBrush: Brush =
        Brush.radialGradient(listOf(glowColour, Color.Transparent), screen.center, glowRadius)
    private val glowMonoBrush: Brush =
        Brush.radialGradient(listOf(glowMono, Color.Transparent), screen.center, glowRadius)
    fun glow(mono: Boolean): Brush = if (mono) glowMonoBrush else glowColourBrush

    val bezelStroke = Stroke(width = ch * 0.012f)
    val cabinetBrush: Brush =
        Brush.verticalGradient(listOf(WalnutLight, WalnutDark), cabinet.top, cabinet.bottom)
    val bezelBrush: Brush =
        Brush.verticalGradient(listOf(BezelDark, Color(0xFF120A04)), bezelRect.top, bezelRect.bottom)
    val shadowBrush: Brush = Brush.radialGradient(
        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
        center = Offset(cabinet.center.x, cabinet.bottom + ch * 0.05f),
        radius = (cabinet.width * 0.55f).coerceAtLeast(1f),
    )
    // Held off the middle of the tube so the lower third stays a bright bar, not a grey one.
    val vignette: Brush = Brush.radialGradient(
        0f to Color.Transparent,
        0.55f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.42f),
        center = screen.center,
        radius = (maxOf(screen.width, screen.height) * 0.85f).coerceAtLeast(1f),
    )

    val feet: List<Rect> = run {
        val fw = ch * 0.11f
        val fh = ch * 0.07f
        listOf(cabinet.left + cabinet.width * 0.13f, cabinet.right - cabinet.width * 0.13f - fw)
            .map { Rect(it, cabinet.bottom - fh * 0.3f, it + fw, cabinet.bottom - fh * 0.3f + fh) }
    }
    val footRadius = CornerRadius(ch * 0.07f * 0.35f)

    /** x, stroke width and a pale flag per grain column, hashed so the wood never shimmers. */
    val grainX = FloatArray(GRAIN_COLUMNS)
    val grainWidth = FloatArray(GRAIN_COLUMNS)
    val grainPale = BooleanArray(GRAIN_COLUMNS)

    val dialDiameter: Float = minOf(layout.controls.width * 0.74f, layout.controls.height * 0.19f)
    val dialTop: Offset = Offset(layout.controls.center.x, layout.controls.top + dialDiameter * 0.85f)
    val dialBottom: Offset = Offset(dialTop.x, dialTop.y + dialDiameter * 1.45f)
    val dialTopBrush: Brush = dialBrush(dialTop, dialDiameter / 2f)
    val dialBottomBrush: Brush = dialBrush(dialBottom, dialDiameter / 2f)
    val grille: List<Rect>
    val grillePitch: Float
    val lamp: Offset = Offset(layout.controls.center.x, layout.controls.bottom - dialDiameter * 0.28f)
    val lampRadius: Float = dialDiameter * 0.13f
    val lampGlow: Brush = Brush.radialGradient(
        listOf(Color(0xFFFFD08A).copy(alpha = 0.55f), Color.Transparent),
        center = lamp, radius = (lampRadius * 4f).coerceAtLeast(1f),
    )

    val earsPivot: Offset
    val domeRect: Rect
    val domeBrush: Brush
    val rodReach: Float

    /** One path for all 240 scanlines: 240 draw calls a frame was the whole cost of the glass. */
    val scanlines: Path = Path()
    val sheen: Path = Path()

    init {
        for (i in 0 until GRAIN_COLUMNS) {
            val jitter = grainHash(i)
            grainX[i] = cabinet.left + cabinet.width * ((i + 0.15f + jitter * 0.7f) / GRAIN_COLUMNS)
            grainWidth[i] = ch * (0.004f + jitter * 0.006f)
            grainPale[i] = jitter > 0.6f
        }

        val col = layout.controls
        val grilleTop = dialTop.y + dialDiameter * 2.1f
        val grilleBottom = col.bottom - dialDiameter * 0.55f
        grillePitch = if (grilleBottom > grilleTop) (grilleBottom - grilleTop) / GRILLE_SLOTS else 0f
        grille = if (grillePitch > 0f) {
            (0 until GRILLE_SLOTS).map { i ->
                val y = grilleTop + i * grillePitch
                Rect(col.left + col.width * 0.14f, y, col.right - col.width * 0.14f, y + grillePitch * 0.5f)
            }
        } else {
            emptyList()
        }

        val base = Offset(cabinet.center.x, cabinet.top)
        val domeW = ch * 0.20f
        val domeH = ch * 0.085f
        domeRect = Rect(base.x - domeW / 2f, base.y - domeH, base.x + domeW / 2f, base.y + domeH)
        domeBrush = Brush.verticalGradient(
            listOf(Color(0xFF55504A), Color(0xFF221F1C)), base.y - domeH, base.y,
        )
        earsPivot = Offset(base.x, base.y - domeH * 0.85f)
        rodReach = (layout.ears.height - domeH).coerceAtLeast(0f)

        if (screen.height > 1f) {
            val pitch = screen.height / SCANLINES
            for (i in 0 until SCANLINES) {
                val y = screen.top + i * pitch
                scanlines.addRect(Rect(screen.left, y, screen.right, y + pitch * 0.5f))
            }
            sheen.moveTo(screen.left, screen.top + screen.height * 0.62f)
            sheen.lineTo(screen.left + screen.width * 0.52f, screen.top)
            sheen.lineTo(screen.left + screen.width * 0.80f, screen.top)
            sheen.lineTo(screen.left, screen.top + screen.height * 0.94f)
            sheen.close()
        }
    }

    val sheenBrush: Brush = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.055f), Color.Transparent),
        start = Offset(screen.left, screen.top),
        end = Offset(screen.left + screen.width * 0.7f, screen.bottom),
    )

    private companion object {
        const val GRAIN_COLUMNS = 54
        const val GRILLE_SLOTS = 9
        const val SCANLINES = 240
    }
}

private fun dialBrush(center: Offset, r: Float): Brush = Brush.linearGradient(
    listOf(Chrome, Color(0xFF6A6259)),
    start = Offset(center.x - r, center.y - r),
    end = Offset(center.x + r, center.y + r),
)

@Composable
internal fun rememberTvSetArt(layout: TvLayout, window: Size, glow: Color, glowMono: Color): TvSetArt =
    remember(layout, window, glow, glowMono) { TvSetArt(layout, window, glow, glowMono) }

/**
 * Everything around the picture: room glow, cabinet, controls and antenna. The glass overlay is
 * [drawGlassOverlay], which has to run after the picture instead of here.
 */
internal fun DrawScope.drawTvSet(art: TvSetArt, mono: Boolean, level: Float, time: Float) {
    val ch = art.layout.cabinet.height
    if (ch <= 1f) return
    val lit = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    val clock = if (time.isFinite()) time else 0f

    // The only thing painted over the whole window; the host's void has to stay visible.
    drawRect(art.glow(mono), alpha = 0.10f + 0.12f * lit)

    val c = art.layout.cabinet
    drawOval(
        brush = art.shadowBrush,
        topLeft = Offset(c.left - ch * 0.08f, c.bottom - ch * 0.02f),
        size = Size(c.width + ch * 0.16f, ch * 0.16f),
    )
    for (i in art.feet.indices) {
        val foot = art.feet[i]
        drawRoundRect(WalnutDark, foot.topLeft, foot.size, art.footRadius)
    }
    drawCabinet(art, ch)
    drawControls(art, ch, lit)
    drawRabbitEars(art, ch, lit, clock)
}

private fun DrawScope.drawCabinet(art: TvSetArt, ch: Float) {
    val c = art.layout.cabinet
    drawRoundRect(art.cabinetBrush, c.topLeft, c.size, art.cabinetRadius)

    for (i in art.grainX.indices) {
        drawLine(
            color = if (art.grainPale[i]) Color.White.copy(alpha = 0.035f) else Color.Black.copy(alpha = 0.055f),
            start = Offset(art.grainX[i], c.top + ch * 0.03f),
            end = Offset(art.grainX[i], c.bottom - ch * 0.03f),
            strokeWidth = art.grainWidth[i],
        )
    }

    drawLine(
        color = Color.White.copy(alpha = 0.16f),
        start = Offset(c.left + ch * 0.08f, c.top + ch * 0.012f),
        end = Offset(c.right - ch * 0.08f, c.top + ch * 0.012f),
        strokeWidth = ch * 0.008f,
    )

    drawRoundRect(art.bezelBrush, art.bezelRect.topLeft, art.bezelRect.size, art.bezelRadius)
    val s = art.layout.screen
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.8f),
        topLeft = s.topLeft, size = s.size, cornerRadius = art.glassRadius,
        style = art.bezelStroke,
    )
}

private fun DrawScope.drawControls(art: TvSetArt, ch: Float, level: Float) {
    val col = art.layout.controls
    if (col.width <= 1f) return
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = col.topLeft, size = col.size, cornerRadius = CornerRadius(ch * 0.03f),
    )

    // The top dial is the channel selector; it drifts with the level so the set looks alive.
    drawDial(art.dialTop, art.dialDiameter, art.dialTopBrush, -2.2f + level * 1.2f)
    drawDial(art.dialBottom, art.dialDiameter, art.dialBottomBrush, 0.9f)

    for (i in art.grille.indices) {
        val slot = art.grille[i]
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.62f),
            topLeft = slot.topLeft, size = slot.size,
            cornerRadius = CornerRadius(slot.height * 0.5f),
        )
        val y = slot.bottom + art.grillePitch * 0.08f
        drawLine(
            color = Color.White.copy(alpha = 0.07f),
            start = Offset(slot.left, y), end = Offset(slot.right, y),
            strokeWidth = art.grillePitch * 0.1f,
        )
    }

    drawCircle(art.lampGlow, art.lampRadius * 4f, art.lamp)
    drawCircle(Color(0xFFFFC46B), art.lampRadius, art.lamp)
}

private fun DrawScope.drawDial(center: Offset, diameter: Float, face: Brush, angle: Float) {
    val r = diameter / 2f
    if (r <= 0.5f) return
    drawCircle(ChromeDark, r, center)
    drawCircle(brush = face, radius = r * 0.84f, center = center)
    // Ticks read as a channel ring without needing numbers at this size.
    for (i in 0 until 12) {
        val a = i * (PI.toFloat() * 2f / 12f)
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f),
            radius = r * 0.05f,
            center = Offset(center.x + cos(a) * r * 0.92f, center.y + sin(a) * r * 0.92f),
        )
    }
    drawLine(
        color = Color(0xFF1A1611),
        start = center,
        end = Offset(center.x + cos(angle) * r * 0.72f, center.y + sin(angle) * r * 0.72f),
        strokeWidth = r * 0.16f,
    )
    drawCircle(Color(0xFF2B2620), r * 0.14f, center)
}

private fun DrawScope.drawRabbitEars(art: TvSetArt, ch: Float, level: Float, time: Float) {
    drawArc(
        brush = art.domeBrush,
        startAngle = 180f, sweepAngle = 180f, useCenter = true,
        topLeft = art.domeRect.topLeft, size = art.domeRect.size,
    )
    // Slow, small sway: this is motion, never a flash.
    val sway = 2f + 10f * level
    drawRod(art.earsPivot, -ANTENNA_SPREAD, art.rodReach * 0.98f, ch, sway * sin(time * 0.55f))
    drawRod(art.earsPivot, ANTENNA_SPREAD, art.rodReach * 0.82f, ch, sway * sin(time * 0.41f + 1.7f))
}

/** One telescoping rod: three segments of falling thickness, ending in a ball. */
private fun DrawScope.drawRod(pivot: Offset, angle: Float, length: Float, ch: Float, drift: Float) {
    val dx = sin(angle)
    val dy = -cos(angle)
    var from = pivot
    for (segment in 0 until 3) {
        val t1 = (segment + 1) / 3f
        val bend = drift * t1 * t1
        val to = Offset(pivot.x + dx * length * t1 + bend, pivot.y + dy * length * t1)
        drawLine(
            color = if (segment == 0) Chrome else Chrome.copy(alpha = 0.92f - segment * 0.06f),
            start = from, end = to,
            strokeWidth = ch * (0.022f - segment * 0.005f),
            cap = StrokeCap.Round,
        )
        from = to
    }
    drawCircle(Chrome, ch * 0.021f, from)
    drawCircle(Color.White.copy(alpha = 0.35f), ch * 0.008f, Offset(from.x - ch * 0.006f, from.y - ch * 0.006f))
}

/**
 * Scanlines, vignette and one reflection, drawn after the picture so they sit on the glass
 * rather than under it.
 */
internal fun DrawScope.drawGlassOverlay(art: TvSetArt, crt: Float) {
    val s = art.layout.screen
    if (s.height <= 1f) return
    val strength = if (crt.isFinite()) crt.coerceIn(0f, 1f) else 0f

    clipPath(art.glass) {
        // One path rather than 240 strokes, so the lines no longer overlap through their
        // antialiased edges; the alpha is lower to keep the old softness on a bright banner.
        if (strength > 0.01f) drawPath(art.scanlines, Color.Black, alpha = 0.18f * strength)
        drawRect(art.vignette, topLeft = s.topLeft, size = s.size)
        // A single soft diagonal, the one thing that says there is glass in front of the tube.
        drawPath(art.sheen, art.sheenBrush)
    }
}

/** Splay of each antenna rod from vertical. */
private const val ANTENNA_SPREAD = 0.52f   // radians, about 30 degrees

/** Stable 0..1 per grain column, so the wood does not shimmer frame to frame. */
private fun grainHash(i: Int): Float {
    var h = i * 374761393 + 668265263
    h = (h xor (h shr 13)) * 1274126177
    return ((h xor (h shr 16)) and 0xFFFF) / 65535f
}
