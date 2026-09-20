package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import org.balch.orpheus.ui.viz.LocalVizStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Every colour the picture is allowed to use, so mono has no stray colour anywhere. */
internal data class BroadcastPalette(
    val backdropTop: Color, val backdropBottom: Color, val grid: Color,
    val desk: Color, val deskEdge: Color, val chair: Color,
    val insetBorder: Color, val bannerFill: Color, val bannerText: Color,
    val bannerTag: Color, val tickerFill: Color, val tickerText: Color, val bug: Color,
)

internal val ColourBroadcast = BroadcastPalette(
    backdropTop = Color(0xFF1B3E86), backdropBottom = Color(0xFF05122E), grid = Color(0xFF6FA3E8),
    desk = Color(0xFF1C3160), deskEdge = Color(0xFF8FB2E6), chair = Color(0xFF101D38),
    insetBorder = Color(0xFFE6EDF8), bannerFill = Color(0xFFC4181A), bannerText = Color(0xFFFFFFFF),
    bannerTag = Color(0xFF7C0C0E), tickerFill = Color(0xFF071634), tickerText = Color(0xFFFFC44D),
    bug = Color(0xFFE23B2E),
)

/** Flatter and harsher: a switched tube, not a desaturated one. */
internal val MonoBroadcast = BroadcastPalette(
    backdropTop = Color(0xFF6A6A6A), backdropBottom = Color(0xFF131313), grid = Color(0xFFB4B4B4),
    desk = Color(0xFF2C2C2C), deskEdge = Color(0xFFCFCFCF), chair = Color(0xFF1D1D1D),
    insetBorder = Color(0xFFF4F4F4), bannerFill = Color(0xFFF4F4F4), bannerText = Color(0xFF080808),
    bannerTag = Color(0xFFB4B4B4), tickerFill = Color(0xFF0C0C0C), tickerText = Color(0xFFEDEDED),
    bug = Color(0xFFDCDCDC),
)

/** Screen-widths per second the ticker travels. */
private const val TICKER_SPEED = 0.18f
private const val TICKER_SEPARATOR = "   •   "

/** Below this the tube is too small to draw anything into. */
private const val MIN_SCREEN_PX = 8f

/**
 * The whole visualization: an old set showing a news broadcast, with the morphing face as the
 * over-the-shoulder story graphic. [face] already carries the colour/mono state. [glitch] tears
 * the whole tube, so the face itself is handed a glitch of zero.
 */
@Composable
internal fun NewsBroadcastScene(
    modifier: Modifier,
    face: FaceMorphInputs,
    level: Float,
    crt: Float,
    time: Float,
    glitch: Float = 0f,
) {
    // Normally a full-window layer at the origin, but the stage is in root coordinates, so the
    // offset is subtracted rather than assumed to be zero.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val stage = LocalVizStage.current

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val density = LocalDensity.current
        val window = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        // Read inside the content lambda: a stage move then re-lays out the picture and nothing
        // above it.
        val stageLocal = stage?.bounds?.translate(-origin.x, -origin.y)
        val layout = remember(window, stageLocal) { tvLayout(window, stageLocal) }
        if (layout.screen.height < MIN_SCREEN_PX) return@BoxWithConstraints

        val mono = face.mono > 0.5f
        val palette = if (mono) MonoBroadcast else ColourBroadcast
        val setArt = rememberTvSetArt(layout, window, palette.backdropTop)
        val picture = remember(layout, palette) { PictureArt(layout, palette) }
        val measurer = rememberTextMeasurer()
        // Both wordings are measured together so a kick's colour flip is a pick, not a re-measure.
        val text = remember(layout, density, measurer) { measureBroadcastText(layout, measurer, density) }

        Canvas(Modifier.fillMaxSize()) { drawTvSet(setArt, level, time) }
        // One layer for everything the tube is showing, so a hit tears the backdrop, the banner,
        // the ticker and the story box as a single surface.
        Box(Modifier.fillMaxSize().tvPictureEffect(layout.screen, glitch, face.mono, time)) {
            Canvas(Modifier.fillMaxSize()) {
                clipPath(setArt.glass) { drawPicture(picture, text, mono, time) }
            }
            // The shader draws the face itself, so it has to be a child rather than a draw call.
            Box(
                Modifier
                    .offset { IntOffset(layout.inset.left.roundToInt(), layout.inset.top.roundToInt()) }
                    .size(
                        with(density) { layout.inset.width.toDp() },
                        with(density) { layout.inset.height.toDp() },
                    )
                    .clip(RoundedCornerShape(with(density) { (layout.inset.height * 0.06f).toDp() })),
            ) {
                FaceMorphCanvas(Modifier.fillMaxSize(), face)
            }
            Canvas(Modifier.fillMaxSize()) {
                clipPath(setArt.glass) { drawInsetFrame(picture, text, mono) }
            }
        }
        // In front of the tube, so the scanlines and the reflection stay still while it tears.
        Canvas(Modifier.fillMaxSize()) { drawGlassOverlay(setArt, crt) }
    }
}

/**
 * One wording in each mode, already shrunk to fit. Measured with a placeholder colour: the
 * palette colour is applied at draw time, so a flip never costs a measurement.
 */
internal class FittedText(
    val colour: TextLayoutResult,
    val mono: TextLayoutResult,
    val maxWidth: Float,
    val maxHeight: Float,
) {
    fun pick(isMono: Boolean): TextLayoutResult = if (isMono) mono else colour
}

internal class BroadcastText(
    val banner: FittedText,
    val tag: FittedText,
    val bug: FittedText,
    val caption: FittedText,
    val ticker: FittedText,
) {
    fun all(): List<FittedText> = listOf(banner, tag, bug, caption, ticker)
}

/** Width of the tag block on the left of the lower third. */
internal fun TvLayout.bannerTagWidth(): Float = banner.height * 1.15f

/**
 * Measures every label once for a given layout and density. Public to the module so the harness
 * can check the fit at densities the render shots do not cover.
 */
internal fun measureBroadcastText(
    layout: TvLayout,
    measurer: TextMeasurer,
    density: Density,
): BroadcastText {
    val banner = layout.banner
    val screen = layout.screen
    val tagW = layout.bannerTagWidth()

    return BroadcastText(
        banner = measurer.fitted(
            NewsCopy.bannerColour, NewsCopy.bannerMono, banner.height * 0.56f,
            maxWidth = banner.width - tagW - banner.height * 0.9f,
            maxHeight = banner.height * 0.72f, density = density,
        ),
        tag = measurer.fitted(
            "LIVE", "NOW", banner.height * 0.30f,
            maxWidth = tagW * 0.82f, maxHeight = banner.height * 0.60f, density = density,
        ),
        bug = measurer.fitted(
            "CH 5", "CH 5", screen.height * 0.035f,
            maxWidth = screen.width * 0.22f, maxHeight = screen.height * 0.07f, density = density,
        ),
        caption = measurer.fitted(
            NewsCopy.profileColour, NewsCopy.profileMono, layout.inset.height * 0.13f,
            maxWidth = layout.inset.width * 0.88f,
            maxHeight = layout.inset.height * INSET_TAB * 0.80f, density = density,
        ),
        ticker = measurer.fitted(
            tickerRun(NewsCopy.headlines), tickerRun(NewsCopy.commands), layout.ticker.height * 0.62f,
            // The run scrolls, so only its height has to fit.
            maxWidth = Float.POSITIVE_INFINITY,
            maxHeight = layout.ticker.height * 0.86f, density = density,
        ),
    )
}

private fun tickerRun(items: List<String>): String = items.joinToString(TICKER_SEPARATOR) + TICKER_SEPARATOR

/** Shrinks until both dimensions fit; width alone lets a tall glyph run out of its bar. */
private fun TextMeasurer.fitted(
    colourText: String,
    monoText: String,
    startPx: Float,
    maxWidth: Float,
    maxHeight: Float,
    density: Density,
): FittedText {
    var px = startPx.coerceAtLeast(1f)
    var colour = measure(colourText, pictureStyle(px, density), softWrap = false)
    var mono = measure(monoText, pictureStyle(px, density), softWrap = false)
    var guard = 0
    while (guard++ < 30 && listOf(colour, mono).any {
            it.size.width > maxWidth || it.size.height > maxHeight
        }
    ) {
        px *= 0.9f
        colour = measure(colourText, pictureStyle(px, density), softWrap = false)
        mono = measure(monoText, pictureStyle(px, density), softWrap = false)
    }
    return FittedText(colour, mono, maxWidth, maxHeight)
}

/**
 * These are picture elements, not UI text, so they are sized in screen pixels. toSp() divides by
 * density and fontScale and the measurer multiplies both back, which is what pins a label to the
 * tube whatever the display or the user's text size setting.
 */
private fun pictureStyle(px: Float, density: Density) = TextStyle(
    color = Color.White,
    fontSize = with(density) { px.toSp() },
    fontWeight = FontWeight.Black,
    letterSpacing = with(density) { (px * 0.06f).toSp() },
)

/** Paths and brushes for the picture: everything that depends only on layout and palette. */
private class PictureArt(val layout: TvLayout, val palette: BroadcastPalette) {
    val screen: Rect = layout.screen
    val hair: Float = screen.height * 0.004f
    val backdrop: Brush =
        Brush.verticalGradient(listOf(palette.backdropTop, palette.backdropBottom), screen.top, screen.bottom)

    val globeCentre = Offset(screen.left + screen.width * 0.34f, screen.top + screen.height * 0.40f)
    val globeRadius = screen.height * 0.46f
    val gridColour = palette.grid.copy(alpha = 0.16f)

    val deskBottom: Float = layout.banner.top
    val deskNear: Float = deskBottom - screen.height * 0.155f
    val deskFar: Float = deskNear - screen.height * 0.075f
    val deskLeft: Float = screen.left - screen.width * 0.05f
    val deskRight: Float = screen.left + screen.width * 0.56f
    val chairWidth: Float = screen.width * 0.155f
    private val chairCx: Float = deskLeft + (deskRight - deskLeft) * 0.46f
    private val chairBottom: Float = deskFar + screen.height * 0.04f
    val chairTop: Float = chairBottom - screen.height * 0.21f
    val chairSeamY: Float = chairTop + screen.height * 0.062f
    val chairSeamLeft: Float = chairCx - chairWidth * 0.34f
    val chairSeamRight: Float = chairCx + chairWidth * 0.34f

    val chair: Path = Path().apply {
        val r = chairWidth * 0.22f
        moveTo(chairCx - chairWidth * 0.42f, chairBottom)
        lineTo(chairCx - chairWidth * 0.5f, chairTop + r)
        quadraticTo(chairCx - chairWidth * 0.5f, chairTop, chairCx - chairWidth * 0.5f + r, chairTop)
        lineTo(chairCx + chairWidth * 0.5f - r, chairTop)
        quadraticTo(chairCx + chairWidth * 0.5f, chairTop, chairCx + chairWidth * 0.5f, chairTop + r)
        lineTo(chairCx + chairWidth * 0.42f, chairBottom)
        close()
    }

    // Trapezoid top: the far edge is shorter, which is all the perspective this needs.
    val deskTop: Path = Path().apply {
        val inset = screen.width * 0.05f
        moveTo(deskLeft + inset, deskFar)
        lineTo(deskRight - inset, deskFar)
        lineTo(deskRight, deskNear)
        lineTo(deskLeft, deskNear)
        close()
    }

    // Opaque, or the studio grid shows through what is meant to be a solid surface.
    val deskTopBrush: Brush = Brush.linearGradient(
        listOf(lerp(palette.desk, palette.deskEdge, 0.38f), palette.desk),
        start = Offset(deskLeft, deskFar), end = Offset(deskRight, deskNear),
    )
    val deskFrontBrush: Brush =
        Brush.verticalGradient(listOf(palette.desk, palette.chair), deskNear, deskBottom)

    val bugRadius: Float = screen.height * 0.032f
    val bugCentre = Offset(screen.left + screen.width * 0.055f, screen.top + screen.height * 0.075f)
    val insetBorder: Float = screen.height * INSET_BORDER
    val insetTab: Float = layout.inset.height * INSET_TAB
    val insetRadius = CornerRadius(layout.inset.height * 0.06f + insetBorder)
}

private fun DrawScope.drawPicture(art: PictureArt, text: BroadcastText, mono: Boolean, time: Float) {
    val s = art.screen
    drawRect(art.backdrop, topLeft = s.topLeft, size = s.size)
    drawStudioGrid(art)
    drawDesk(art)
    drawStationBug(art, text, mono)
    drawLowerThird(art, text, mono)
    drawTicker(art, text, mono, time)
}

/** Latitude and longitude arcs, not a real map: the shape a news studio puts behind the desk. */
private fun DrawScope.drawStudioGrid(art: PictureArt) {
    val r = art.globeRadius
    val centre = art.globeCentre
    val faint = art.gridColour
    val line = art.hair

    drawCircle(faint, r, centre, style = Stroke(line * 1.6f))
    for (i in 1..4) {
        val f = i / 5f
        val h = r * cos(f * (PI.toFloat() / 2f))
        val y = r * sin(f * (PI.toFloat() / 2f))
        listOf(-y, y).forEach { dy ->
            drawArc(
                color = faint, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(centre.x - h, centre.y + dy - h * 0.10f),
                size = Size(h * 2f, h * 0.20f), style = Stroke(line),
            )
        }
    }
    for (i in 0..4) {
        val f = (i / 4f) * 2f - 1f
        val w = r * abs(f)
        drawArc(
            color = faint, startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(centre.x - w, centre.y - r),
            size = Size(w * 2f, r * 2f), style = Stroke(line),
        )
    }
    drawLine(faint, Offset(centre.x, centre.y - r), Offset(centre.x, centre.y + r), line)
}

/** An anchor desk with nobody behind it. */
private fun DrawScope.drawDesk(art: PictureArt) {
    val p = art.palette
    val hair = art.hair

    // The chair goes first so the desk overlaps its foot; the empty back is the whole point.
    drawPath(art.chair, p.chair)
    drawPath(art.chair, p.deskEdge.copy(alpha = 0.45f), style = Stroke(hair * 1.4f))
    // The seam under the headrest is what makes it read as a chair and not a slab.
    drawLine(
        p.deskEdge.copy(alpha = 0.30f),
        Offset(art.chairSeamLeft, art.chairSeamY), Offset(art.chairSeamRight, art.chairSeamY), hair,
    )

    drawPath(art.deskTop, art.deskTopBrush)
    drawPath(art.deskTop, p.deskEdge.copy(alpha = 0.5f), style = Stroke(hair))

    drawRect(
        brush = art.deskFrontBrush,
        topLeft = Offset(art.deskLeft, art.deskNear),
        size = Size(art.deskRight - art.deskLeft, art.deskBottom - art.deskNear),
    )
    // Bright near edge: the one line that puts the desk in front of everything else.
    drawLine(
        p.deskEdge.copy(alpha = 0.9f),
        Offset(art.deskLeft, art.deskNear), Offset(art.deskRight, art.deskNear), hair * 2f,
    )
    drawLine(
        p.deskEdge.copy(alpha = 0.35f),
        Offset(art.deskRight, art.deskNear), Offset(art.deskRight, art.deskBottom), hair * 1.4f,
    )
    // Two shallow panel grooves on the front, the way a news desk is built.
    listOf(0.32f, 0.68f).forEach { f ->
        val x = art.deskLeft + (art.deskRight - art.deskLeft) * f
        drawLine(
            p.deskEdge.copy(alpha = 0.20f),
            Offset(x, art.deskNear + art.screen.height * 0.018f), Offset(x, art.deskBottom), hair,
        )
    }
}

private fun DrawScope.drawStationBug(art: PictureArt, text: BroadcastText, mono: Boolean) {
    val c = art.bugCentre
    val r = art.bugRadius
    drawCircle(art.palette.bug, r, c)
    drawCircle(art.palette.insetBorder.copy(alpha = 0.7f), r, c, style = Stroke(art.hair))
    val label = text.bug.pick(mono)
    drawText(
        label, color = art.palette.bug,
        topLeft = Offset(c.x + r * 1.6f, c.y - label.size.height / 2f),
    )
}

private fun DrawScope.drawLowerThird(art: PictureArt, text: BroadcastText, mono: Boolean) {
    val b = art.layout.banner
    if (b.height < 2f) return
    val p = art.palette
    drawRect(p.bannerFill, topLeft = b.topLeft, size = b.size)

    val tagW = art.layout.bannerTagWidth()
    drawRect(p.bannerTag, topLeft = b.topLeft, size = Size(tagW, b.height))

    val tag = text.tag.pick(mono)
    drawText(
        tag, color = p.bannerText,
        topLeft = Offset(b.left + (tagW - tag.size.width) / 2f, b.top + (b.height - tag.size.height) / 2f),
    )
    val headline = text.banner.pick(mono)
    drawText(
        headline, color = p.bannerText,
        topLeft = Offset(b.left + tagW + b.height * 0.45f, b.top + (b.height - headline.size.height) / 2f),
    )
}

private fun DrawScope.drawTicker(art: PictureArt, text: BroadcastText, mono: Boolean, time: Float) {
    val t = art.layout.ticker
    if (t.height < 2f) return
    val p = art.palette
    drawRect(p.tickerFill, topLeft = t.topLeft, size = t.size)
    drawLine(
        p.tickerText.copy(alpha = 0.35f),
        Offset(t.left, t.top), Offset(t.right, t.top), t.height * 0.06f,
    )

    val run = text.ticker.pick(mono)
    val runWidth = run.size.width.toFloat()
    if (runWidth <= 1f) return
    val speed = art.screen.width * TICKER_SPEED
    // Modulo the run's own period, so the offset never grows and loses float precision.
    val period = runWidth / speed
    val safeTime = if (time.isFinite()) time else 0f
    val phase = ((safeTime % period) + period) % period
    val y = t.top + (t.height - run.size.height) / 2f
    clipRect(t.left, t.top, t.right, t.bottom) {
        var x = t.left - phase * speed
        while (x < t.right) {
            drawText(run, color = p.tickerText, topLeft = Offset(x, y))
            x += runWidth
        }
    }
}

/** The frame and caption tab that turn the face box into a news graphic. */
private fun DrawScope.drawInsetFrame(art: PictureArt, text: BroadcastText, mono: Boolean) {
    val i = art.layout.inset
    if (i.height < 4f) return
    val border = art.insetBorder
    drawRoundRect(
        color = art.palette.insetBorder,
        topLeft = Offset(i.left - border, i.top - border),
        size = Size(i.width + border * 2f, i.height + border * 2f),
        cornerRadius = art.insetRadius,
        style = Stroke(border * 2f),
    )
    drawRect(
        art.palette.bannerTag,
        topLeft = Offset(i.left, i.bottom + border),
        size = Size(i.width, art.insetTab),
    )
    val caption = text.caption.pick(mono)
    drawText(
        caption, color = art.palette.bannerText,
        topLeft = Offset(
            i.left + (i.width - caption.size.width) / 2f,
            i.bottom + border + (art.insetTab - caption.size.height) / 2f,
        ),
    )
}
