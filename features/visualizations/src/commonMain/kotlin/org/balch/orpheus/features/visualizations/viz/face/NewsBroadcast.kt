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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
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
    val standByFill: Color, val standByText: Color,
)

internal val ColourBroadcast = BroadcastPalette(
    backdropTop = Color(0xFF1B3E86), backdropBottom = Color(0xFF05122E), grid = Color(0xFF6FA3E8),
    desk = Color(0xFF1C3160), deskEdge = Color(0xFF8FB2E6), chair = Color(0xFF101D38),
    insetBorder = Color(0xFFE6EDF8), bannerFill = Color(0xFFC4181A), bannerText = Color(0xFFFFFFFF),
    bannerTag = Color(0xFF7C0C0E), tickerFill = Color(0xFF071634), tickerText = Color(0xFFFFC44D),
    bug = Color(0xFFE23B2E),
    standByFill = Color(0xFF040A1A), standByText = Color(0xFFF2E9C8),
)

/** Flatter and harsher: a switched tube, not a desaturated one. */
internal val MonoBroadcast = BroadcastPalette(
    backdropTop = Color(0xFF6A6A6A), backdropBottom = Color(0xFF131313), grid = Color(0xFFB4B4B4),
    desk = Color(0xFF2C2C2C), deskEdge = Color(0xFFCFCFCF), chair = Color(0xFF1D1D1D),
    insetBorder = Color(0xFFF4F4F4), bannerFill = Color(0xFFF4F4F4), bannerText = Color(0xFF080808),
    bannerTag = Color(0xFFB4B4B4), tickerFill = Color(0xFF0C0C0C), tickerText = Color(0xFFEDEDED),
    bug = Color(0xFFDCDCDC),
    // Never drawn: the card is a colour-broadcast element. Here so the palette stays total.
    standByFill = Color(0xFF0C0C0C), standByText = Color(0xFFEDEDED),
)

/** Screen-widths per second the ticker travels. */
private const val TICKER_SPEED = 0.18f
private const val TICKER_SEPARATOR = "   •   "

/** Share of the stand-by card's height a title line and the closing line may each take. */
private const val STAND_BY_TITLE_SHARE = 0.26f
private const val STAND_BY_SUB_SHARE = 0.17f

/** How crooked the card sits: a turn in degrees, then a horizontal shear. */
private const val STAND_BY_TURN_DEG = -3.5f
private const val STAND_BY_SHEAR = -0.10f

/** Station bug, as shares of the tube: its centre across the width, its radius of the height. */
private const val BUG_CENTRE_X = 0.055f
private const val BUG_RADIUS = 0.032f

/** Where the two panel grooves sit across the desk front. */
private val DESK_GROOVES = floatArrayOf(0.32f, 0.68f)

/** Below this the tube is too small to draw anything into. */
private const val MIN_SCREEN_PX = 8f

/** One frame of the whole broadcast: the story graphic plus what the set itself needs. */
internal class BroadcastFrame(
    val face: FaceMorphInputs,
    val level: Float,
    val crt: Float,
    val glitch: Float,
    val time: Float,
    /** Opacity of the "technical difficulties" card; see [standByAlpha]. */
    val standBy: Float = 1f,
) {
    val mono: Boolean get() = face.mono > 0.5f
}

/**
 * The whole visualization: an old set showing a news broadcast, with the morphing face as the
 * over-the-shoulder story graphic. The glitch tears the whole tube, so the face itself is handed
 * a glitch of zero.
 *
 * [frame] is only ever called from draw and layer blocks. A new frame therefore redraws, and
 * nothing recomposes or re-lays out; composition runs when the layout or the stage moves.
 */
@Composable
internal fun NewsBroadcastScene(modifier: Modifier, frame: () -> BroadcastFrame) {
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

        val setArt = rememberTvSetArt(
            layout, window, ColourBroadcast.backdropTop, MonoBroadcast.backdropTop,
        )
        // Both palettes up front: a kick flips them at up to 3 Hz, and a flip is a pick at draw
        // time rather than a rebuild of every path and brush.
        val colourArt = remember(layout) { PictureArt(layout, ColourBroadcast) }
        val monoArt = remember(layout) { PictureArt(layout, MonoBroadcast) }
        val measurer = rememberTextMeasurer()
        val text = remember(layout, density, measurer) { measureBroadcastText(layout, measurer, density) }
        val insetBox = remember(layout, density) {
            with(density) {
                Modifier
                    .offset { IntOffset(layout.inset.left.roundToInt(), layout.inset.top.roundToInt()) }
                    .size(layout.inset.width.toDp(), layout.inset.height.toDp())
                    .clip(RoundedCornerShape((layout.inset.height * 0.06f).toDp()))
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val f = frame()
            drawTvSet(setArt, f.mono, f.level, f.time)
        }
        // One layer for everything the tube is showing, so a hit tears the backdrop, the banner,
        // the ticker and the story box as a single surface.
        Box(
            Modifier.fillMaxSize().tvPictureEffect(
                layout.screen, glitch = { frame().glitch }, mono = { frame().face.mono }, time = { frame().time },
            ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val f = frame()
                val art = if (f.mono) monoArt else colourArt
                clipPath(setArt.glass) { drawPicture(art, text, f.mono, f.time, f.standBy) }
            }
            // The shader draws the face itself, so it has to be a child rather than a draw call.
            Box(insetBox) { FaceMorphCanvas(Modifier.fillMaxSize()) { frame().face } }
            Canvas(Modifier.fillMaxSize()) {
                val mono = frame().mono
                clipPath(setArt.glass) { drawInsetFrame(if (mono) monoArt else colourArt, text, mono) }
            }
        }
        // In front of the tube, so the scanlines and the reflection stay still while it tears.
        Canvas(Modifier.fillMaxSize()) { drawGlassOverlay(setArt, frame().crt) }
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
    val standBy: List<FittedText>,
) {
    /** Summed once here so the card's draw has nothing to add up or allocate. */
    val standByHeight: Int = standBy.sumOf { it.colour.size.height }

    fun all(): List<FittedText> = listOf(banner, tag, bug, caption, ticker) + standBy
}

/** Width of the tag block on the left of the lower third. */
internal fun TvLayout.bannerTagWidth(): Float = banner.height * 1.15f

/**
 * The stand-by card before its tilt: top edge level with the story box's frame, so the two
 * graphics read as one row and the seat nobody is in stays in plain view underneath. It sits
 * between the station bug's label and the story box, and shrinks in proportion on a narrow tube
 * rather than cover either.
 */
internal fun TvLayout.standByCard(): Rect {
    val s = screen
    val lo = s.left + s.width * BUG_CENTRE_X + s.height * (BUG_RADIUS * 1.6f + 0.14f)
    val hi = inset.left - s.height * INSET_BORDER - s.width * 0.03f
    val wanted = s.width * 0.37f
    val left = maxOf(lo, minOf(s.left + s.width * 0.184f, hi - wanted))
    val width = minOf(wanted, hi - left).coerceAtLeast(0f)
    val top = inset.top - s.height * INSET_BORDER
    return Rect(left, top, left + width, top + s.height * 0.24f * (width / wanted))
}

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
    val card = layout.standByCard()

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
        // One wording for both modes: mono never draws the card.
        standBy = NewsCopy.standBy.mapIndexed { i, line ->
            val share = if (i == NewsCopy.standBy.lastIndex) STAND_BY_SUB_SHARE else STAND_BY_TITLE_SHARE
            measurer.fitted(
                line, line, card.height * share * 0.8f,
                maxWidth = card.width * 0.88f, maxHeight = card.height * share, density = density,
            )
        },
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
    val hairStroke = Stroke(hair)
    val trimStroke = Stroke(hair * 1.4f)
    val rimStroke = Stroke(hair * 1.6f)
    val bugRadius: Float = screen.height * BUG_RADIUS
    val bugCentre = Offset(screen.left + screen.width * BUG_CENTRE_X, screen.top + screen.height * 0.075f)
    val backdrop: Brush =
        Brush.verticalGradient(listOf(palette.backdropTop, palette.backdropBottom), screen.top, screen.bottom)

    val globeCentre = Offset(screen.left + screen.width * 0.34f, screen.top + screen.height * 0.40f)
    val globeRadius = screen.height * 0.46f
    val gridColour = palette.grid.copy(alpha = 0.16f)

    // Latitude and longitude arcs, not a real map: the shape a news studio puts behind the desk.
    // One path, so the frame strokes it once instead of laying out fourteen arcs.
    val grid: Path = Path().apply {
        val r = globeRadius
        val c = globeCentre
        for (i in 1..4) {
            val f = i / 5f
            val h = r * cos(f * (PI.toFloat() / 2f))
            val y = r * sin(f * (PI.toFloat() / 2f))
            addOval(Rect(c.x - h, c.y - y - h * 0.10f, c.x + h, c.y - y + h * 0.10f))
            addOval(Rect(c.x - h, c.y + y - h * 0.10f, c.x + h, c.y + y + h * 0.10f))
        }
        for (i in 0..4) {
            val w = r * abs((i / 4f) * 2f - 1f)
            if (w > 0f) addOval(Rect(c.x - w, c.y - r, c.x + w, c.y + r))
        }
        moveTo(c.x, c.y - r)
        lineTo(c.x, c.y + r)
    }

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

    val standByCard: Rect = layout.standByCard()

    // A slight turn and shear about the card's centre: a slide dropped in crooked, in a hurry.
    val standByTilt: Matrix = Matrix().apply {
        val turn = STAND_BY_TURN_DEG * (PI.toFloat() / 180f)
        val m00 = cos(turn)
        val m10 = sin(turn)
        val m01 = m00 * STAND_BY_SHEAR - m10
        val m11 = m10 * STAND_BY_SHEAR + m00
        val c = standByCard.center
        values[Matrix.ScaleX] = m00
        values[Matrix.SkewY] = m10
        values[Matrix.SkewX] = m01
        values[Matrix.ScaleY] = m11
        values[Matrix.TranslateX] = c.x - (m00 * c.x + m01 * c.y)
        values[Matrix.TranslateY] = c.y - (m10 * c.x + m11 * c.y)
    }
    val standByRadius = CornerRadius(screen.height * 0.012f)

    val insetBorder: Float = screen.height * INSET_BORDER
    val insetStroke = Stroke(insetBorder * 2f)
    val insetTab: Float = layout.inset.height * INSET_TAB
    val insetRadius = CornerRadius(layout.inset.height * 0.06f + insetBorder)
}

private fun DrawScope.drawPicture(
    art: PictureArt,
    text: BroadcastText,
    mono: Boolean,
    time: Float,
    standBy: Float,
) {
    val s = art.screen
    drawRect(art.backdrop, topLeft = s.topLeft, size = s.size)
    drawStudioGrid(art)
    drawDesk(art)
    if (!mono) drawStandBy(art, text, standBy)
    drawStationBug(art, text, mono)
    drawLowerThird(art, text, mono)
    drawTicker(art, text, mono, time)
}

private fun DrawScope.drawStudioGrid(art: PictureArt) {
    drawCircle(art.gridColour, art.globeRadius, art.globeCentre, style = art.rimStroke)
    drawPath(art.grid, art.gridColour, style = art.hairStroke)
}

/** An anchor desk with nobody behind it. */
private fun DrawScope.drawDesk(art: PictureArt) {
    val p = art.palette
    val hair = art.hair

    // The chair goes first so the desk overlaps its foot; the empty back is the whole point.
    drawPath(art.chair, p.chair)
    drawPath(art.chair, p.deskEdge.copy(alpha = 0.45f), style = art.trimStroke)
    // The seam under the headrest is what makes it read as a chair and not a slab.
    drawLine(
        p.deskEdge.copy(alpha = 0.30f),
        Offset(art.chairSeamLeft, art.chairSeamY), Offset(art.chairSeamRight, art.chairSeamY), hair,
    )

    drawPath(art.deskTop, art.deskTopBrush)
    drawPath(art.deskTop, p.deskEdge.copy(alpha = 0.5f), style = art.hairStroke)

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
    for (f in DESK_GROOVES) {
        val x = art.deskLeft + (art.deskRight - art.deskLeft) * f
        drawLine(
            p.deskEdge.copy(alpha = 0.20f),
            Offset(x, art.deskNear + art.screen.height * 0.018f), Offset(x, art.deskBottom), hair,
        )
    }
}

/** The station's apology for the empty chair. Inside the torn layer, so a hit breaks it up too. */
private fun DrawScope.drawStandBy(art: PictureArt, text: BroadcastText, alpha: Float) {
    val a = if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else 0f
    if (a < MorphDirector.VISIBLE_EPSILON || art.standByCard.width < 4f) return
    withTransform({ transform(art.standByTilt) }) { drawStandByCard(art, text, a) }
}

private fun DrawScope.drawStandByCard(art: PictureArt, text: BroadcastText, a: Float) {
    val card = art.standByCard
    val p = art.palette
    drawRoundRect(p.standByFill, card.topLeft, card.size, art.standByRadius, alpha = a * 0.82f)
    drawRoundRect(
        p.standByText, card.topLeft, card.size, art.standByRadius,
        style = art.trimStroke, alpha = a * 0.7f,
    )

    val lines = text.standBy
    val gap = card.height * 0.035f
    var y = card.top + (card.height - text.standByHeight - gap * (lines.size - 1)) / 2f
    for (i in lines.indices) {
        val line = lines[i].colour
        drawText(line, color = p.standByText, topLeft = Offset(card.center.x - line.size.width / 2f, y), alpha = a)
        y += line.size.height + gap
    }
}

private fun DrawScope.drawStationBug(art: PictureArt, text: BroadcastText, mono: Boolean) {
    val c = art.bugCentre
    val r = art.bugRadius
    drawCircle(art.palette.bug, r, c)
    drawCircle(art.palette.insetBorder.copy(alpha = 0.7f), r, c, style = art.hairStroke)
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
        style = art.insetStroke,
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
