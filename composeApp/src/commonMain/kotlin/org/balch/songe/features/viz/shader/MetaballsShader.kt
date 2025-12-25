package org.balch.songe.features.viz.shader

import androidx.compose.ui.graphics.Color

/**
 * Data class representing a metaball for shader rendering.
 */
data class MetaballData(
    val x: Float,           // 0-1 normalized X position
    val y: Float,           // 0-1 normalized Y position  
    val radius: Float,      // 0-1 normalized radius
    val color: Color,       // Ball color with alpha
    val energy: Float = 1f  // Energy/influence multiplier
)

/**
 * Configuration for the metaballs shader.
 */
data class MetaballsConfig(
    val maxBalls: Int = 16,           // Configurable max blobs
    val threshold: Float = 1.0f,      // Surface threshold (classic metaballs use ~1.0)
    val glowIntensity: Float = 0.4f,  // Glow effect strength
    val blendSoftness: Float = 0.5f   // Color blend smoothness
)

/**
 * SKSL shader source for metaballs rendering.
 * 
 * Classic metaballs algorithm:
 * - Each ball contributes influence = radius / distance
 * - Pixels are colored when total influence exceeds threshold
 * - Colors blend smoothly based on weighted influence
 */
object MetaballsShaderSource {
    
    /**
     * SKSL shader that renders metaballs with smooth blending.
     * 
     * Uniforms:
     * - resolution: Canvas size in pixels
     * - time: Animation time for effects
     * - ballCount: Number of active balls
     * - threshold: Surface detection threshold
     * - glowIntensity: Edge glow strength
     * - lfoMod: LFO modulation value (-1 to 1)
     * - masterEnergy: Overall brightness (0 to 1)
     * - balls[]: Array of ball data (x, y, radius, energy)
     * - colors[]: Array of RGBA colors for each ball
     */
    const val SKSL_SOURCE = """
uniform float2 resolution;
uniform float time;
uniform int ballCount;
uniform float threshold;
uniform float glowIntensity;
uniform float lfoMod;
uniform float masterEnergy;

// Ball data: x, y, radius, energy
uniform float4 balls[16];
// Color data: r, g, b, a
uniform float4 colors[16];

// HSV to RGB conversion for color effects
float3 hsv2rgb(float3 c) {
    float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    float3 p = abs(fract(float3(c.x, c.x, c.x) + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// RGB to HSV conversion
float3 rgb2hsv(float3 c) {
    float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
    float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

float4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    
    // Flip Y coordinate (0 = bottom in our coordinate system)
    uv.y = 1.0 - uv.y;
    
    float totalInfluence = 0.0;
    float3 blendedColor = float3(0.0);
    float totalWeight = 0.0;
    
    // Calculate metaball field
    for (int i = 0; i < 16; i++) {
        if (i >= ballCount) break;
        
        float2 ballPos = balls[i].xy;
        float radius = balls[i].z;
        float energy = balls[i].w;
        
        // Skip if no radius or very small
        if (radius < 0.005) continue;
        
        // Distance from pixel to ball center
        float dist = distance(uv, ballPos);
        
        // Classic metaball influence: radius / distance
        // This creates the characteristic "blobby" merging effect
        float influence = radius / max(dist, 0.001);
        influence *= energy;  // Scale by ball energy
        
        totalInfluence += influence;
        
        // Weight color contribution by influence squared for sharper color boundaries
        float3 ballColor = colors[i].rgb;
        float alpha = colors[i].a;
        float weight = influence * influence * alpha;
        
        blendedColor += ballColor * weight;
        totalWeight += weight;
    }
    
    // No influence at all - transparent
    if (totalInfluence < 0.01) {
        return float4(0.0, 0.0, 0.0, 0.0);
    }
    
    // Normalize blended color
    if (totalWeight > 0.001) {
        blendedColor /= totalWeight;
    } else {
        return float4(0.0, 0.0, 0.0, 0.0);
    }
    
    // Apply LFO-driven hue shift
    if (abs(lfoMod) > 0.01) {
        float3 hsv = rgb2hsv(blendedColor);
        hsv.x = fract(hsv.x + lfoMod * 0.08);  // Subtle hue shift
        hsv.y = clamp(hsv.y * (1.0 + lfoMod * 0.15), 0.0, 1.0);  // Saturation boost
        blendedColor = hsv2rgb(hsv);
    }
    
    // Increase color saturation for more vivid blobs
    float3 hsv = rgb2hsv(blendedColor);
    hsv.y = min(hsv.y * 1.3, 1.0);  // Boost saturation by 30%
    hsv.z = min(hsv.z * 1.1, 1.0);  // Slight brightness boost
    blendedColor = hsv2rgb(hsv);
    
    // Apply master energy as brightness multiplier
    blendedColor *= (0.7 + masterEnergy * 0.4);
    
    // Calculate alpha with threshold
    // Use sharp threshold for classic metaball look
    float coreAlpha = smoothstep(threshold * 0.7, threshold, totalInfluence);
    float glowAlpha = smoothstep(threshold * 0.3, threshold * 0.8, totalInfluence) * glowIntensity;
    
    float finalAlpha = coreAlpha + glowAlpha * (1.0 - coreAlpha);
    finalAlpha = clamp(finalAlpha, 0.0, 1.0);
    
    // Add glow around edges for depth
    if (totalInfluence > threshold * 0.5 && totalInfluence < threshold * 1.3) {
        float edgeFactor = smoothstep(threshold * 0.5, threshold * 0.9, totalInfluence) * 
                          (1.0 - smoothstep(threshold * 0.9, threshold * 1.3, totalInfluence));
        blendedColor += blendedColor * edgeFactor * glowIntensity * 0.8;
    }
    
    return float4(blendedColor, finalAlpha);
}
"""
}
