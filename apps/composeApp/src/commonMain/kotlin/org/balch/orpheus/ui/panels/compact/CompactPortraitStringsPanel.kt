package org.balch.orpheus.ui.panels.compact

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.math.abs

/**
 * Compact string panel for the bottom panel navigation in portrait mode.
 *
 * Layout:
 * - Left: Quad 0 pitch and hold controls
 * - Center: 4 strummable vertical strings (each controls a duo of voices)
 * - Right: Quad 1 pitch and hold controls
 *
 * Interactions:
 * - Each string acts as a bender for its 2 voices
 * - Horizontal deflection controls pitch bend (-1 to +1)
 * - Vertical pluck position controls voice mix (top=voice A, center=both, bottom=voice B)
 * - Multi-touch: two fingers can control non-adjacent strings simultaneously
 * - On tap: triggers voice pulse AND small spring-back bend
 * - On release: spring animation returns string to center
 */
@Composable
fun CompactStringPanel(
    voiceState: VoiceUiState,
    actions: VoicePanelActions,
    modifier: Modifier = Modifier,
    liquidState: LiquidState? = LocalLiquidState.current,
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current
) {
    val shape = RoundedCornerShape(12.dp)

    // String colors for the 4 duos
    val stringColors = listOf(
        OrpheusColors.neonMagenta,
        OrpheusColors.electricBlue,
        OrpheusColors.warmGlow,
        OrpheusColors.synthGreen
    )

    val baseModifier = modifier.fillMaxSize()

    val panelModifier = if (liquidState != null) {
        baseModifier
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.softPurple,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(8.dp)
    } else {
        baseModifier.padding(8.dp)
    }

    Row(modifier = panelModifier) {
        // Left controls column
        Column(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .padding(end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Quad 0 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches.getOrElse(0) { 0.5f },
                onValueChange = { actions.setQuadPitch(0, it) },
                label = "PITCH",
                controlId = ControlIds.quadPitch(0),
                size = 40.dp,
                progressColor = OrpheusColors.neonMagenta
            )
            // Quad 0 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds.getOrElse(0) { 0f },
                onValueChange = { actions.setQuadHold(0, it) },
                label = "HOLD",
                controlId = ControlIds.quadHold(0),
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }

        // Center: Strummable strings with slide bar overlay
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Track slide bar position with animation support
            val slideBarAnim = remember { Animatable(0f) }
            val coroutineScope = rememberCoroutineScope()
            
            // PERSISTENT STRING POSITIONS
            val stringCentersState = rememberSaveable(key = "string_centers", saver = Saver(
                save = { it.value },
                restore = { mutableStateOf(it) }
            )) {
                // Default positions: Spread out to prevent overlap (0.2, 0.4, 0.6, 0.8)
                mutableStateOf(listOf(0.20f, 0.40f, 0.60f, 0.80f))
            }
            var stringCenters by stringCentersState
            
            // Strings canvas (full area, behind slide bar)
            BenderStringsCanvas(
                colors = stringColors,
                voiceStates = voiceState.voiceStates,
                stringCenters = stringCenters,
                onStringPositionsChanged = { newPositions -> stringCenters = newPositions },
                slideBarPosition = { slideBarAnim.value },
                onStringStart = { stringIndex, bendAmount, voiceMix ->
                    // First touch - just start bending (no voice triggering)
                    actions.setStringBend(stringIndex, bendAmount, voiceMix)
                },
                onStringBendChange = { stringIndex, bendAmount, voiceMix ->
                    // Continue bending
                    actions.setStringBend(stringIndex, bendAmount, voiceMix)
                },
                onStringBendRelease = { stringIndex ->
                    // Release bend (no voice release)
                    actions.releaseStringBend(stringIndex)
                },
                onStringTap = { stringIndex, voiceMix ->
                    // Tap triggers a small bend that springs back (no voice pulse)
                    actions.setStringBend(stringIndex, 0.3f, voiceMix)
                    actions.releaseStringBend(stringIndex)
                },
                onSlideChange = { yPos, xPos ->
                    // Snap animation to finger position immediately
                    coroutineScope.launch {
                        slideBarAnim.snapTo(yPos)
                    }
                    actions.setSlideBar(yPos, xPos)
                },
                onSlideRelease = {
                    // Animate back to top (0.0) on release
                    coroutineScope.launch {
                        slideBarAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.6f,
                                stiffness = 300f
                            )
                        )
                        // Ensure engine knows we returned to 0
                        actions.setSlideBar(0f, 0.5f)
                        actions.releaseSlideBar()
                    }
                }
            )
        }


        // Right controls column
        Column(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .padding(start = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Quad 1 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches.getOrElse(1) { 0.5f },
                onValueChange = { actions.setQuadPitch(1, it) },
                label = "PITCH",
                controlId = ControlIds.quadPitch(1),
                size = 40.dp,
                progressColor = OrpheusColors.synthGreen
            )
            // Quad 1 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds.getOrElse(1) { 0f },
                onValueChange = { actions.setQuadHold(1, it) },
                label = "HOLD",
                controlId = ControlIds.quadHold(1),
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }
    }
}





/**
 * Tracks state for a pointer touching strings.
 * A single pointer can grab multiple strings when dragging across them.
 */
private data class StringTouchState(
    val pointerId: PointerId,
    val startY: Float,             // Y at initial touch (for voice mix calculation)
    var currentX: Float,           // Current X position
    var currentY: Float,           // Current Y position
    val grabbedStrings: MutableSet<Int> = mutableSetOf() // All strings grabbed by this pointer
)

/**
 * Canvas for drawing and interacting with the 4 bender strings.
 *
 * Supports:
 * - Multi-touch: each pointer can control different strings independently
 * - Strumming: dragging across strings grabs them all
 * - Horizontal drag = pitch bend
 * - Vertical position at touch = voice mix (A vs B in duo)
 */
@Composable
private fun BenderStringsCanvas(
    colors: List<Color>,
    voiceStates: List<VoiceState>,
    stringCenters: List<Float>,
    onStringPositionsChanged: (List<Float>) -> Unit,
    onStringStart: (stringIndex: Int, bendAmount: Float, voiceMix: Float) -> Unit,
    onStringBendChange: (stringIndex: Int, bendAmount: Float, voiceMix: Float) -> Unit,
    onStringBendRelease: (stringIndex: Int) -> Int, // Returns spring duration in ms
    onStringTap: (stringIndex: Int, voiceMix: Float) -> Unit,
    slideBarPosition: () -> Float = { 0f },
    onSlideChange: ((Float, Float) -> Unit)? = null,
    onSlideRelease: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val path = remember { Path() }

    // Per-string deflection animation state
    val stringDeflections = remember {
        List(4) { Animatable(0f) }
    }

    // Track active touches per string (for visual feedback)
    var activeStrings by remember { mutableStateOf(setOf<Int>()) }

    // Use rememberUpdatedState to ensure pointerInput loop always sees the latest positions
    val currentStringCenters by rememberUpdatedState(stringCenters)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Track active pointers and their mode
                val pointerModes = mutableMapOf<PointerId, StringInteraction>()
                // Track dragging state for headers
                val activeHeaderDrags = mutableMapOf<PointerId, Int>() // PointerId -> StringIndex
                // Track pluck state
                val activePlucks = mutableMapOf<PointerId, StringTouchState>()

                var slideBarPointerId: PointerId? = null
                val slideBarThicknessPx = 48.dp.toPx()
                val bridgeHeight = 80.dp.toPx() // Distinct Bridge Zone at top

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val totalWidth = size.width.toFloat()
                        // Playing area height (exclude bridge)
                        val playingHeight = size.height.toFloat() - bridgeHeight

                        // Helper to find closest string to an X position
                        fun getClosestStringIndex(x: Float): Int {
                            var closestIndex = -1
                            // Increased from 80dp to 120dp to make edge strings easier to grab
                            var minDist = 120.dp.toPx()
                            
                            // Always use the latest centers
                            val centers = currentStringCenters
                            
                            for (i in 0 until 4) {
                                val centerX = centers[i] * totalWidth
                                val dist = abs(x - centerX)
                                if (dist < minDist) {
                                    minDist = dist
                                    closestIndex = i
                                }
                            }
                            return closestIndex
                        }

                        event.changes.forEach { change ->
                            val pointerId = change.id
                            val touchX = change.position.x
                            val touchY = change.position.y

                            val mode = pointerModes[pointerId]

                            if (change.pressed) {
                                if (mode == null) {
                                    // NEW TOUCH - Determine Mode

                                    // 1. Check Bridge Zone (Top Area) - EXCLUSIVE for Moving Strings
                                    if (touchY <= bridgeHeight) {
                                        val closestString = getClosestStringIndex(touchX)
                                        if (closestString != -1) {
                                            pointerModes[pointerId] = StringInteraction.MoveHeader(closestString)
                                            activeHeaderDrags[pointerId] = closestString
                                            change.consume()
                                        }
                                    }
                                    // 2. Playable Zone (Below Bridge)
                                    else {
                                        // Visual Y of Slide Bar
                                        val currentBarY = bridgeHeight + slideBarPosition() * playingHeight
                                        // Grab zone matches visual bar thickness (48dp)
                                        val inSlideBarZone = abs(touchY - currentBarY) < slideBarThicknessPx

                                        // Check Slide Bar Grab FIRST (higher z-index priority)
                                        if (inSlideBarZone && slideBarPointerId == null && onSlideChange != null) {
                                            pointerModes[pointerId] = StringInteraction.SlideBar
                                            slideBarPointerId = pointerId
                                            // Map touch relative to playing area
                                            val newY = ((touchY - bridgeHeight) / playingHeight).coerceIn(0f, 1f)
                                            val newX = (touchX / size.width).coerceIn(0f, 1f)
                                            onSlideChange(newY, newX)
                                            change.consume()
                                        }
                                        // Check Pluck (fallback - allow if slider not grabbed)
                                        else {
                                            val closestString = getClosestStringIndex(touchX)
                                            if (closestString != -1) {
                                                // Start Pluck
                                                pointerModes[pointerId] = StringInteraction.Pluck(closestString)

                                                val voiceMix = ((touchY - bridgeHeight) / playingHeight).coerceIn(0f, 1f)
                                                // CURRENT centers
                                                val stringCenterX = currentStringCenters[closestString] * totalWidth
                                                // Use a fixed width reference for deflection calculation to keep feel consistent
                                                // regardless of how close strings are.
                                                val referenceWidth = totalWidth / 6f

                                                val touchState = StringTouchState(
                                                    pointerId = pointerId,
                                                    startY = touchY,
                                                    currentX = touchX,
                                                    currentY = touchY
                                                )
                                                touchState.grabbedStrings.add(closestString)
                                                activePlucks[pointerId] = touchState

                                                activeStrings = activeStrings + closestString

                                                val deflection = (touchX - stringCenterX) / referenceWidth
                                                coroutineScope.launch {
                                                    stringDeflections[closestString].snapTo(deflection)
                                                }

                                                onStringStart(closestString, deflection.coerceIn(-1f, 1f), voiceMix)
                                                change.consume()
                                            }
                                        }
                                    }
                                } else {
                                    // EXISTING TOUCH - Continue Mode
                                    when (mode) {
                                        is StringInteraction.SlideBar -> {
                                            if (onSlideChange != null) {
                                                // Map touch relative to playing area
                                                val newY = ((touchY - bridgeHeight) / playingHeight).coerceIn(0f, 1f)
                                                val newX = (touchX / size.width).coerceIn(0f, 1f)
                                                onSlideChange(newY, newX)
                                                change.consume()
                                            }
                                        }
                                        is StringInteraction.MoveHeader -> {
                                            val stringIndex = mode.stringIndex
                                            // Move the string center
                                            val newCenter = (touchX / totalWidth).coerceIn(0.05f, 0.95f)
                                            // Must read and copy from CURRENT centers to avoid staling
                                            val newCenters = currentStringCenters.toMutableList()
                                            newCenters[stringIndex] = newCenter
                                            onStringPositionsChanged(newCenters) // Update parent state
                                            change.consume()
                                        }
                                        is StringInteraction.Pluck -> {
                                            val touchState = activePlucks[pointerId]!!
                                            val previousX = touchState.currentX
                                            touchState.currentX = touchX
                                            touchState.currentY = touchY

                                            val voiceMix = ((touchState.startY - bridgeHeight) / playingHeight).coerceIn(0f, 1f)

                                            // Strum logic: did we cross another string?
                                            // We need to check all other strings to see if we crossed them
                                            for (i in 0 until 4) {
                                                if (i in touchState.grabbedStrings) continue

                                                val sCenter = currentStringCenters[i] * totalWidth
                                                // Check crossing: (prev < center < curr) or (curr < center < prev)
                                                if ((previousX < sCenter && touchX > sCenter) || (previousX > sCenter && touchX < sCenter)) {
                                                    // Grabbed new string
                                                    touchState.grabbedStrings.add(i)
                                                    activeStrings = activeStrings + i

                                                    val referenceWidth = totalWidth / 6f
                                                    val deflection = (touchX - sCenter) / referenceWidth

                                                    onStringStart(i, deflection.coerceIn(-1f, 1f), voiceMix)
                                                }
                                            }

                                            // Update all grabbed strings
                                            touchState.grabbedStrings.forEach { grabStringIndex ->
                                                val stringCenterX = currentStringCenters[grabStringIndex] * totalWidth
                                                val referenceWidth = totalWidth / 6f
                                                val deflection = (touchX - stringCenterX) / referenceWidth

                                                coroutineScope.launch {
                                                    stringDeflections[grabStringIndex].snapTo(deflection)
                                                }

                                                onStringBendChange(grabStringIndex, deflection.coerceIn(-1f, 1f), voiceMix)
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                            } else {
                                // RELEASE
                                pointerModes.remove(pointerId)
                                activeHeaderDrags.remove(pointerId)

                                if (mode is StringInteraction.SlideBar) {
                                    slideBarPointerId = null
                                    onSlideRelease?.invoke()
                                    change.consume()
                                } else if (mode is StringInteraction.Pluck) {
                                    val touchState = activePlucks.remove(pointerId)!!

                                    // Tap detection (on initial string)
                                    val initialStringIndex = mode.initialStringIndex
                                    val initialStringCenterX = currentStringCenters[initialStringIndex] * totalWidth
                                    val dx = abs(touchState.currentX - initialStringCenterX)
                                    val dy = abs(touchState.currentY - touchState.startY)
                                    val isTap = dx < 30f && dy < 30f && touchState.grabbedStrings.size == 1

                                    if (isTap) {
                                        val voiceMix = ((touchState.startY - bridgeHeight) / playingHeight).coerceIn(0f, 1f)
                                        onStringTap(initialStringIndex, voiceMix)
                                    }

                                    // Release all
                                    touchState.grabbedStrings.forEach { stringIndex ->
                                        activeStrings = activeStrings - stringIndex
                                        val springDuration = onStringBendRelease(stringIndex)

                                        coroutineScope.launch {
                                            val startValue = stringDeflections[stringIndex].value
                                            val overshoot = -startValue * 0.20f
                                            stringDeflections[stringIndex].animateTo(
                                                targetValue = overshoot,
                                                animationSpec = tween(
                                                    durationMillis = (springDuration * 0.35f).toInt().coerceAtLeast(80),
                                                    easing = { t -> t * (2 - t) }
                                                )
                                            )
                                            stringDeflections[stringIndex].animateTo(
                                                targetValue = 0f,
                                                animationSpec = tween(
                                                    durationMillis = (springDuration * 0.65f).toInt().coerceAtLeast(120),
                                                    easing = { t -> t * (2 - t) }
                                                )
                                            )
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val totalWidth = size.width
        val bridgeHeight = 80.dp.toPx()
        val playingHeight = size.height - bridgeHeight

        // ═══════════════════════════════════════════════════════════
        // DRAW BRIDGE ZONE
        // ═══════════════════════════════════════════════════════════
        // Dark metallic finish for the bridge area
        drawRect(
            color = OrpheusColors.charcoal,
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(totalWidth, bridgeHeight)
        )
        // Bottom edge highlight (chrome look)
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.3f))
            ),
            start = Offset(0f, 0f),
            end = Offset(0f, bridgeHeight),
            strokeWidth = totalWidth // cover full width
        )
        // Separator line
        drawLine(
            color = Color.White.copy(alpha = 0.2f),
            start = Offset(0f, bridgeHeight),
            end = Offset(totalWidth, bridgeHeight),
            strokeWidth = 1.dp.toPx()
        )

        // Draw Strings & Saddles
        for (i in 0 until 4) {
            val stringCenterX = stringCenters[i] * totalWidth
            val isActive = i in activeStrings || voiceStates.getOrNull(i * 2)?.pulse == true
            val deflection = stringDeflections[i].value
            // For drawing curve, scale deflection to visual width
            val referenceWidth = totalWidth / 6f

            val color = colors.getOrElse(i) { OrpheusColors.neonCyan }
            val strokeWidth = if (isActive) 6f else 4f
            val alpha = if (isActive) 1f else 0.7f
            val glowAlpha = if (isActive) 0.4f else 0.1f

            val tension = abs(deflection).coerceIn(0f, 1f)
            // Brighten the string's own color when under tension (instead of shifting to orange)
            val tensionColor = if (tension > 0.1f) {
                Color(
                    red = (color.red + (1f - color.red) * tension * 0.5f).coerceIn(0f, 1f),
                    green = (color.green + (1f - color.green) * tension * 0.5f).coerceIn(0f, 1f),
                    blue = (color.blue + (1f - color.blue) * tension * 0.5f).coerceIn(0f, 1f),
                    alpha = 1f
                )
            } else color

            // 1. Draw Bridge Saddle (Improved Visuals)
            val saddleWidth = 40.dp.toPx()
            val saddleHeight = 52.dp.toPx() // Increased slightly
            val saddleTop = (bridgeHeight - saddleHeight) / 2f

            // Outer Chrome Rim (to define edge against dark bg)
            drawRoundRect(
                color = OrpheusColors.lightGrey,
                topLeft = Offset(stringCenterX - saddleWidth/2 - 2f, saddleTop - 2f),
                size = androidx.compose.ui.geometry.Size(saddleWidth + 4f, saddleHeight + 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1f)
            )

            // Saddle Main Body (Dark Metal Gradient)
            val saddleGradient = Brush.verticalGradient(
                colors = listOf(
                    OrpheusColors.greyHighlight, // Top highlight
                    OrpheusColors.mediumShadow, // Middle
                    OrpheusColors.darkShadow  // Bottom shadow
                ),
                startY = saddleTop,
                endY = saddleTop + saddleHeight
            )

            drawRoundRect(
                brush = saddleGradient,
                topLeft = Offset(stringCenterX - saddleWidth/2, saddleTop),
                size = androidx.compose.ui.geometry.Size(saddleWidth, saddleHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
            )

            // Saddle Border (Persistent Color Identification)
            // Active: Bright, glowing, thicker
            // Inactive: Colored but dimmer, thinner
            val borderColor = if (isActive) tensionColor else color.copy(alpha = 0.6f)
            val borderStroke = if (isActive) 3f else 2f
            
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(stringCenterX - saddleWidth/2, saddleTop),
                size = androidx.compose.ui.geometry.Size(saddleWidth, saddleHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = Stroke(width = borderStroke)
            )
            
            // Extra Glow overlap if active
            if (isActive) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            tensionColor.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(stringCenterX - saddleWidth/2, saddleTop),
                    size = androidx.compose.ui.geometry.Size(saddleWidth, saddleHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                )
            }

            // Detail: Horizontal Grooves (Machine look)
            for(lineY in 0..2) {
                 drawLine(
                    color = Color.Black.copy(alpha = 0.3f),
                    start = Offset(stringCenterX - saddleWidth/2 + 6f, saddleTop + 10f + lineY * 6f),
                    end = Offset(stringCenterX + saddleWidth/2 - 6f, saddleTop + 10f + lineY * 6f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                 )
            }

            // Detail: Central Screw Head
            val screwY = saddleTop + saddleHeight - 14f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(OrpheusColors.darkShadow, OrpheusColors.lightShadow),
                    center = Offset(stringCenterX, screwY),
                    radius = 8f
                ),
                radius = 8f,
                center = Offset(stringCenterX, screwY)
            )
            // Screw slot
             drawLine(
                color = OrpheusColors.lightGrey,
                start = Offset(stringCenterX - 4f, screwY),
                end = Offset(stringCenterX + 4f, screwY),
                strokeWidth = 2f,
                cap = StrokeCap.Round
             )

            // 2. Draw String Glow
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        tensionColor.copy(alpha = glowAlpha * 0.5f),
                        tensionColor.copy(alpha = glowAlpha),
                        tensionColor.copy(alpha = glowAlpha * 0.5f)
                    )
                ),
                start = Offset(stringCenterX, bridgeHeight),
                end = Offset(stringCenterX, size.height),
                strokeWidth = strokeWidth * 4,
                cap = StrokeCap.Round
            )

            // 3. Draw String Line
            if (abs(deflection) > 0.01f) {
                path.reset()
                path.moveTo(stringCenterX, bridgeHeight) // Start at bottom of bridge
                val midY = bridgeHeight + playingHeight / 2f
                val deflectionX = deflection * referenceWidth * 0.8f
                path.quadraticTo(
                    stringCenterX + deflectionX, midY,
                    stringCenterX, size.height
                )
                drawPath(
                    path = path,
                    color = tensionColor.copy(alpha = alpha),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            } else {
                drawLine(
                    color = color.copy(alpha = alpha),
                    start = Offset(stringCenterX, bridgeHeight),
                    end = Offset(stringCenterX, size.height),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        // ═══════════════════════════════════════════════════════════
        // DRAW SLIDE BAR OVERLAY using the passed position
        // ═══════════════════════════════════════════════════════════
        val barY = bridgeHeight + slideBarPosition() * playingHeight

        val barHeight = 44.dp.toPx()
        val numFrets = 5
        val fretSpacing = size.width / numFrets

        val slideGradient = Brush.horizontalGradient(
            colors = listOf(
                OrpheusColors.neonMagenta.copy(alpha = 0.5f),
                OrpheusColors.electricBlue.copy(alpha = 0.5f),
                OrpheusColors.warmGlow.copy(alpha = 0.5f),
                OrpheusColors.synthGreen.copy(alpha = 0.5f)
            )
        )

        // Clip slide bar to playing area (so it doesn't draw over bridge when at 0.0)
        // Although logically 0.0 maps to bridgeHeight, so it abuts the bridge.
        drawRoundRect(
            brush = slideGradient,
            topLeft = Offset(0f, barY - barHeight / 2),
            size = androidx.compose.ui.geometry.Size(size.width, barHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
        )
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = Offset(0f, barY),
            end = Offset(size.width, barY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        
        for (i in 1 until numFrets) {
            val fretX = i * fretSpacing
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(fretX, barY - barHeight/3),
                end = Offset(fretX, barY + barHeight/3),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

private sealed interface StringInteraction {
    data class Pluck(val initialStringIndex: Int) : StringInteraction
    data class MoveHeader(val stringIndex: Int) : StringInteraction
    object SlideBar : StringInteraction
}

// ==================== PREVIEWS ====================

@Preview
@Composable
private fun StringsPanelPreview() {
    MaterialTheme {
        CompactStringPanel(
            voiceState = VoiceUiState(),
            actions = VoicePanelActions.EMPTY
        )
    }
}
