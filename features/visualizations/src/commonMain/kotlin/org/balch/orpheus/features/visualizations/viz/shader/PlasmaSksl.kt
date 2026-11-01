package org.balch.orpheus.features.visualizations.viz.shader

/**
 * The Orphogram and Orphograph shaders: loud regions melt into glossy plasma (metaball-style
 * dome shading, rim glow, a halo outside the surface) over a dark void with faint drifting
 * nebula and stars. Colour follows pitch: bass indigo and violet, mids hot pink, highs electric
 * blue and cyan, with a cyan rim light. Valid as SkSL and AGSL.
 */
object PlasmaSksl {

    /** Field level at which the plasma surface sits. */
    const val SURFACE = 0.5f

    private const val COMMON = """
uniform float2 resolution;
uniform float time;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}

float fbm(float2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return s;
}

// 0 = lowest pitch, 1 = highest: indigo bass through violet and hot pink to electric blue and cyan highs.
float3 pitchHue(float t) {
    float s = clamp(t, 0.0, 1.0) * 5.0;
    if (s < 1.0) return mix(float3(0.15, 0.0, 0.6), float3(0.5, 0.1, 1.0), s);
    if (s < 2.0) return mix(float3(0.5, 0.1, 1.0), float3(0.8, 0.2, 1.0), s - 1.0);
    if (s < 3.0) return mix(float3(0.8, 0.2, 1.0), float3(1.0, 0.2, 0.8), s - 2.0);
    if (s < 4.0) return mix(float3(1.0, 0.2, 0.8), float3(0.2, 0.6, 1.0), s - 3.0);
    return mix(float3(0.2, 0.6, 1.0), float3(0.0, 1.0, 1.0), s - 4.0);
}

// The void: near-black violet, slow nebula drift, a sparse twinkle of stars.
float3 voidBg(float2 uv, float2 fragCoord) {
    float n = fbm(uv * float2(3.0, 2.0) + float2(time * 0.02, 0.0));
    float3 c = mix(float3(0.008, 0.004, 0.03), float3(0.06, 0.02, 0.12), n * n);
    c += float3(0.02, 0.05, 0.12) * smoothstep(0.55, 0.9, fbm(uv * 6.0 - time * 0.01));
    float2 cell = floor(fragCoord * 0.5);
    float star = step(0.997, hash(cell)) * (0.5 + 0.5 * sin(time * 2.0 + hash(cell + 3.1) * 30.0));
    return c + star * 0.35;
}

// Lit plasma for a surface with gradient (gx, gy) (field units per pixel) and depth past the surface.
float3 plasma(float3 hue, float gx, float gy, float depth) {
    float dome = sqrt(clamp(depth / 0.4, 0.0, 1.0));
    float3 n = normalize(float3(-gx * 9.0, -gy * 9.0, 0.22 + dome));
    float3 L = normalize(float3(-0.4, 0.7, 0.6));
    float diff = max(dot(n, L), 0.0);
    // A broad sheen and a faint bounce light follow the curve; a tight highlight drew a white dot.
    float3 r = reflect(float3(0.0, 0.0, -1.0), n);
    float sheen = pow(max(dot(r, L), 0.0), 8.0) * 0.45;
    float bounce = pow(max(dot(r, normalize(float3(0.6, -0.5, 0.6))), 0.0), 6.0) * 0.25;
    float rim = (1.0 - n.z) * (1.0 - n.z);
    float3 white = mix(float3(1.0), hue, 0.5);
    // The rim light leans cyan, so every tube carries a blue edge like a plasma globe's.
    return hue * (0.25 + 0.8 * diff) + mix(hue, float3(0.2, 0.9, 1.0), 0.6) * rim * 1.2 + hue * bounce + white * sheen + white * pow(dome, 4.0) * 0.3;
}

// Plasma texture at p (view heights): x = the bright writhing filaments of a plasma globe, y = a
// soft mottle between them. Both drift slowly with time, so the surface is never still.
float2 plasmaTex(float2 p) {
    float2 q = p * 5.0 + float2(time * 0.12, -time * 0.08);
    float n1 = fbm(q);
    float n2 = fbm(q * 2.3 + float2(5.2, 1.3) + float2(-time * 0.1, time * 0.15));
    // Ridges where the noise crosses its middle: thin veins, a finer web between them.
    float vein = pow(1.0 - abs(2.0 * n1 - 1.0), 7.0);
    float web = pow(1.0 - abs(2.0 * n2 - 1.0), 9.0) * 0.6;
    return float2(max(vein, web), n1);
}

// How the mottle tilts the surface at p, as an extra (gx, gy): the lit skin wrinkles with it.
float2 plasmaBump(float2 p) {
    float2 q = p * 5.0 + float2(time * 0.12, -time * 0.08);
    float2 e = float2(0.06, 0.0);
    return float2(vnoise(q + e) - vnoise(q - e), vnoise(q + e.yx) - vnoise(q - e.yx)) * 0.5;
}
"""

    /**
     * The scrolling spectrogram. `level` holds each row's brightness and `field` the same
     * blurred down the column, both [ringSize] with the highest frequency on the top row; `head`
     * is the ring column under the left edge, fractional so the scroll is smooth.
     */
    const val ORPHOGRAM = COMMON + """
uniform float head;
uniform float2 ringSize;
uniform shader level;
uniform shader field;

float2 texel(float2 uv) {
    float x = head + uv.x * (ringSize.x - 2.0);
    return float2(x - ringSize.x * floor(x / ringSize.x), (1.0 - uv.y) * ringSize.y);
}

float fieldAt(float2 uv, float dx) {
    return 0.4 * field.eval(texel(uv)).a
        + 0.3 * (field.eval(texel(uv - float2(dx, 0.0))).a + field.eval(texel(uv + float2(dx, 0.0))).a);
}

half4 main(float2 fragCoord) {
    float2 uv = float2(fragCoord.x / resolution.x, 1.0 - fragCoord.y / resolution.y);
    float2 px = 1.0 / resolution;
    float dx = 3.0 * px.x;
    float f = fieldAt(uv, dx);
    float3 hue = pitchHue(uv.y);
    float2 p = float2(uv.x * resolution.x / resolution.y, uv.y);
    float2 tex = plasmaTex(p);
    // The halo outside the surface carries a faint trace of the filaments.
    float3 col = voidBg(uv, fragCoord) + hue * smoothstep(0.2, 0.5, f) * (0.3 + 0.25 * tex.x);
    if (f > 0.47) {
        float gx = (fieldAt(uv + float2(2.0 * px.x, 0.0), dx) - fieldAt(uv - float2(2.0 * px.x, 0.0), dx)) * 0.25;
        float gy = (fieldAt(uv + float2(0.0, 2.0 * px.y), dx) - fieldAt(uv - float2(0.0, 2.0 * px.y), dx)) * 0.25;
        float depth = smoothstep(0.5, 0.9, f);
        // The skin wrinkles with the mottle, most where the plasma is deep and the dome flat.
        float2 bump = plasmaBump(p) * (0.03 + 0.05 * depth);
        float3 surf = plasma(hue, gx + bump.x, gy + bump.y, f - 0.5);
        // Mottled skin, and filaments glowing from inside, brighter the deeper the plasma.
        surf *= 0.8 + 0.3 * tex.y;
        surf += mix(float3(1.0), hue, 0.5) * tex.x * (0.25 + 0.45 * depth);
        // White-hot cores where the raw level runs at the peak: the sustained partials.
        surf += float3(1.0, 0.97, 1.0) * smoothstep(0.86, 1.0, level.eval(texel(uv)).a) * 0.55;
        col = mix(col, surf, smoothstep(0.48, 0.52, f));
    }
    return half4(half3(col), 1.0);
}
"""

    /**
     * The bar analyzer: [BANDS] glossy plasma columns that melt into their neighbours, each
     * slow-falling peak a droplet above its column. Heights and peaks are 0..1 of the view.
     */
    const val BANDS = 40

    const val ORPHOGRAPH = COMMON + """
uniform float bands[40];
uniform float peaks[40];

// 0.5 on a shape's edge, rising to 1 inside and falling to 0 outside, over a band `soft` wide;
// tails sum into goo.
float blob(float d, float soft) { return 1.0 / (1.0 + exp(d / soft)); }

// One capsule column and its peak droplet at p, in units of view height (x stretched by the aspect).
// The softness follows the column's width, so narrow screens don't melt the columns into a wall.
float columnAt(float2 p, float aspect, float i, float h, float pk) {
    float cx = (i + 0.5) / 40.0 * aspect;
    float r = 0.22 / 40.0 * aspect;
    float soft = min(0.004, r * 0.3);
    float s = 0.0;
    if (h > 0.004) s += blob(length(float2(p.x - cx, p.y - clamp(p.y, 0.0, h))) - r, soft);
    if (pk > 0.01) s += blob(length(p - float2(cx, pk + 0.035)) - r * 0.8, soft);
    return s;
}

// The molten pool the columns rise from, deeper under loud bands.
float poolAt(float2 p, float level) { return blob(p.y - 0.02 - 0.09 * level, 0.004); }

// Eases overlapping shapes toward 1 past the surface, so a column standing in the pool doesn't
// leave a steep seam inside it to catch the specular.
float settle(float f) { return f < 0.5 ? f : 1.0 - 0.5 * exp(-2.0 * (f - 0.5)); }

half4 main(float2 fragCoord) {
    float2 uv = float2(fragCoord.x / resolution.x, 1.0 - fragCoord.y / resolution.y);
    float aspect = resolution.x / resolution.y;
    float2 p = float2(uv.x * aspect, uv.y);
    float e = 1.5 / resolution.y;
    float band = uv.x * 40.0;
    float f = 0.0;
    float fx = 0.0;
    float fy = 0.0;
    float level = 0.0;
    float weight = 0.0;
    for (int i = 0; i < 40; i++) {
        float fi = float(i);
        float off = fi + 0.5 - band;
        if (abs(off) > 2.5) continue;
        float h = bands[i] * 0.85;
        float pk = peaks[i] * 0.85;
        f += columnAt(p, aspect, fi, h, pk);
        fx += columnAt(p + float2(e, 0.0), aspect, fi, h, pk);
        fy += columnAt(p + float2(0.0, e), aspect, fi, h, pk);
        float w = exp(-off * off);
        level += h * w;
        weight += w;
    }
    level /= weight;
    f += poolAt(p, level);
    fx += poolAt(p + float2(e, 0.0), level);
    fy += poolAt(p + float2(0.0, e), level);
    f = settle(f);
    fx = settle(fx);
    fy = settle(fy);
    float3 hue = pitchHue(uv.x);
    float3 col = voidBg(uv, fragCoord) + hue * smoothstep(0.15, 0.5, f) * 0.35;
    if (f > 0.47) {
        float3 surf = plasma(hue, (fx - f) * 0.75, (fy - f) * 0.75, f - 0.5);
        col = mix(col, surf, smoothstep(0.48, 0.52, f));
    }
    return half4(half3(col), 1.0);
}
"""
}
