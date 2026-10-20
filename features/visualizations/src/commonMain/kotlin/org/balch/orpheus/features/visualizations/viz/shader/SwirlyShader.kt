package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.ui.graphics.ShaderBrush

const val SWIRLY_ARM_COUNT = 6

/** One frame of Swirly's arm uniforms, refilled in place on every draw. */
class SwirlyArms {
    var time = 0f
    var lfo = 0f
    /** Radians an arm turns between the rim and the core. */
    var sweep = 0f
    /** rgb + core alpha per arm. */
    val colors = FloatArray(SWIRLY_ARM_COUNT * 4)
    /** Core stroke width in px per arm; the glow is 2.5x as wide at a quarter of the alpha. */
    val widths = FloatArray(SWIRLY_ARM_COUNT)
}

/**
 * Skia RuntimeEffect on JVM, iOS and WASM; AGSL RuntimeShader on Android 13+.
 *
 * Stroking the arms as paths costs a CPU-rasterized mask per stroke on Android's Skia, redone for
 * every glass lens that replays the viz: ~250 ms frames on a Fold. The shader draws the same arms
 * in one GPU pass.
 */
expect class SwirlyRenderer() {
    /** Null where the shader can't run; the caller strokes the arms as paths instead. */
    fun brush(width: Float, height: Float, arms: SwirlyArms): ShaderBrush?
    fun dispose()
}

object SwirlyShaderSource {
    /**
     * Arm i follows r(t) = R(1 - 0.95t), theta(t) = phase_i + t * sweep for t in [0, 1], with the
     * LFO wobble on r. r falls monotonically with t, so a pixel's radius picks the winding it is
     * near and only the two windings either side need testing.
     */
    const val SKSL = """
uniform float2 resolution;
uniform float time;
uniform float lfo;
uniform float sweep;
uniform float4 armColor[6];
uniform float armWidth[6];

const float TAU = 6.2831853;

float armDistance(float2 p, float rho, float angle, float phase, float k, float maxR) {
    float t = (angle + TAU * k - phase) / sweep;
    float tc = clamp(t, 0.0, 1.0);
    float r = maxR * (1.0 - tc * 0.95);
    r += sin(tc * 8.0 + time * 2.0) * lfo * 0.03 * r;
    if (t == tc) {
        // p sits at this point's angle, so the gap is radial; the pitch turns it into a normal distance.
        float pitch = maxR * 0.95 / sweep / max(r, 1.0);
        return abs(rho - r) / sqrt(1.0 + pitch * pitch);
    }
    // Past either end: distance to the end point, which rounds the cap.
    float theta = phase + tc * sweep;
    return length(p - r * float2(cos(theta), sin(theta)));
}

float4 main(float2 fragCoord) {
    float maxR = min(resolution.x, resolution.y) * 0.7;
    float2 p = fragCoord - resolution * 0.5;
    float rho = length(p);
    float angle = atan(p.y, p.x);
    float tRho = (1.0 - rho / maxR) / 0.95;
    float4 outColor = float4(0.0);
    for (int arm = 0; arm < 6; arm++) {
        float phase = mod(float(arm) / 6.0 * TAU + time, TAU);
        float k = floor((phase + tRho * sweep - angle) / TAU);
        float d = min(
            armDistance(p, rho, angle, phase, k, maxR),
            armDistance(p, rho, angle, phase, k + 1.0, maxR)
        );
        float4 c = armColor[arm];
        float w = armWidth[arm];
        // Core then glow, each composited once, as the two stroked passes were.
        float core = clamp(w * 0.5 - d + 0.5, 0.0, 1.0) * c.a;
        outColor = float4(c.rgb * core, core) + outColor * (1.0 - core);
        float glow = clamp(w * 1.25 - d + 0.5, 0.0, 1.0) * c.a * 0.25;
        outColor = float4(c.rgb * glow, glow) + outColor * (1.0 - glow);
    }
    return outColor;
}
"""
}
