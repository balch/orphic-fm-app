package org.balch.orpheus.features.visualizations.viz.shader

/**
 * Mt Hood's lava: up to [MAX_BLOBS] blobs, each an oval stretched along its flow, melt into one
 * sticky molten surface that glows its own colour, with a hot core where it runs deep and a red
 * heat haze just outside. Transparent elsewhere, so it draws over the mountain. Valid as SkSL and AGSL.
 */
object MtHoodLavaSksl {

    const val MAX_BLOBS = 64

    // blobs[i] = (x, y, radius in pixels, stretch along the flow); tints[i] = (rgb 0..1, unused);
    // axes[i] = (cos, sin) of the flow angle. Only the first `count` are read.
    const val SOURCE = """
uniform float2 resolution;
uniform float count;
uniform float4 blobs[64];
uniform float4 tints[64];
uniform float2 axes[64];

half4 main(float2 p) {
    float f = 0.0;
    float2 grad = float2(0.0);
    float3 tint = float3(0.0);
    for (int i = 0; i < 64; i++) {
        if (float(i) >= count) break;
        float4 b = blobs[i];
        float2 o = p - b.xy;
        // Past three radii along the stretched axis the blob adds nothing, so skip the rotation.
        if (dot(o, o) > 9.0 * b.z * b.z * b.w * b.w) continue;
        float cs = axes[i].x;
        float sn = axes[i].y;
        // In the blob's own frame, the flow axis squeezed by its stretch.
        float2 d = float2((o.x * cs + o.y * sn) / b.w, -o.x * sn + o.y * cs);
        float d2 = dot(d, d) + 1.0;
        // r^2/d^2 less its value at three radii, so each blob fades to exactly zero.
        float v = b.z * b.z / d2 - 0.111;
        if (v <= 0.0) continue;
        f += v;
        float2 g = -2.0 * (v + 0.111) / d2 * float2(d.x / b.w, d.y);
        grad += float2(g.x * cs - g.y * sn, g.x * sn + g.y * cs);
        tint += v * tints[i].rgb;
    }
    if (f < 0.12) return half4(0.0);
    float3 c = tint / f;
    if (f < 1.0) {
        // Heat haze just outside the surface, redder than the lava.
        float a = (f - 0.12) / 0.88;
        a = a * a * 0.55;
        return half4(half3(c * float3(1.0, 0.45, 0.3) * a), half(a));
    }
    float3 n = normalize(float3(-grad / f * resolution.y * 0.02, 1.0));
    float3 L = normalize(float3(-0.4, -0.7, 0.6));
    float diff = max(dot(n, L), 0.0);
    // A broad sheen and a faint bounce light follow the curve; a tight highlight drew a white dot.
    float3 r = reflect(float3(0.0, 0.0, -1.0), n);
    float sheen = pow(max(dot(r, L), 0.0), 8.0) * 0.3;
    float bounce = pow(max(dot(r, normalize(float3(0.6, 0.5, 0.6))), 0.0), 6.0) * 0.25;
    float rim = (1.0 - n.z) * (1.0 - n.z);
    // Lava glows its own colour, lit a little from above, and runs hotter where it pools deep.
    float depth = min(1.0, (f - 1.0) / 1.4);
    float hot = depth * depth * 0.45;
    float3 col = c * (0.7 + 0.45 * diff) + float3(1.0, 0.75, 0.3) * hot * 0.5
        + c * (rim * 0.7 + bounce) + mix(float3(1.0, 0.9, 0.7), c, 0.5) * sheen;
    float a = 0.5 + 0.5 * min(1.0, (f - 1.0) / 0.08);
    return half4(half3(min(col, float3(1.0)) * a), half(a));
}
"""
}
