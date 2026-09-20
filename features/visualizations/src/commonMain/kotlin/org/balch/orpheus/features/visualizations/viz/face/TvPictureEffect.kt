package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.round

/**
 * One source for SkSL (JVM, iOS, WASM) and AGSL (Android 13+), run over the whole tube rather than
 * a single picture element: a percussion hit tears the broadcast, not just the story graphic.
 * `content` is the already-drawn picture layer, sampled in the layer's own pixels.
 */
object TvPictureShaderSource {
    const val SKSL = """
uniform shader content;
uniform float4 glass;
uniform float glitch;
uniform float mono;
uniform float time;

// Sin-free hash, same as the face dissolve: fract(sin(...)) loses precision as time grows.
float hash(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, float3(p3.y, p3.z, p3.x) + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Clamped so a displaced row runs into its own edge column instead of showing a transparent bar
// or the far side of the picture. The pixel's own column is always in range, so a displacement
// of zero resamples nothing and cannot blur.
half4 tap(float x, float y, float lo, float hi) {
    return content.eval(float2(clamp(x, lo, hi), y));
}

// Five taps along the row, unrolled because neither dialect wants a dynamic loop. At a spacing
// of zero all five land on the same pixel, so this degrades to a single exact sample.
half4 smear(float x, float y, float spacing, float lo, float hi) {
    half4 c = tap(x - 2.0 * spacing, y, lo, hi);
    c += tap(x - spacing, y, lo, hi);
    c += tap(x, y, lo, hi);
    c += tap(x + spacing, y, lo, hi);
    c += tap(x + 2.0 * spacing, y, lo, hi);
    return c * 0.2;
}

half4 main(float2 fragCoord) {
    // Everything outside the tube belongs to the cabinet, so it passes through untouched and no
    // picture pixel can be dragged out onto the wood.
    if (fragCoord.x < glass.x || fragCoord.x > glass.z ||
        fragCoord.y < glass.y || fragCoord.y > glass.w) {
        return content.eval(fragCoord);
    }

    float w = glass.z - glass.x;
    float h = glass.w - glass.y;
    float line = (fragCoord.y - glass.y) / h * 90.0;
    float row = floor(line);
    // Capped at 3 Hz, the same photosensitivity bound as the kick flash: this is a large area.
    float roll = floor(time * 3.0);
    float lo = min(glass.x + 0.5, fragCoord.x);
    float hi = max(glass.z - 0.5, fragCoord.x);
    // Coverage where this pixel actually is, so the rounded corners of the glass stay rounded
    // even on a row that has been displaced.
    half cover = content.eval(fragCoord).a;
    // A red/blue fringe on a black-and-white broadcast would be the one colour on the tube, so
    // mono splits into a horizontal double image instead.
    half isMono = half(step(0.5, mono));
    float split = glitch * 0.004 * w;

    if (hash(float2(row, roll)) < 0.92) {
        // A row that did not tear stays sharp and cheap: one tap per channel, nothing else.
        half4 a = tap(fragCoord.x + split, fragCoord.y, lo, hi);
        half4 b = tap(fragCoord.x - split, fragCoord.y, lo, hi);
        half4 m = tap(fragCoord.x, fragCoord.y, lo, hi);
        half3 rgb = mix(half3(a.r, m.g, b.b), (a.rgb + b.rgb) * 0.5, isMono);
        return half4(rgb, m.a) * cover;
    }

    // A torn row is a tape dropout: it slides sideways, smears along its own length and lifts
    // into a grainy line, so it reads across the whole glass and not only where the picture
    // happens to have an edge. Softened by about a pixel top and bottom so it never aliases.
    float rowPx = h / 90.0;
    float f = fract(line);
    float soft = clamp(1.2 / max(rowPx, 1.0), 0.03, 0.45);
    float amount = smoothstep(0.0, soft, f) * smoothstep(0.0, soft, 1.0 - f);

    float x = fragCoord.x + amount * glitch * 0.03 * w;
    float spacing = amount * glitch * 0.004 * w;
    float wide = split * (1.0 + 1.5 * amount);
    half4 a = smear(x + wide, fragCoord.y, spacing, lo, hi);
    half4 b = smear(x - wide, fragCoord.y, spacing, lo, hi);
    half4 m = smear(x, fragCoord.y, spacing, lo, hi);
    half3 rgb = mix(half3(a.r, m.g, b.b), (a.rgb + b.rgb) * 0.5, isMono);

    // Neutral grey grain, so a mono broadcast stays colourless, and capped at 0.14 so the line
    // is a texture rather than a flash. It decays with the hit like everything else here.
    float grain = hash(float2(floor(fragCoord.x * 0.5), roll + row * 0.37));
    half lift = half(clamp((0.08 + 0.06 * step(0.60, grain)) * glitch * amount, 0.0, 0.14));
    rgb = clamp(rgb + half3(lift), half3(0.0), half3(1.0));

    return half4(rgb, m.a) * cover;
}
"""
}

/** Skia `ImageFilter` on JVM, iOS and WASM; AGSL `RenderEffect` on Android 13+. */
expect class TvPictureRenderer() {
    fun isSupported(): Boolean
    fun dispose()

    /**
     * The pass for one frame, or null when it cannot be built. Reuses the last effect while the
     * inputs that the shader actually reads are unchanged, so a held glitch allocates nothing.
     */
    fun effect(glass: Rect, glitch: Float, mono: Float, time: Float): RenderEffect?
}

/**
 * Tears and fringes everything drawn inside this layer. [glass] is the tube in the layer's own
 * pixels. Below Android 13, or if the shader will not compile, this is a no-op.
 */
@Composable
fun Modifier.tvPictureEffect(glass: Rect, glitch: Float, mono: Float, time: Float): Modifier {
    val renderer = remember { TvPictureRenderer() }
    DisposableEffect(renderer) { onDispose { renderer.dispose() } }

    val amount = sanitizedGlitch(glitch)
    // No layer at all while idle: the pass costs nothing between hits and the picture is left
    // bit-for-bit as it was drawn. Belt and braces alongside MorphDirector's own snap-to-zero
    // (MorphDirector.VISIBLE_EPSILON): a decaying glitch must not keep this layer alive forever.
    if (!runsPicturePass(amount) || glass.width < 1f || glass.height < 1f || !renderer.isSupported()) {
        return this
    }
    val pass = renderer.effect(glass, amount, mono, time) ?: return this
    return graphicsLayer { renderEffect = pass }
}

/** Rate the torn row set is re-rolled at, in Hz. Photosensitivity bound, never raise it. */
internal const val TEAR_ROLL_HZ = 3f

/** Clamps a possibly non-finite glitch input to 0..1, same sanitizing MorphDirector applies. */
internal fun sanitizedGlitch(glitch: Float): Float =
    if (glitch.isFinite()) glitch.coerceIn(0f, 1f) else 0f

/** True while the pass would draw anything, matching MorphDirector's own floor (see there). */
internal fun isGlitchVisible(amount: Float): Boolean = amount >= MorphDirector.VISIBLE_EPSILON

/**
 * Steps glitch is snapped to before it reaches the shader's uniform and the renderer's cache key,
 * so a ~120 ms decay allocates roughly a dozen native filters instead of one every frame. Round-
 * to-nearest keeps the last non-zero step (1/64) sub-pixel: a lift of 0.14/64 and a displacement
 * of 0.03*w/64, both well under one pixel, so the quantised decay cannot end in a visible pop.
 */
internal const val GLITCH_QUANT_STEPS = 64

internal fun quantiseGlitch(glitch: Float): Float =
    round(sanitizedGlitch(glitch) * GLITCH_QUANT_STEPS) / GLITCH_QUANT_STEPS

/**
 * Whether this frame gets the offscreen layer and its native filter at all. Judged on the
 * quantised value, the one the shader is handed: the raw tail of a decay rounds to strength 0.
 */
internal fun runsPicturePass(amount: Float): Boolean = isGlitchVisible(quantiseGlitch(amount))
