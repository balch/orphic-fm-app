package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import org.balch.orpheus.features.cowbell.shake.FILL_PLANE_Y
import org.balch.orpheus.features.cowbell.shake.FillParticle
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The fill plane (`y = FILL_PLANE_Y`) seen through a camera, with nothing allocated per point.
 * [set] takes the camera and the bend once per frame; [project] then leaves a point's place in
 * [px] and [py] (model units after perspective, `y` down, the box centre at 0) and its
 * perspective scale in [s]. Multiply by the mesh's px-per-unit scale to get pixels.
 */
internal class PlaneView {
    private var cy = 1f
    private var sy = 0f
    private var cp = 1f
    private var sp = 0f
    private var cr = 1f
    private var sr = 0f
    private var squeeze = 1f
    private var planeY = FILL_PLANE_Y

    var px = 0f
        private set
    var py = 0f
        private set
    var s = 1f
        private set

    fun set(camera: BellCamera, pull: Float) {
        val d2r = (PI / 180.0).toFloat()
        cy = cos(camera.yaw * d2r)
        sy = sin(camera.yaw * d2r)
        cp = cos(camera.pitch * d2r)
        sp = sin(camera.pitch * d2r)
        cr = cos(camera.roll * d2r)
        sr = sin(camera.roll * d2r)
        squeeze = bendSqueeze(pull)
        planeY = bendY(FILL_PLANE_Y, pull)
    }

    /** The same turn as [BellCamera.rotate], in scalars. */
    fun project(x: Float, z: Float) {
        val bx = x * squeeze
        val bz = z * squeeze
        val x1 = bx * cy + bz * sy
        val z1 = -bx * sy + bz * cy
        val y2 = planeY * cp - z1 * sp
        val z2 = planeY * sp + z1 * cp
        val rx = x1 * cr - y2 * sr
        val ry = x1 * sr + y2 * cr
        s = FOC / (CAMD - z2)
        px = rx * s
        py = -ry * s
    }
}

/**
 * Draws the SHAKE fill: [draw] puts every piece on the fill plane's projection, clipped to the
 * mouth. Every brush is built once, in unit size around the origin, and each piece is drawn
 * under a translate and scale, so a frame builds no brush, path or colour list.
 */
internal class FillPainter {
    /** The mouth outline, rewound each frame. */
    internal val clip = Path()

    /** How many brushes were ever built, for the no-allocation test. */
    internal var brushBuilds = 0
        private set

    private val view = PlaneView()
    private var marble: Brush? = null
    private var marbleHalo: Brush? = null
    private var jingle: Brush? = null
    private val beads = arrayOfNulls<Brush>(BellPalette.size)

    fun draw(
        scope: DrawScope,
        fill: CowbellFill,
        particles: List<FillParticle>,
        camera: BellCamera,
        pull: Float,
        frame: MeshFrame,
        flash: Float,
    ) {
        view.set(camera, pull)
        val rim = frame.rim
        clip.rewind()
        clip.moveTo(rim[0].x, rim[0].y)
        for (i in 1 until rim.size) clip.lineTo(rim[i].x, rim[i].y)
        clip.close()
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val scale = frame.scale
        with(scope) {
            clipPath(clip) {
                for (i in 0 until particles.size) {
                    val p = particles[i]
                    view.project(p.x, p.z)
                    val x = cx + view.px * scale
                    val y = cy + view.py * scale
                    val r = p.r * view.s * scale
                    when (fill) {
                        CowbellFill.MARBLE -> drawMarble(x, y, r)
                        CowbellFill.BEADS -> drawBead(x, y, r, i)
                        CowbellFill.SEEDS -> drawSeed(x, y, r, p.spin, flash)
                        CowbellFill.JINGLES -> drawJingle(x, y, r, p.spin)
                    }
                }
            }
        }
    }

    private fun DrawScope.drawMarble(x: Float, y: Float, r: Float) {
        val halo = marbleHalo ?: build {
            Brush.radialGradient(
                0.45f to OrpheusColors.neonCyan.copy(alpha = 0.4f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1.9f,
            )
        }.also { marbleHalo = it }
        val body = marble ?: build {
            // A broad sheen off the centre: a lighter cyan, never white.
            Brush.radialGradient(
                0f to lerp(OrpheusColors.neonCyan, Color.White, 0.25f),
                0.4f to OrpheusColors.neonCyan,
                1f to MarbleBlue,
                center = Offset(-0.25f, -0.3f),
                radius = 1.35f,
            )
        }.also { marble = it }
        translate(x, y) {
            scale(r, r, Offset.Zero) {
                drawCircle(halo, 1.9f, Offset.Zero)
                drawCircle(body, 1f, Offset.Zero)
            }
        }
    }

    private fun DrawScope.drawBead(x: Float, y: Float, r: Float, index: Int) {
        val slot = index % beads.size
        val body = beads[slot] ?: build {
            val hue = BellPalette[slot]
            Brush.radialGradient(
                0f to lerp(hue, Color.White, 0.3f),
                0.45f to hue,
                1f to BeadEdge,
                center = Offset(-0.3f, -0.35f),
                radius = 1.4f,
            )
        }.also { beads[slot] = it }
        translate(x, y) {
            scale(r, r, Offset.Zero) {
                drawCircle(body, 1f, Offset.Zero)
                drawCircle(BeadHole, 0.25f, Offset.Zero)
            }
        }
    }

    private fun DrawScope.drawSeed(x: Float, y: Float, r: Float, spin: Float, flash: Float) {
        // The warm band cycles with the spin, 30 to 60 degrees of hue.
        val hue = 30f + (spin * 40f).toInt().mod(30)
        val color = Color.hsl(hue, 0.9f, 0.55f + 0.15f * flash.coerceIn(0f, 1f))
        translate(x, y) {
            rotate(spin * RAD_TO_DEG, Offset.Zero) {
                drawOval(color, Offset(-r * 1.5f, -r * 0.8f), Size(r * 3f, r * 1.6f))
            }
        }
    }

    private fun DrawScope.drawJingle(x: Float, y: Float, r: Float, spin: Float) {
        val body = jingle ?: build {
            Brush.linearGradient(
                0f to lerp(OrpheusColors.neonMagenta, Color.White, 0.2f),
                0.5f to JingleMid,
                1f to OrpheusColors.neonMagenta,
                start = Offset(-1f, -1f),
                end = Offset(1f, 1f),
            )
        }.also { jingle = it }
        translate(x, y) {
            rotate(spin * RAD_TO_DEG, Offset.Zero) {
                scale(r, r, Offset.Zero) {
                    drawOval(body, Offset(-1f, -0.55f), Size(2f, 1.1f))
                    drawCircle(JingleRing, 0.22f, Offset.Zero, style = RingStroke)
                }
            }
        }
    }

    private inline fun build(make: () -> Brush): Brush {
        brushBuilds++
        return make()
    }

    private companion object {
        const val RAD_TO_DEG = (180.0 / PI).toFloat()
        val MarbleBlue = Color(0xFF2A3CFF)
        val BeadEdge = Color(0xFF120C22)
        val BeadHole = Color(0xFF0A0714)
        val JingleMid = Color(0xFFC9B8FF)
        val JingleRing = Color(0xFF281440).copy(alpha = 0.8f)

        /** In unit space, so the scale makes it a fifth of the jingle's radius wide. */
        val RingStroke = Stroke(0.12f)
    }
}
