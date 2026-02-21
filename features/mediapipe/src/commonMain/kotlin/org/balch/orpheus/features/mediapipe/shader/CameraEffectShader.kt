package org.balch.orpheus.features.mediapipe.shader

/**
 * SKSL shader source for audio-reactive camera effects.
 *
 * The camera image is sampled through swirl, morph, blur, and dimming
 * effects driven by audio engine uniforms (master level, peak, LFO).
 *
 * Note: `.eval()` in SKSL is the standard built-in function for sampling
 * child shaders — it is NOT JavaScript eval().
 */
internal object CameraEffectShader {
    // language=SKSL
    const val SKSL_SOURCE: String = """
uniform shader cameraImage;
uniform float2 resolution;
uniform float time;
uniform float masterLevel;
uniform float peakLevel;
uniform float lfoMod;

half4 sampleCamera(float2 coord) {
    return cameraImage.eval(coord);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float2 center = float2(0.5, 0.5);

    // Boosted energy curve: engage effects sooner at low levels
    float energy = pow(masterLevel, 0.5);

    // --- Swirl: polar rotation around center, driven by energy * LFO ---
    float2 delta = uv - center;
    float dist = length(delta);
    float angle = atan(delta.y, delta.x);
    float swirlStrength = energy * lfoMod * 1.2;
    float swirlAtten = smoothstep(0.7, 0.0, dist);
    angle += swirlStrength * swirlAtten;
    float2 swirlUV = center + dist * float2(cos(angle), sin(angle));

    // --- Morph: sinusoidal UV displacement, driven by peak ---
    float morphAmp = max(peakLevel, energy * 0.6) * 0.04;
    float2 morphOffset = float2(
        sin(swirlUV.y * 15.0 + time * 3.0) * morphAmp,
        cos(swirlUV.x * 15.0 + time * 2.5) * morphAmp
    );
    float2 finalUV = swirlUV + morphOffset;

    // --- Blur: 9-tap box blur, radius scales with energy ---
    float blurRadius = energy * 10.0;
    half4 color = half4(0.0);
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            float2 offset = float2(float(dx), float(dy)) * blurRadius;
            color += sampleCamera(finalUV * resolution + offset);
        }
    }
    color /= 9.0;

    // --- Color shift: hue-rotate toward warm tones at high energy ---
    float shift = energy * 0.15;
    color.r += shift * 0.5;
    color.g -= shift * 0.2;

    // --- Dimming: base visibility + audio-responsive boost ---
    float brightness = 0.25 + 0.75 * pow(masterLevel, 0.6);
    color.rgb *= brightness;

    return color;
}
"""
}
