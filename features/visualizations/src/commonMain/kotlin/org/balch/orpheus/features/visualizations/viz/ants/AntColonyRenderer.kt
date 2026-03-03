package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================
// Rendering — all DrawScope drawing extracted from AntColonyViz
// ============================================================================

internal fun DrawScope.drawBackground() {
    drawRect(color = OrpheusColors.antColonyBackground, size = size)
}

internal fun DrawScope.drawGroundPlane() {
    val w = size.width
    val h = size.height
    val centerX = ISO_CENTER_X
    val centerY = ISO_CENTER_Y

    val gridColor = OrpheusColors.antColonyGridLine.copy(alpha = 0.3f)
    for (i in 0..GRID_W) {
        val startPos = isoProject(i.toFloat(), 0f, 0f, centerX, centerY)
        val endPos = isoProject(i.toFloat(), 0f, GRID_D.toFloat(), centerX, centerY)
        drawLine(
            color = gridColor,
            start = Offset(startPos.x * w, startPos.y * h),
            end = Offset(endPos.x * w, endPos.y * h),
            strokeWidth = 0.5f,
        )
    }
    for (j in 0..GRID_D) {
        val startPos = isoProject(0f, 0f, j.toFloat(), centerX, centerY)
        val endPos = isoProject(GRID_W.toFloat(), 0f, j.toFloat(), centerX, centerY)
        drawLine(
            color = gridColor,
            start = Offset(startPos.x * w, startPos.y * h),
            end = Offset(endPos.x * w, endPos.y * h),
            strokeWidth = 0.5f,
        )
    }
}

internal fun DrawScope.drawBlocks(
    grid: Grid,
    voiceColors: Array<Color>,
    duoLevels: FloatArray,
    smoothedMaster: Float,
    smoothedLfo: Float,
) {
    val w = size.width
    val h = size.height
    val centerX = ISO_CENTER_X
    val centerY = ISO_CENTER_Y
    val baseSize = CUBE_SIZE * w * BLOB_BASE_SIZE_FACTOR
    val isoOut = FloatArray(2) // Scratch array for isoProjectInto

    // Audio-reactive intensity: quiet = dim, loud = glowing (like BlackHoleSun)
    val audioIntensity = (smoothedMaster * 0.6f + smoothedLfo * 0.15f).coerceIn(0f, 0.85f)

    for (y in 0 until GRID_H) {
        for (z in 0 until GRID_D) {
            for (x in 0 until GRID_W) {
                val colorIdx = grid.get(x, y, z)
                if (colorIdx == 0) continue

                // Occlusion: skip fully hidden blocks
                val hasBlockAbove = y < GRID_H - 1 && grid.get(x, y + 1, z) != 0
                val hasBlockFront = z < GRID_D - 1 && grid.get(x, y, z + 1) != 0
                val hasBlockRight = x < GRID_W - 1 && grid.get(x + 1, y, z) != 0
                if (hasBlockAbove && hasBlockFront && hasBlockRight) continue

                val baseColor = voiceColors[(colorIdx - 1).coerceIn(0, voiceColors.lastIndex)]
                isoProjectInto(x.toFloat(), y.toFloat(), z.toFloat(), centerX, centerY, isoOut)

                // Deterministic per-block variation using position hash
                val hash = (x * 73 + z * 137 + y * 251) and 0xFF
                val sizeMul = 0.7f + (hash % 40) / 66f        // 0.7–1.3 range
                val jitterX = ((hash % 11) - 5) * 0.6f
                val jitterY = ((hash / 7 % 9) - 4) * 0.5f

                val blobRadius = baseSize * sizeMul
                val cx = isoOut[0] * w + jitterX
                val cy = isoOut[1] * h + jitterY

                val isSurface = !hasBlockAbove

                // Per-duo audio reactivity
                val duoIdx = ((colorIdx - 1) / 2).coerceIn(0, 5)
                val duoEnergy = duoLevels[duoIdx]

                // Alpha: base from depth, boosted by audio + duo energy
                val baseAlpha = if (isSurface) BLOB_SURFACE_ALPHA else BLOB_INTERIOR_ALPHA
                val blobAlpha = (baseAlpha + audioIntensity * 0.5f + duoEnergy * 0.3f)
                    .coerceIn(0.05f, 0.8f)

                // Oblong shape: deterministic aspect ratio and rotation per block
                val elongation = 1.3f + (hash % 29) / 40f   // 1.3–2.0 range
                val angle = (hash * 137 % 360).toFloat()      // pseudo-random rotation

                // Surface blobs: soft radial gradient glow + oblong core
                if (isSurface) {
                    val glowRadius = blobRadius * (BLOB_GLOW_EXPAND + duoEnergy * BLOB_GLOW_ENERGY_SCALE)
                    // Glow: keep circular for soft ambient light
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to baseColor.copy(alpha = blobAlpha * 0.6f),
                                0.4f to baseColor.copy(alpha = blobAlpha * 0.3f),
                                0.7f to baseColor.copy(alpha = blobAlpha * 0.1f),
                                1f to Color.Transparent,
                            ),
                            center = Offset(cx, cy),
                            radius = glowRadius,
                        ),
                        radius = glowRadius,
                        center = Offset(cx, cy),
                        blendMode = BlendMode.Plus,
                    )
                    // Core: oblong rotated oval
                    val ovalW = blobRadius * 2f * elongation
                    val ovalH = blobRadius * 2f
                    rotate(degrees = angle, pivot = Offset(cx, cy)) {
                        drawOval(
                            color = baseColor.copy(alpha = blobAlpha * 0.7f),
                            topLeft = Offset(cx - ovalW / 2f, cy - ovalH / 2f),
                            size = Size(ovalW, ovalH),
                            blendMode = BlendMode.Plus,
                        )
                    }
                } else {
                    // Interior blobs: oblong rotated oval
                    val ovalW = blobRadius * 2f * elongation
                    val ovalH = blobRadius * 2f
                    rotate(degrees = angle, pivot = Offset(cx, cy)) {
                        drawOval(
                            color = baseColor.copy(alpha = blobAlpha),
                            topLeft = Offset(cx - ovalW / 2f, cy - ovalH / 2f),
                            size = Size(ovalW, ovalH),
                        )
                    }
                }
            }
        }
    }
}

internal fun DrawScope.drawAnts(
    ants: Array<Ant>,
    voiceColors: Array<Color>,
    strokeLeg: Stroke,
) {
    val w = size.width
    val h = size.height
    val centerX = ISO_CENTER_X
    val centerY = ISO_CENTER_Y
    val isoOut = FloatArray(2) // Scratch array for isoProjectInto

    for (ant in ants) {
        if (!ant.active) continue

        val gx = ant.x * GRID_W
        val gz = ant.z * GRID_D
        isoProjectInto(gx, 0f, gz, centerX, centerY, isoOut)
        val sx = isoOut[0] * w
        val sy = isoOut[1] * h

        val antColor = voiceColors[(ant.colorIndex - 1).coerceIn(0, voiceColors.lastIndex)]

        // Compute screen-space heading by projecting grid movement through iso
        val hdx = cos(ant.headingAngle)
        val hdz = sin(ant.headingAngle)
        isoProjectInto(gx + hdx, 0f, gz + hdz, centerX, centerY, isoOut)
        val screenDx = isoOut[0] * w - sx
        val screenDy = isoOut[1] * h - sy
        val headingDeg = atan2(screenDy, screenDx) * (180f / PI.toFloat())
        val segRadius = ANT_SEG_RADIUS
        val segGap = ANT_SEG_GAP

        rotate(degrees = headingDeg, pivot = Offset(sx, sy)) {
            // Abdomen (back, largest) — elongated oval via two overlapping circles
            val abdomenX = sx - segRadius * 2f - segGap
            drawCircle(
                color = antColor.copy(alpha = 0.85f),
                radius = segRadius * 1.4f,
                center = Offset(abdomenX, sy),
            )
            drawCircle(
                color = antColor.copy(alpha = 0.9f),
                radius = segRadius * 1.2f,
                center = Offset(abdomenX + segRadius * 0.5f, sy),
            )
            // Abdomen outline
            drawCircle(
                color = antColor,
                radius = segRadius * 1.4f,
                center = Offset(abdomenX, sy),
                style = strokeLeg,
            )

            // Thorax (middle) — slightly smaller
            drawCircle(
                color = antColor.copy(alpha = 0.9f),
                radius = segRadius,
                center = Offset(sx, sy),
            )
            // Thorax outline
            drawCircle(
                color = antColor,
                radius = segRadius,
                center = Offset(sx, sy),
                style = strokeLeg,
            )

            // Head (front) — oval shape
            val headX = sx + segRadius + segGap
            drawCircle(
                color = antColor.copy(alpha = 0.95f),
                radius = segRadius * 0.75f,
                center = Offset(headX, sy),
            )
            // Head outline
            drawCircle(
                color = antColor,
                radius = segRadius * 0.75f,
                center = Offset(headX, sy),
                style = strokeLeg,
            )

            // 6 legs (3 pairs) — longer, angled lines from thorax with knees
            val legLen = ANT_LEG_LENGTH
            for (legPair in 0 until 3) {
                val legBaseX = sx + (legPair - 1) * 5f
                val legAngleOut = 0.7f + legPair * 0.1f
                val kneeLen = legLen * 0.55f
                val shinLen = legLen * 0.5f

                // Left leg (upper + lower)
                val kneeLeftX = legBaseX - kneeLen * 0.3f
                val kneeLeftY = sy - kneeLen * legAngleOut
                drawLine(
                    color = antColor.copy(alpha = 0.7f),
                    start = Offset(legBaseX, sy),
                    end = Offset(kneeLeftX, kneeLeftY),
                    strokeWidth = 1.2f,
                )
                drawLine(
                    color = antColor.copy(alpha = 0.6f),
                    start = Offset(kneeLeftX, kneeLeftY),
                    end = Offset(kneeLeftX - shinLen * 0.4f, kneeLeftY - shinLen * 0.3f),
                    strokeWidth = 1f,
                )

                // Right leg (upper + lower)
                val kneeRightX = legBaseX - kneeLen * 0.3f
                val kneeRightY = sy + kneeLen * legAngleOut
                drawLine(
                    color = antColor.copy(alpha = 0.7f),
                    start = Offset(legBaseX, sy),
                    end = Offset(kneeRightX, kneeRightY),
                    strokeWidth = 1.2f,
                )
                drawLine(
                    color = antColor.copy(alpha = 0.6f),
                    start = Offset(kneeRightX, kneeRightY),
                    end = Offset(kneeRightX - shinLen * 0.4f, kneeRightY + shinLen * 0.3f),
                    strokeWidth = 1f,
                )
            }

            // Antennae — two longer curved lines from head
            val antennaeBase = headX + segRadius * 0.5f
            drawLine(
                color = antColor.copy(alpha = 0.7f),
                start = Offset(antennaeBase, sy),
                end = Offset(antennaeBase + 7f, sy - 5f),
                strokeWidth = 0.9f,
            )
            drawLine(
                color = antColor.copy(alpha = 0.7f),
                start = Offset(antennaeBase, sy),
                end = Offset(antennaeBase + 7f, sy + 5f),
                strokeWidth = 0.9f,
            )

            // Mandibles — small V from head front
            drawLine(
                color = antColor.copy(alpha = 0.6f),
                start = Offset(headX + segRadius * 0.6f, sy),
                end = Offset(headX + segRadius * 0.6f + 3f, sy - 2f),
                strokeWidth = 0.8f,
            )
            drawLine(
                color = antColor.copy(alpha = 0.6f),
                start = Offset(headX + segRadius * 0.6f, sy),
                end = Offset(headX + segRadius * 0.6f + 3f, sy + 2f),
                strokeWidth = 0.8f,
            )
        }

        // Glow around ant — larger to match bigger body
        drawCircle(
            color = antColor.copy(alpha = 0.12f),
            radius = ANT_GLOW_RADIUS,
            center = Offset(sx, sy),
            blendMode = BlendMode.Plus,
        )
    }
}

internal fun DrawScope.drawDebris(
    debris: Array<Debris>,
    voiceColors: Array<Color>,
) {
    val w = size.width
    val h = size.height

    for (d in debris) {
        if (!d.active) continue
        val alpha = (d.life * d.life).coerceIn(0f, 1f)
        val color = voiceColors[(d.colorIndex - 1).coerceIn(0, voiceColors.lastIndex)]

        val sx = d.x * w
        val sy = (1f - d.y) * h * 0.5f + h * 0.3f
        val radius = d.size * w

        drawCircle(
            color = color.copy(alpha = alpha * 0.4f),
            radius = radius * 3f,
            center = Offset(sx, sy),
            blendMode = BlendMode.Plus,
        )
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = Offset(sx, sy),
        )
    }
}

// ============================================================================
// Destruction Effects
// ============================================================================

internal fun DrawScope.drawStompEffect(
    progress: Float,
    stompY: Float,
    stompPhase: Int,
    bootPath: Path,
    strokeBoot: Stroke,
    strokeCrack: Stroke,
) {
    val w = size.width
    val h = size.height

    val bootColor = OrpheusColors.antBootNeon
    val bootScreenY = stompY * h + h * 0.1f
    val bootW = w * 0.3f
    val bootH = h * 0.15f
    val bootLeft = w * 0.35f

    bootPath.reset()
    bootPath.moveTo(bootLeft, bootScreenY)
    bootPath.lineTo(bootLeft + bootW, bootScreenY)
    bootPath.lineTo(bootLeft + bootW * 0.9f, bootScreenY + bootH)
    bootPath.lineTo(bootLeft + bootW * 0.1f, bootScreenY + bootH)
    bootPath.close()
    drawPath(bootPath, color = bootColor.copy(alpha = 0.6f), style = strokeBoot)
    drawPath(bootPath, color = bootColor.copy(alpha = 0.15f))

    if (stompPhase == 1 && progress < 0.45f) {
        val flashAlpha = ((0.45f - progress) / 0.05f).coerceIn(0f, 0.5f)
        drawRect(color = Color.White.copy(alpha = flashAlpha), size = size, blendMode = BlendMode.Plus)
    }

    if (stompPhase >= 1) {
        val crackAlpha = (1f - (progress - 0.3f) / 0.7f).coerceIn(0f, 0.8f)
        val crackColor = OrpheusColors.antBootNeon.copy(alpha = crackAlpha)
        val cx = w * 0.5f
        val cy = h * 0.65f
        val crackLen = w * 0.15f * min(1f, (progress - 0.3f) * 5f)
        for (i in 0 until 6) {
            val angle = i * 60f * (PI.toFloat() / 180f)
            drawLine(
                color = crackColor,
                start = Offset(cx, cy),
                end = Offset(
                    cx + cos(angle) * crackLen,
                    cy + sin(angle) * crackLen
                ),
                strokeWidth = strokeCrack.width,
            )
        }
    }
}

internal fun DrawScope.drawFireAntEffect(
    progress: Float,
    fireAntRng: Random,
) {
    val w = size.width
    val h = size.height

    val glowRadius = progress * w * 0.4f
    val cx = w * 0.5f
    val cy = h * 0.55f
    val glowAlpha = (0.4f * (1f - progress)).coerceIn(0f, 0.4f)

    drawCircle(
        color = OrpheusColors.antFireGlow.copy(alpha = glowAlpha),
        radius = glowRadius,
        center = Offset(cx, cy),
        blendMode = BlendMode.Plus,
    )

    val numFireAnts = (progress * 40).toInt()
    repeat(numFireAnts) {
        val angle = fireAntRng.nextFloat() * 2f * PI.toFloat()
        val r = glowRadius * (0.8f + fireAntRng.nextFloat() * 0.4f)
        val fx = cx + cos(angle) * r
        val fy = cy + sin(angle) * r * 0.5f

        drawCircle(
            color = OrpheusColors.antFireRed.copy(alpha = 0.8f),
            radius = 2f,
            center = Offset(fx, fy),
        )
        drawCircle(
            color = OrpheusColors.antFireGlow.copy(alpha = 0.3f),
            radius = 5f,
            center = Offset(fx, fy),
            blendMode = BlendMode.Plus,
        )
    }
}

internal fun DrawScope.drawMagnifyingGlassEffect(
    magGlassCenterX: Float,
    magGlassCenterY: Float,
    animationTime: Float,
    strokeLens: Stroke,
) {
    val w = size.width
    val h = size.height

    val lensRadius = w * 0.08f
    val lensCenterX = magGlassCenterX * w
    val lensCenterY = magGlassCenterY * h * 0.5f + h * 0.3f

    drawCircle(
        color = Color.White.copy(alpha = 0.4f),
        radius = lensRadius,
        center = Offset(lensCenterX, lensCenterY),
        style = strokeLens,
    )

    val focalY = h * 0.55f
    drawLine(
        color = OrpheusColors.antMagGlassWhite.copy(alpha = 0.3f),
        start = Offset(lensCenterX, lensCenterY),
        end = Offset(lensCenterX, focalY),
        strokeWidth = 1.5f,
    )

    val burnAlpha = 0.5f + sin(animationTime * 8f) * 0.2f
    drawCircle(
        color = OrpheusColors.antMagGlassWhite.copy(alpha = burnAlpha),
        radius = 8f,
        center = Offset(lensCenterX, focalY),
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        color = OrpheusColors.antMagGlassBurn.copy(alpha = burnAlpha * 0.6f),
        radius = 20f,
        center = Offset(lensCenterX, focalY),
        blendMode = BlendMode.Plus,
    )
}
