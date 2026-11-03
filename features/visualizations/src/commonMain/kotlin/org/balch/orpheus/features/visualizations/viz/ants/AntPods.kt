package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import org.balch.orpheus.features.visualizations.viz.shader.LayerShader
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaShader
import org.balch.orpheus.features.visualizations.viz.sprite.SpriteBatch
import org.balch.orpheus.features.visualizations.viz.sprite.SpriteCell
import org.balch.orpheus.features.visualizations.viz.sprite.drawSprites
import org.balch.orpheus.features.visualizations.viz.sprite.spriteBatchSupported
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Field level where a pod's surface sits; one pod alone peaks at 1. */
private const val POD_SURFACE = 0.3f

/**
 * The field is stored as 1 - exp(-field / this): fine steps near the surface, and a deep pile of
 * pods eases toward 1 instead of clipping into a flat plateau with a hard edge.
 */
private const val POD_FIELD_SOFTNESS = 1.5f

/** A pod's influence reaches this many radii, where its surface sits at about 0.58 of the reach. */
private const val POD_REACH = 1.73f

/** Field texels per screen pixel: the shader filters it back up, so a quarter is plenty for soft blobs. */
private const val POD_FIELD_SCALE = 0.25f
private const val POD_FIELD_MAX_WIDTH = 480

/** The glow's two sampling rings, in pod radii: the outer one only counts as much as the pods are loud. */
private const val POD_GLOW_INNER = 1.3f
private const val POD_GLOW_OUTER = 2.6f

/** How much a buried pod adds to the field, so the surface ones bead on top. */
private const val POD_BURIED_WEIGHT = 0.55f

/** The biggest pod's reach (size, swell and stretch) plus the glow's outer ring, in pod radii. */
private const val POD_BOUNDS_PAD = POD_REACH * 2.9f + POD_GLOW_OUTER

/**
 * Every visible pod in [grid] and the stomp's [debris] in draw order, in screen pixels, placed,
 * sized, stretched and lit as [drawBlocks] places them. [emit] gets centre x, y, radius,
 * elongation, angle, colour, light and field weight.
 */
private inline fun forEachPod(
    grid: Grid, voiceColors: Array<Color>, duoLevels: FloatArray,
    smoothedMaster: Float, smoothedLfo: Float, screenW: Float, screenH: Float,
    debris: Array<Debris>, iso: FloatArray,
    emit: (cx: Float, cy: Float, radius: Float, elongation: Float, angle: Float, color: Color, light: Float, weight: Float) -> Unit,
) {
    val baseSize = CUBE_SIZE * screenW * BLOB_BASE_SIZE_FACTOR
    val audioIntensity = (smoothedMaster * 0.6f + smoothedLfo * 0.15f).coerceIn(0f, 0.85f)

    for (y in 0 until GRID_H) for (z in 0 until GRID_D) for (x in 0 until GRID_W) {
        val colorIdx = grid.get(x, y, z)
        if (colorIdx == 0) continue
        val hasBlockAbove = y < GRID_H - 1 && grid.get(x, y + 1, z) != 0
        val hasBlockFront = z < GRID_D - 1 && grid.get(x, y, z + 1) != 0
        val hasBlockRight = x < GRID_W - 1 && grid.get(x + 1, y, z) != 0
        if (hasBlockAbove && hasBlockFront && hasBlockRight) continue

        val color = voiceColors[(colorIdx - 1).coerceIn(0, voiceColors.lastIndex)]
        isoProjectInto(x.toFloat(), y.toFloat(), z.toFloat(), ISO_CENTER_X, ISO_CENTER_Y, iso)
        val hash = (x * 73 + z * 137 + y * 251) and 0xFF
        val sizeMul = 0.7f + (hash % 40) / 66f
        val cx = iso[0] * screenW + ((hash % 11) - 5) * 0.6f
        val cy = iso[1] * screenH + ((hash / 7 % 9) - 4) * 0.5f
        val elongation = 1.3f + (hash % 29) / 40f
        val angle = (hash * 137 % 360) * (kotlin.math.PI.toFloat() / 180f)

        val duoEnergy = duoLevels[((colorIdx - 1) / 2).coerceIn(0, 5)]
        val isSurface = !hasBlockAbove
        val baseAlpha = if (isSurface) BLOB_SURFACE_ALPHA else BLOB_INTERIOR_ALPHA
        // The Canvas pods' alpha, as a brightness: loud duos glow, buried pods stay dim.
        val light = ((baseAlpha + audioIntensity * 0.5f + duoEnergy * 0.3f).coerceIn(0.05f, 0.8f) / 0.8f)
        // A loud duo's pods swell, as its Canvas glow widens; buried pods sink in, so the
        // surface ones bead on top instead of everything fusing into one mass.
        val radius = baseSize * sizeMul * (0.85f + duoEnergy * 0.25f)
        emit(cx, cy, radius, elongation, angle, color, light, if (isSurface) 1f else POD_BURIED_WEIGHT)
    }

    // The stomp's splash: droplets stretched along their flight, flattened into puddles once
    // they land, shrinking and dimming as they fade.
    for (d in debris) {
        if (!d.active || !d.goo) continue
        val gx = d.x * GRID_W
        val gy = d.y * GRID_H
        val gz = d.z * GRID_D
        isoProjectInto(gx, gy, gz, ISO_CENTER_X, ISO_CENTER_Y, iso)
        val cx = iso[0] * screenW
        val cy = iso[1] * screenH
        val elongation: Float
        val angle: Float
        if (d.y <= 0f) {
            elongation = 1.9f
            angle = 0f
        } else {
            isoProjectInto(gx + d.vx * GRID_W, gy + d.vy * GRID_H, gz + d.vz * GRID_D, ISO_CENTER_X, ISO_CENTER_Y, iso)
            val vx = iso[0] * screenW - cx
            val vy = iso[1] * screenH - cy
            elongation = 1f + min(1.2f, sqrt(vx * vx + vy * vy) / (baseSize * 6f))
            angle = atan2(vy, vx)
        }
        val color = voiceColors[(d.colorIndex - 1).coerceIn(0, voiceColors.lastIndex)]
        val radius = baseSize * (d.size / 0.006f) * (0.3f + 0.4f * d.life)
        emit(cx, cy, radius, elongation, angle, color, 0.4f + 0.6f * d.life, 1f)
    }
}

/**
 * The colony's pods as one metaball field, splatted on the CPU at a quarter of the screen's size:
 * [field] is the summed falloff and [red], [green], [blue] each texel's colour, weighted by how
 * strongly each pod reaches it and lit by its loudness. One byte a texel each, for [PodsSksl].
 * The fallback for [PodLayer]; the stomp's and the glass's uniforms live here for both.
 */
internal class PodField {
    var width = 0
        private set
    var height = 0
        private set
    var field = ByteArray(0)
        private set
    var red = ByteArray(0)
        private set
    var green = ByteArray(0)
        private set
    var blue = ByteArray(0)
        private set

    /** A typical pod's radius in field texels, which sets how far the shader's glow reaches. */
    var podRadius = 1f
        private set

    /** Field texels holding pods or their glow, as left, top, right, bottom; the shader skips the rest. */
    val bounds = FloatArray(4)
    private var fieldEmpty = true

    /** The stomp's ripple in screen pixels: centre x, y, radius and strength (0 for none). */
    val shock = FloatArray(4)

    /** The magnifying glass's lens in screen pixels: centre x, y and radius (0 for none). */
    val lens = FloatArray(3)

    /** The glass's burn in screen pixels: focus x, y and burn radius (0 for none). */
    val heat = FloatArray(3)

    fun clearEffects() {
        shock.fill(0f); lens.fill(0f); heat.fill(0f)
    }

    private var f = FloatArray(0)
    private var r = FloatArray(0)
    private var g = FloatArray(0)
    private var b = FloatArray(0)
    private val iso = FloatArray(2)

    private fun resize(screenW: Float, screenH: Float) {
        val scale = min(POD_FIELD_SCALE, POD_FIELD_MAX_WIDTH / screenW)
        val w = max(1, (screenW * scale).toInt())
        val h = max(1, (screenH * scale).toInt())
        if (w == width && h == height) return
        width = w
        height = h
        val n = w * h
        field = ByteArray(n); red = ByteArray(n); green = ByteArray(n); blue = ByteArray(n)
        f = FloatArray(n); r = FloatArray(n); g = FloatArray(n); b = FloatArray(n)
    }

    /** Splats every pod [forEachPod] gives. */
    fun splat(
        grid: Grid, voiceColors: Array<Color>, duoLevels: FloatArray,
        smoothedMaster: Float, smoothedLfo: Float, screenW: Float, screenH: Float,
        debris: Array<Debris> = emptyArray(),
    ) {
        resize(screenW, screenH)
        f.fill(0f); r.fill(0f); g.fill(0f); b.fill(0f)
        val sx = width / screenW
        val sy = height / screenH
        podRadius = CUBE_SIZE * screenW * BLOB_BASE_SIZE_FACTOR * sx
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        forEachPod(grid, voiceColors, duoLevels, smoothedMaster, smoothedLfo, screenW, screenH, debris, iso) { x, y, radius, elongation, angle, color, light, weight ->
            val cx = x * sx
            val cy = y * sy
            splatPod(cx, cy, radius * sx, elongation, angle, color, light, weight)
            minX = min(minX, cx); maxX = max(maxX, cx)
            minY = min(minY, cy); maxY = max(maxY, cy)
        }
        val pad = podRadius * POD_BOUNDS_PAD + 2f
        bounds[0] = minX - pad; bounds[1] = minY - pad
        bounds[2] = maxX + pad; bounds[3] = maxY + pad

        // Nothing splatted: the bytes only need clearing once, not encoding every frame.
        val anySplatted = minX <= maxX
        if (!anySplatted) {
            if (!fieldEmpty) {
                field.fill(0); red.fill(0); green.fill(0); blue.fill(0)
                fieldEmpty = true
            }
            return
        }
        fieldEmpty = false
        for (i in f.indices) {
            val v = f[i]
            if (v == 0f) {
                field[i] = 0; red[i] = 0; green[i] = 0; blue[i] = 0
                continue
            }
            field[i] = ((1f - exp(-v / POD_FIELD_SOFTNESS)) * 255f + 0.5f).toInt().toByte()
            if (v > 1e-4f) {
                red[i] = (min(1f, r[i] / v) * 255f + 0.5f).toInt().toByte()
                green[i] = (min(1f, g[i] / v) * 255f + 0.5f).toInt().toByte()
                blue[i] = (min(1f, b[i] / v) * 255f + 0.5f).toInt().toByte()
            } else {
                red[i] = 0; green[i] = 0; blue[i] = 0
            }
        }
    }

    // A rotated oval of half-axes radius * elongation by radius, falling off as (1 - d^2)^3 so it
    // reaches exactly zero at its edge and neighbours sum smoothly.
    private fun splatPod(
        cx: Float, cy: Float, radius: Float, elongation: Float, angle: Float, color: Color, light: Float, weight: Float,
    ) {
        val reachMinor = radius * POD_REACH
        val reachMajor = reachMinor * elongation
        val c = cos(angle)
        val s = sin(angle)
        val half = reachMajor
        val x0 = max(0, (cx - half).toInt())
        val x1 = min(width - 1, (cx + half).toInt() + 1)
        val y0 = max(0, (cy - half).toInt())
        val y1 = min(height - 1, (cy + half).toInt() + 1)
        val invMajor2 = 1f / (reachMajor * reachMajor)
        val invMinor2 = 1f / (reachMinor * reachMinor)
        val cr = color.red * light
        val cg = color.green * light
        val cb = color.blue * light
        for (py in y0..y1) {
            val dy = py + 0.5f - cy
            var i = py * width + x0
            for (px in x0..x1) {
                val dx = px + 0.5f - cx
                val u = dx * c + dy * s
                val v = -dx * s + dy * c
                val d2 = u * u * invMajor2 + v * v * invMinor2
                if (d2 < 1f) {
                    val k = 1f - d2
                    val w = k * k * k * weight
                    f[i] += w; r[i] += w * cr; g[i] += w * cg; b[i] += w * cb
                }
                i++
            }
        }
    }
}

/**
 * Melts the pods into glossy glowing ones: lit by their own colour, a broad sheen up top and a glow
 * on the ground around them. Also bends them under the stomp's ripple and the glass's lens, and
 * heats them near its burn. Transparent elsewhere, so the ground shows through. SkSL and AGSL.
 * [SOURCE] reads [PodField] and [LAYER_SOURCE] the layer [PodLayer] draws; the look is shared.
 */
internal object PodsSksl {
    private val SURFACE_E = 1f - exp(-POD_SURFACE / POD_FIELD_SOFTNESS)

    private const val UNIFORMS = """
uniform float2 resolution;
uniform float2 fieldSize;
uniform float podRadius;
uniform float4 bounds;
uniform float4 shock;
uniform float3 lens;
uniform float3 heat;
"""

    private val MELT = """
// How hot the glass has made screen point p: 1 at the burn's edge, 0 well clear of it.
float heatAt(float2 p) {
    if (heat.z <= 0.0) return 0.0;
    float2 d = p - heat.xy;
    d.y *= 1.7; // the ground is foreshortened
    return 1.0 - smoothstep(heat.z * 0.8, heat.z * 2.4, length(d));
}

// A colour heated toward orange, then white.
float3 burn(float3 c, float h) {
    c = mix(c, float3(1.0, 0.45, 0.1), smoothstep(0.0, 0.6, h));
    return mix(c, float3(1.0, 0.95, 0.8), smoothstep(0.5, 1.0, h));
}

// The pods' light on the ground: the field averaged on two rings around q, in the taps' colours
// weighted by their strength. The outer ring counts by loudness, so loud pods glow wider.
half4 glow(float2 q, float4 s, float surf, float h, float ring) {
    float2 d = float2(1.0, 0.0);
    float inner = 0.0;
    float outer = 0.0;
    float e = s.a;
    float3 c = s.rgb;
    for (int i = 0; i < 8; i++) {
        float4 tapA = S(q + d * (podRadius * $POD_GLOW_INNER));
        float4 tapB = S(q + d * (podRadius * $POD_GLOW_OUTER));
        inner += tapA.a;
        outer += tapB.a;
        c += tapA.rgb + tapB.rgb;
        d = float2(d.x - d.y, d.x + d.y) * 0.70710678;
    }
    float total = e + inner + outer;
    if (total < 0.004) return half4(0.0);
    c /= total;
    float peak = max(c.r, max(c.g, c.b));
    float lum = max(peak, h);
    float3 hue = burn(c / max(peak, 0.05), h);
    float near = e / surf;
    float wide = (inner * 0.6 + outer * 1.2 * lum) / (8.0 * surf);
    float a = min(0.85, (near * near * 0.6 + wide * 0.5) * (0.35 + 0.65 * lum) * (1.0 + ring));
    // Less alpha than colour, so the glow adds light to the ground more than it covers it.
    return half4(half3(hue * a), half(a * 0.6));
}

half4 main(float2 p) {
    // The ripple pushes the pods outward as it passes, an oval because the ground is foreshortened.
    float2 sp = p;
    float ring = 0.0;
    if (shock.w > 0.0) {
        float2 d = p - shock.xy;
        d.y *= 2.0;
        float r = length(d);
        float band = (r - shock.z) / (resolution.x * 0.035);
        ring = exp(-band * band) * shock.w;
        if (r > 0.001) sp -= float2(d.x, d.y * 0.5) / r * ring * resolution.x * 0.02;
    }
    // Under the lens the pods are magnified, most at its centre and not at all at its rim.
    if (lens.z > 0.0) {
        float2 d = sp - lens.xy;
        float r2 = dot(d, d) / (lens.z * lens.z);
        if (r2 < 1.0) sp = lens.xy + d * (0.55 + 0.45 * r2);
    }
    float h = heatAt(sp);
    float surf = $SURFACE_E * (1.0 - 0.45 * h); // hot pods swell
    float2 q = sp / resolution * fieldSize;
    if (q.x < bounds.x || q.y < bounds.y || q.x > bounds.z || q.y > bounds.w) return half4(0.0);
    float4 s = S(q);
    float e = s.a;
    // Pods fade into their glow across a band around the surface, so their edges are soft.
    float edgeLo = surf - 0.045;
    float edgeHi = surf + 0.07;
    if (e < edgeLo) return glow(q, s, surf, h, ring);
    float3 c = s.rgb / max(e, 0.002);
    float lum = max(c.r, max(c.g, c.b));
    // The pod's hue at full strength; its loudness comes back in as glow, and heat makes it blaze.
    float3 hue = burn(c / max(lum, 0.05), h);
    float shine = max(0.35 + 0.65 * lum, h) * (1.0 + ring * 0.6);
    float2 g = slope(q);
    float3 n = normalize(float3(-g.x * 6.0, -g.y * 6.0, 1.0));
    float3 L = normalize(float3(-0.4, -0.7, 0.6));
    float diff = max(dot(n, L), 0.0);
    // A broad sheen and a faint bounce light follow the curve; a tight highlight drew a white dot.
    float3 r = reflect(float3(0.0, 0.0, -1.0), n);
    float sheen = pow(max(dot(r, L), 0.0), 8.0) * 0.45;
    float bounce = pow(max(dot(r, normalize(float3(0.6, 0.5, 0.6))), 0.0), 6.0) * 0.25;
    float rim = (1.0 - n.z) * (1.0 - n.z);
    float3 col = hue * shine * (0.5 + 0.6 * diff) + hue * (rim * 0.9 + bounce) * shine
        + mix(float3(1.0), hue, 0.5) * sheen * shine;
    float a = 0.6 + 0.35 * smoothstep(surf, surf + 0.1, e);
    half4 top = half4(half3(min(col, float3(1.0)) * a), half(a));
    if (e >= edgeHi) return top;
    return mix(glow(q, s, surf, h, ring), top, half(smoothstep(edgeLo, edgeHi, e)));
}
"""

    val SOURCE = UNIFORMS + """
uniform shader field;
uniform shader red;
uniform shader green;
uniform shader blue;

// The encoded field, 0..1: the surface level below is in the same encoding.
float E(float2 q) { return field.eval(q).a; }

// Colour weighted by the field in rgb, the field in a.
float4 S(float2 q) {
    float e = E(q);
    return float4(red.eval(q).a * e, green.eval(q).a * e, blue.eval(q).a * e, e);
}

float2 slope(float2 q) {
    return float2(E(q + float2(1.0, 0.0)) - E(q - float2(1.0, 0.0)), E(q + float2(0.0, 1.0)) - E(q - float2(0.0, 1.0)));
}
""" + MELT

    /** Texels here are pixels: `texel` is the [PodField] texel the sheen was tuned at, in pixels. */
    val LAYER_SOURCE = UNIFORMS + """
uniform float texel;
uniform shader content;

float E(float2 q) { return content.eval(q).a; }

float4 S(float2 q) { return float4(content.eval(q)); }

// Eight taps on pixel corners: at full size one byte of field is a visible bump in the sheen, which
// the quarter-size field hid by interpolating. This smooths it back out at about the same width.
float2 slope(float2 q) {
    float2 c = floor(q);
    float t = floor(texel + 0.5);
    float tl = E(c + float2(-t, -t));
    float tr = E(c + float2(t, -t));
    float bl = E(c + float2(-t, t));
    float br = E(c + float2(t, t));
    float gx = tr + 2.0 * E(c + float2(t, 0.0)) + br - tl - 2.0 * E(c + float2(-t, 0.0)) - bl;
    float gy = bl + 2.0 * E(c + float2(0.0, t)) + br - tl - 2.0 * E(c + float2(0.0, -t)) - tr;
    return float2(gx, gy) * 0.25 * texel / t;
}
""" + MELT
}

/** Where [PodLayer] can run: a layer effect for the melt and `drawVertices` for the sprites. */
internal fun podLayerSupported(shader: LayerShader): Boolean = shader.isSupported && spriteBatchSupported()

/**
 * The colony's pods as sprites in a GPU layer, melted by [PodsSksl.LAYER_SOURCE]. The field needs no
 * floats: stacking sprites of alpha 1 - exp(-w / softness) source-over multiplies their
 * transmittance, so the layer's alpha is the sum's encoding, 1 - exp(-sum / softness), and can't
 * clip. Its rgb is the premultiplied colour, weighted toward the pods drawn last. One per [PodField].
 */
internal class PodLayer {
    // Half a texel inside the gradient, where SpriteBatch keeps linear filtering off the next cell.
    private val spriteReach = POD_REACH * 63f / 64f
    private val batch by lazy(LazyThreadSafetyMode.NONE) { SpriteBatch(listOf(podCell(1f), podCell(POD_BURIED_WEIGHT))) }
    private val iso = FloatArray(2)
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f

    // What the held effect was built from: most frames nothing here moves, so it is reused.
    private val uniforms = FloatArray(UNIFORM_COUNT)
    private val built = FloatArray(UNIFORM_COUNT)
    private var effect: RenderEffect? = null

    private fun podCell(weight: Float) = SpriteCell(
        *Array(CELL_STOPS + 1) { i ->
            val d = i.toFloat() / CELL_STOPS
            val k = 1f - d * d
            d to 1f - exp(-k * k * k * weight / POD_FIELD_SOFTNESS)
        },
    )

    /** False if the shader wouldn't build, with nothing drawn, so the caller can fall back. */
    fun draw(
        scope: DrawScope, shader: LayerShader, layer: GraphicsLayer, effects: PodField,
        grid: Grid, voiceColors: Array<Color>, duoLevels: FloatArray,
        smoothedMaster: Float, smoothedLfo: Float, debris: Array<Debris>,
    ): Boolean = with(scope) {
        val w = size.width
        val h = size.height
        if (w < 1f || h < 1f) return true
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        layer.record(this, layoutDirection, IntSize(w.toInt(), h.toInt())) {
            drawSprites(batch, BlendMode.SrcOver) {
                forEachPod(grid, voiceColors, duoLevels, smoothedMaster, smoothedLfo, w, h, debris, iso) { cx, cy, radius, elongation, angle, color, light, weight ->
                    val minor = radius * spriteReach
                    quad(
                        if (weight == 1f) 0 else 1, cx, cy, minor * elongation, minor, cos(angle), sin(angle),
                        Color(color.red * light, color.green * light, color.blue * light),
                    )
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                }
            }
        }
        // Nothing to melt: no reason to pay for the layer.
        if (minX > maxX) return true
        layer.renderEffect = effect(shader, effects, w, h) ?: return false
        drawLayer(layer)
        true
    }

    fun effect(shader: LayerShader, effects: PodField, w: Float, h: Float): RenderEffect? {
        val podRadius = CUBE_SIZE * w * BLOB_BASE_SIZE_FACTOR
        // Whole pixels, so a droplet's sub-pixel drift doesn't rebuild the effect.
        val pad = podRadius * POD_BOUNDS_PAD + 2f
        uniforms[0] = w
        uniforms[1] = h
        uniforms[2] = podRadius
        uniforms[3] = floor(minX - pad)
        uniforms[4] = floor(minY - pad)
        uniforms[5] = ceil(maxX + pad)
        uniforms[6] = ceil(maxY + pad)
        effects.shock.copyInto(uniforms, 7)
        effects.lens.copyInto(uniforms, 11)
        effects.heat.copyInto(uniforms, 14)
        if (effect != null && uniforms.contentEquals(built)) return effect
        uniforms.copyInto(built)
        shader.uniform("resolution", w, h)
        shader.uniform("fieldSize", w, h)
        shader.uniform("podRadius", podRadius)
        shader.uniform("bounds", uniforms.copyOfRange(3, 7))
        shader.uniform("shock", effects.shock)
        shader.uniform("lens", effects.lens)
        shader.uniform("heat", effects.heat)
        shader.uniform("texel", 1f / min(POD_FIELD_SCALE, POD_FIELD_MAX_WIDTH / w))
        effect = shader.effect()
        return effect
    }

    private companion object {
        const val CELL_STOPS = 32
        const val UNIFORM_COUNT = 17
    }
}

/** The colony's pods through [shader]; the caller draws [drawBlocks] instead where it isn't supported. */
internal fun DrawScope.drawPods(shader: PlasmaShader, pods: PodField) {
    shader.uniform("resolution", size.width, size.height)
    shader.uniform("fieldSize", pods.width.toFloat(), pods.height.toFloat())
    shader.uniform("podRadius", pods.podRadius)
    shader.uniform("bounds", pods.bounds)
    shader.uniform("shock", pods.shock)
    shader.uniform("lens", pods.lens)
    shader.uniform("heat", pods.heat)
    shader.alphaImage("field", pods.field, pods.width, pods.height)
    shader.alphaImage("red", pods.red, pods.width, pods.height)
    shader.alphaImage("green", pods.green, pods.width, pods.height)
    shader.alphaImage("blue", pods.blue, pods.width, pods.height)
    shader.brush()?.let { drawRect(it) }
}
