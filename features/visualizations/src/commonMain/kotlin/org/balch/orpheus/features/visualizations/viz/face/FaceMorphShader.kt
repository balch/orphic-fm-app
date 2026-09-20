package org.balch.orpheus.features.visualizations.viz.face

/**
 * One source for SkSL (JVM, iOS, WASM) and AGSL (Android 13+); the dialects agree on everything
 * used here. Each pixel flips from faceA to faceB when t passes its own threshold, so the new
 * face erupts in patches instead of ghosting through as a double exposure.
 */
object FaceMorphShaderSource {
    const val SKSL = """
uniform shader faceA;
uniform shader faceB;
uniform shader bias;
uniform float2 resolution;
uniform float2 imageSize;
uniform float2 biasSize;
uniform float t;
uniform float flicker;
uniform float glitch;
uniform float crt;
uniform float mono;
uniform float time;

// Sin-free hash: fract(sin(...)) loses precision on mobile GPUs as the argument grows with time.
float hash(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, float3(p3.y, p3.z, p3.x) + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}

float fbm(float2 p) {
    return 0.65 * vnoise(p) + 0.35 * vnoise(p * 2.1 + 7.3);
}

// Low threshold turns early. The slow drift keeps patch borders crawling while t holds still.
float thresholdAt(float2 uv) {
    float n = fbm(uv * 6.0 + float2(time * 0.05, -time * 0.03));
    float b = 1.0 - bias.eval(clamp(uv, 0.0, 1.0) * biasSize).r;
    return mix(n, b, 0.65);
}

// Two copies on purpose: child shaders cannot be passed as function parameters.
half3 sampleA(float2 uv, float split) {
    return half3(faceA.eval((uv + float2(split, 0.0)) * imageSize).r,
                 faceA.eval(uv * imageSize).g,
                 faceA.eval((uv - float2(split, 0.0)) * imageSize).b);
}

half3 sampleB(float2 uv, float split) {
    return half3(faceB.eval((uv + float2(split, 0.0)) * imageSize).r,
                 faceB.eval(uv * imageSize).g,
                 faceB.eval((uv - float2(split, 0.0)) * imageSize).b);
}

half4 main(float2 fragCoord) {
    // Fit, not cover, so the whole head stays framed on both wide and portrait screens.
    // Keyframe backgrounds are near black, so the letterboxed edge disappears into the
    // vignette below instead of showing bars.
    float scale = min(resolution.x / imageSize.x, resolution.y / imageSize.y) * $FACE_ZOOM;
    float2 uv = (fragCoord - 0.5 * resolution) / (imageSize * scale) + 0.5;

    // Widened past 0..1 so the soft band still reaches all-B at t=1; at t=0 this only reaches
    // all-A when flicker is 0, since flicker deliberately pushes the dissolve ahead.
    float k = clamp(t + flicker * 0.35, 0.0, 1.0) * 1.16 - 0.08;
    float th = thresholdAt(uv);
    float edge = smoothstep(th - 0.08, th + 0.08, k);
    float band = edge * (1.0 - edge) * 4.0;

    // Skin pulls toward the advancing border. Forward difference reuses th instead of a second
    // pair of thresholdAt evaluations, halving the noise cost per fragment.
    float e = 0.004;
    float2 grad = float2(thresholdAt(uv + float2(e, 0.0)) - th, thresholdAt(uv + float2(0.0, e)) - th);
    float2 w = uv + normalize(grad + float2(1e-5, 1e-5)) * band * 0.006;

    float row = floor(w.y * 90.0);
    // Capped at 3 Hz, same photosensitivity bound as the kick flash: this is a large-area change.
    w.x += step(0.92, hash(float2(row, floor(time * 3.0)))) * glitch * 0.03;

    float split = glitch * 0.004;
    half3 c = mix(sampleA(w, split), sampleB(w, split), half(edge));
    c += half3(0.10, 0.02, 0.04) * half(band);

    // Luminance-preserving grey, lifted slightly in contrast so the skull still reads flat.
    half g = dot(c, half3(0.299, 0.587, 0.114));
    c = mix(c, half3(clamp(g * 1.15 - 0.04, 0.0, 1.0)), half(mono));

    c *= half(1.0 - crt * 0.35 * (0.5 + 0.5 * sin(fragCoord.y * 3.14159)));
    c *= half(1.0 - smoothstep(0.38, 0.85, distance(uv, float2(0.5, 0.5))));

    // The keyframe's backdrop is a shade lighter than black, so fade out before its own edge
    // shows, rather than let the CLAMP-tiled edge column meet the vignette as a visible seam.
    float2 f = smoothstep(float2(0.0, 0.0), float2(0.14, 0.14), uv) *
               smoothstep(float2(0.0, 0.0), float2(0.14, 0.14), float2(1.0, 1.0) - uv);
    c *= half(f.x * f.y);
    return half4(c, 1.0);
}
"""
}
