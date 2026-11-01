package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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

/** Embers and sparks; the stomp's goo too unless [gooInPods], when [PodField] melts it in instead. */
internal fun DrawScope.drawDebris(
    debris: Array<Debris>,
    voiceColors: Array<Color>,
    gooInPods: Boolean,
    iso: FloatArray,
) {
    val w = size.width
    val h = size.height

    for (d in debris) {
        if (!d.active || (d.goo && gooInPods)) continue
        val alpha = (d.life * d.life).coerceIn(0f, 1f)
        val color = voiceColors[(d.colorIndex - 1).coerceIn(0, voiceColors.lastIndex)]

        val sx: Float
        val sy: Float
        if (d.goo) {
            isoProjectInto(d.x * GRID_W, d.y * GRID_H, d.z * GRID_D, ISO_CENTER_X, ISO_CENTER_Y, iso)
            sx = iso[0] * w
            sy = iso[1] * h
        } else {
            sx = d.x * w
            sy = (1f - d.y) * h * 0.5f + h * 0.3f
        }
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

/**
 * The stomp's ripple into [out] as centre x, y, radius and strength in screen pixels: it runs out
 * from where the boot lands and fades, and is all zeros before the impact and once it's gone.
 */
internal fun stompShock(progress: Float, w: Float, h: Float, out: FloatArray) {
    val t = (progress - STOMP_IMPACT) / 0.45f
    if (t < 0f || t > 1f) {
        out.fill(0f)
        return
    }
    out[0] = ISO_CENTER_X * w
    out[1] = ISO_CENTER_Y * h
    out[2] = t * w * 0.6f
    out[3] = (1f - t) * (1f - t)
}

/** How far the impact shakes the whole scene, for half a second after the boot lands. */
internal fun stompShake(progress: Float): Offset {
    val t = (progress - STOMP_IMPACT) * STOMP_DURATION
    if (t < 0f || t > 0.5f) return Offset.Zero
    val amp = 12f * (1f - t / 0.5f)
    return Offset(sin(t * 70f) * amp, cos(t * 95f) * amp * 0.7f)
}

private val glowStroke = Stroke(width = 12f)
private val lensRimStroke = Stroke(width = 5f)
private val lensGlintStroke = Stroke(width = 3f, cap = StrokeCap.Round)

internal fun DrawScope.drawStompEffect(
    progress: Float,
    stompY: Float,
    stompPhase: Int,
    shock: FloatArray,
    bootPath: Path,
    strokeBoot: Stroke,
    strokeCrack: Stroke,
) {
    val w = size.width
    val h = size.height

    // The ripple's visible crest; the pod shader bends the pods along the same ring.
    if (shock[3] > 0f) {
        val r = shock[2]
        val topLeft = Offset(shock[0] - r, shock[1] - r * 0.5f)
        val ovalSize = Size(r * 2f, r)
        drawOval(OrpheusColors.antBootNeon.copy(alpha = 0.2f * shock[3]), topLeft, ovalSize, style = glowStroke, blendMode = BlendMode.Plus)
        drawOval(OrpheusColors.antBootNeon.copy(alpha = 0.6f * shock[3]), topLeft, ovalSize, style = strokeCrack, blendMode = BlendMode.Plus)
    }

    // A boot in profile, toe to the right: dark leather, a treaded sole and a neon rim light.
    val bootColor = OrpheusColors.antBootNeon
    val u = w * 0.3f
    val bh = h * 0.15f
    val left = w * 0.35f
    val sole = stompY * h + h * 0.1f + bh
    val st = bh * 0.24f
    bootPath.reset()
    bootPath.moveTo(left + u * 0.06f, sole - bh * 3.2f)
    bootPath.lineTo(left + u * 0.04f, sole - bh * 0.9f)
    bootPath.quadraticTo(left, sole - bh * 0.6f, left + u * 0.01f, sole - st)
    bootPath.lineTo(left + u * 0.94f, sole - st)
    bootPath.quadraticTo(left + u * 1.02f, sole - st, left + u, sole - st - bh * 0.25f)
    bootPath.quadraticTo(left + u * 0.97f, sole - bh * 0.85f, left + u * 0.72f, sole - bh * 0.95f)
    bootPath.lineTo(left + u * 0.48f, sole - bh * 1.25f)
    bootPath.lineTo(left + u * 0.44f, sole - bh * 3.2f)
    bootPath.close()
    drawPath(
        bootPath,
        Brush.verticalGradient(listOf(Color(0xFF223548), Color(0xFF101A26)), sole - bh * 3.2f, sole),
    )
    drawRoundRect(
        color = Color(0xFF070B10),
        topLeft = Offset(left, sole - st),
        size = Size(u * 1.0f, st),
        cornerRadius = CornerRadius(st * 0.4f),
    )
    for (i in 0 until 11) {
        val x = left + u * (0.05f + 0.09f * i)
        drawLine(bootColor.copy(alpha = 0.45f), Offset(x, sole - st * 0.5f), Offset(x, sole), strokeWidth = 2f)
    }
    drawLine(bootColor.copy(alpha = 0.5f), Offset(left + u * 0.02f, sole - st), Offset(left + u * 0.95f, sole - st), strokeWidth = 1.5f)
    // Laces across the shin, hooked on the front edge.
    for (i in 1..4) {
        val f = i / 5f
        val fx = left + u * (0.48f - 0.04f * f)
        val fy = sole - bh * (1.25f + 1.95f * f)
        drawLine(bootColor.copy(alpha = 0.5f), Offset(fx, fy), Offset(fx - u * 0.12f, fy + bh * 0.08f), strokeWidth = 2f)
    }
    drawPath(bootPath, bootColor.copy(alpha = 0.18f), style = glowStroke, blendMode = BlendMode.Plus)
    drawPath(bootPath, bootColor.copy(alpha = 0.85f), style = strokeBoot)

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

/**
 * Lays out the glass from its burn centre on the grid: [heat] gets the focus on the ground and the
 * burn radius, [lens] the lens hanging above it, all in screen pixels for both shader and Canvas.
 */
internal fun magGlassLayout(
    centerX: Float, centerZ: Float, progress: Float, w: Float, h: Float,
    lens: FloatArray, heat: FloatArray, iso: FloatArray,
) {
    isoProjectInto(centerX * GRID_W, 0f, centerZ * GRID_D, ISO_CENTER_X, ISO_CENTER_Y, iso)
    heat[0] = iso[0] * w
    heat[1] = iso[1] * h
    heat[2] = (2f + progress * 3f) * CUBE_SIZE * w
    lens[0] = heat[0]
    lens[1] = heat[1] - h * 0.24f
    lens[2] = w * 0.075f
}

internal fun DrawScope.drawMagnifyingGlassEffect(
    lens: FloatArray,
    heat: FloatArray,
    animationTime: Float,
    beamPath: Path,
    strokeLens: Stroke,
) {
    val lx = lens[0]
    val ly = lens[1]
    val r = lens[2]
    val fx = heat[0]
    val fy = heat[1]
    val sun = OrpheusColors.antMagGlassWhite

    // Sunlight falling onto the lens, then focused down to the burn in a cone.
    drawRect(
        Brush.horizontalGradient(listOf(sun.copy(alpha = 0f), sun.copy(alpha = 0.08f), sun.copy(alpha = 0f)), lx - r * 1.2f, lx + r * 1.2f),
        topLeft = Offset(lx - r * 1.2f, 0f), size = Size(r * 2.4f, ly), blendMode = BlendMode.Plus,
    )
    val beam = Brush.verticalGradient(listOf(sun.copy(alpha = 0.06f), sun.copy(alpha = 0.3f)), ly, fy)
    for (i in 0..1) {
        val spread = if (i == 0) 0.9f else 0.35f
        beamPath.reset()
        beamPath.moveTo(lx - r * spread, ly)
        beamPath.lineTo(lx + r * spread, ly)
        beamPath.lineTo(fx, fy)
        beamPath.close()
        drawPath(beamPath, beam, blendMode = BlendMode.Plus)
    }

    // The handle, out from the rim at the lower right: a silver collar, then a dark grip.
    val hx = lx + r * 0.7071f
    val hy = ly + r * 0.7071f
    val collar = Offset(hx + r * 0.2f, hy + r * 0.2f)
    val end = Offset(hx + r * 1.15f, hy + r * 1.15f)
    drawLine(Color(0xFFB8C4CE), collar, end, strokeWidth = 14f, cap = StrokeCap.Round)
    drawLine(Color(0xFF1E1A2A), collar, end, strokeWidth = 10f, cap = StrokeCap.Round)
    drawLine(Color(0xFFCFD8E0), Offset(hx, hy), collar, strokeWidth = 9f)

    // The glass: a faint sheen, a silver rim with a dark inner edge, and a curved highlight.
    val center = Offset(lx, ly)
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.02f), Color(0xFF9FE8FF).copy(alpha = 0.1f)), center, r),
        radius = r, center = center,
    )
    drawCircle(Color(0xFFCFD8E0).copy(alpha = 0.9f), radius = r, center = center, style = lensRimStroke)
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = r - 3.5f, center = center, style = strokeLens)
    drawArc(
        Color.White.copy(alpha = 0.55f), startAngle = 200f, sweepAngle = 60f, useCenter = false,
        topLeft = Offset(lx - r * 0.75f, ly - r * 0.75f), size = Size(r * 1.5f, r * 1.5f),
        style = lensGlintStroke,
    )

    // The burn: a flickering white-hot point in an orange bloom.
    val flicker = 0.5f + sin(animationTime * 8f) * 0.2f
    val focal = Offset(fx, fy)
    drawCircle(OrpheusColors.antMagGlassBurn.copy(alpha = flicker * 0.3f), radius = heat[2] * 0.6f, center = focal, blendMode = BlendMode.Plus)
    drawCircle(OrpheusColors.antMagGlassBurn.copy(alpha = flicker * 0.6f), radius = 22f, center = focal, blendMode = BlendMode.Plus)
    drawCircle(sun.copy(alpha = 0.6f + flicker * 0.4f), radius = 8f, center = focal, blendMode = BlendMode.Plus)
}
