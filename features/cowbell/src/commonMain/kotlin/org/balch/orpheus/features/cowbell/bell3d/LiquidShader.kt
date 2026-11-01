package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.graphics.ShaderBrush

/**
 * Draws a [LiquidFrame] as one chrome body: the pool smooth-unioned with its drops.
 * Skiko targets use `RuntimeEffect`; Android uses AGSL `RuntimeShader` on API 33+.
 */
internal expect class LiquidRenderer() {
    fun isSupported(): Boolean

    /**
     * A brush in the frame's canvas px, or null when unsupported, the pool is degenerate or
     * `unit` is not positive. `unit` is canvas px per prototype px (`projector.scale / 125f`).
     */
    fun brush(unit: Float, timeSeconds: Float, frame: LiquidFrame): ShaderBrush?

    fun dispose()
}

internal const val LIQUID_POLY_MAX = 16
internal const val LIQUID_DROP_COUNT = 8
internal const val LIQUID_K = 24f
internal const val LIQUID_BEVEL = 30f

/**
 * The prototype's liquid fragment shader in SkSL, which AGSL also accepts. It runs in prototype
 * px (`fragCoord / unit`); anti-aliasing is at device px. `sdPoly` uses loop-index-only indexing for ES 2.
 */
internal const val LIQUID_SKSL: String = """
uniform float t;
uniform int np;
uniform float2 poly[16];
uniform float4 drops[8];
uniform float k;
uniform float bevel;
uniform float inflate;
uniform float unit;

float sdPoly(float2 p) {
    float d = dot(p - poly[0], p - poly[0]);
    float s = 1.;
    float2 last = poly[0];
    for (int i = 0; i < 16; i++) { if (i == np - 1) last = poly[i]; }
    float2 vj = last;
    for (int i = 0; i < 16; i++) {
        if (i >= np) break;
        float2 vi = poly[i];
        float2 e = vj - vi;
        float2 w = p - vi;
        float2 b = w - e * clamp(dot(w, e) / dot(e, e), 0., 1.);
        d = min(d, dot(b, b));
        bool3 c = bool3(p.y >= vi.y, p.y < vj.y, e.x * w.y > e.y * w.x);
        if (all(c) || all(not(c))) s *= -1.;
        vj = vi;
    }
    return s * sqrt(d);
}

float smin(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.) / k;
    return min(a, b) - h * h * k * .25;
}

float map(float2 p) {
    float d = sdPoly(p) - inflate;
    for (int i = 0; i < 8; i++) {
        float4 q = drops[i];
        if (q.z > .5) d = smin(d, length(p - q.xy) - q.z, k);
    }
    return d;
}

float3 pal(float h) {
    h = fract(h) * 4.;
    float f = smoothstep(0., 1., fract(h));
    float3 c0 = float3(1., .24, .94);
    float3 c1 = float3(.54, .36, 1.);
    float3 c2 = float3(.24, .96, 1.);
    float3 c3 = float3(.24, .55, 1.);
    float3 a;
    float3 b;
    if (h < 1.) { a = c0; b = c1; }
    else if (h < 2.) { a = c1; b = c2; }
    else if (h < 3.) { a = c2; b = c3; }
    else { a = c3; b = c0; }
    return mix(a, b, f);
}

float bev(float d, float b) {
    float z = clamp(-d / b, 0., 1.);
    return b * sqrt(1. - (1. - z) * (1. - z));
}

float smax(float a, float b, float k) { return -smin(-a, -b, k); }

// Rounded-edge pool for the body, true spheres for the drops: smooth tops, no cone tips.
float hgt(float2 p) {
    float h = bev(map(p), 7.);
    h = smax(h, bev(sdPoly(p) - inflate, bevel), 10.);
    for (int i = 0; i < 8; i++) {
        float4 q = drops[i];
        if (q.z > .5) {
            float2 e = p - q.xy;
            h = smax(h, sqrt(max(q.z * q.z - dot(e, e), 0.)), 10.);
        }
    }
    return h;
}

half4 main(float2 fragCoord) {
    float2 p = fragCoord / unit;
    float d = map(p);
    float h = hgt(p);
    float2 gh = float2(hgt(p + float2(1., 0.)) - h, hgt(p + float2(0., 1.)) - h);
    if (length(gh) > 5.) gh = normalize(gh) * 5.;
    float z = clamp(h / bevel, 0., 1.);
    float3 n = float3(-gh, 1.);
    n.xy += .06 * z * float2(sin(p.x * .05 + p.y * .02 + t * 2.6), cos(p.y * .045 - p.x * .015 - t * 2.1));
    n = normalize(n);
    float3 r = reflect(float3(0., 0., -1.), n);
    float3 tint = pal(p.y / 650. + p.x / 1100. + t * .05 + .08 * dot(r.xy, float2(.6, .4)));
    float3 L1 = normalize(float3(-.45, -.6, .66));
    float spec = pow(max(dot(r, L1), 0.), 8.) * .55;
    float bounce = pow(max(dot(r, normalize(float3(.6, .5, .6))), 0.), 6.) * .25;
    float dif = max(dot(n, L1), 0.);
    float3 col = tint * (.12 + .75 * dif) + mix(float3(1.), tint, .5) * spec + tint * (pow(1. - n.z, 2.) * .35 + bounce);
    float a = clamp(.5 - d * unit, 0., 1.);
    float glow = (1. - a) * exp(-max(d, 0.) / 9.) * .4;
    return half4(half3(col * a + tint * glow), half(a + glow));
}
"""
