package org.balch.orpheus.features.visualizations.viz.shader

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
    val threshold: Float = 1.0f,      // Surface threshold
    val glowIntensity: Float = 0.4f,  // Glow effect strength
    val blendSoftness: Float = 0.5f   // Color blend smoothness
)

/**
 * SKSL shader source for metaballs rendering with 3D depth and optimized normals.
 */
object MetaballsShaderSource {
    
    const val SKSL_SOURCE = """
uniform float2 resolution;
uniform float time;
uniform int ballCount;
uniform float threshold;
uniform float glowIntensity;
uniform float lfoMod;
uniform float masterEnergy;

uniform float4 balls[16];
uniform float4 colors[16];

// RGB to HSV conversion
float3 rgb2hsv(float3 c) {
    float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
    float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

// HSV to RGB conversion
float3 hsv2rgb(float3 c) {
    float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    float3 p = abs(fract(float3(c.x, c.x, c.x) + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    uv.y = 1.0 - uv.y;
    
    float totalInfluence = 0.0;
    float3 colorSum = float3(0.0);
    float weightSum = 0.0;
    float2 gradient = float2(0.0); // Analytical gradient
    
    // Single pass calculation of field AND gradient
    for (int i = 0; i < 16; i++) {
        if (i >= ballCount) break;
        
        float2 ballPos = balls[i].xy;
        float radius = balls[i].z;
        float energy = balls[i].w;
        
        if (radius < 0.001 || energy < 0.001) continue;
        
        float2 dv = uv - ballPos;
        float d2 = dot(dv, dv);
        float r2 = radius * radius;
        
        // Influence curve: f(r) = (1 - (d/R)^2)^3
        // Gradient: f'(r) = -6/R^2 * (1 - (d/R)^2)^2 * vector(uv - pos)
        if (d2 < r2) {
            float ratio2 = d2 / r2;
            float g = 1.0 - ratio2;
            float g2 = g * g;
            float influence = g2 * g * energy;
            
            totalInfluence += influence;
            
            // Analytical gradient contribution
            // The -6 factor is omitted here as we normalize the normal anyway
            gradient += (g2 * energy / r2) * dv;
            
            float3 ballColor = colors[i].rgb;
            float weight = influence * colors[i].a;
            colorSum += ballColor * weight;
            weightSum += weight;
        }
    }
    
    // DARKNESS AT REST: Threshold check
    if (totalInfluence < 0.005) {
        return float4(0.0);
    }
    
    // Normalize color
    float3 baseColor = (weightSum > 0.001) ? colorSum / weightSum : float3(0.0);
    
    // Calculate normal from analytical gradient
    // We add a Z component to create the "thickness" of the blob
    // Higher Z scale = flatter blobs, Lower Z scale = rounder/bubblier
    float3 normal = normalize(float3(gradient.x, gradient.y, 0.025));
    
    // Lighting
    float3 lightDir = normalize(float3(0.4, 0.4, 1.0));
    float3 viewDir = float3(0.0, 0.0, 1.0);
    
    float ambient = 0.2;
    float diffuse = max(dot(normal, lightDir), 0.0) * 0.8;
    float3 halfDir = normalize(lightDir + viewDir);
    float specular = pow(max(dot(normal, halfDir), 0.0), 32.0) * 0.7;
    float rim = pow(1.0 - max(dot(normal, viewDir), 0.0), 3.0) * 0.5;
    
    // Color effects
    if (abs(lfoMod) > 0.01) {
        float3 hsv = rgb2hsv(baseColor);
        hsv.x = fract(hsv.x + lfoMod * 0.1);
        hsv.y = clamp(hsv.y * (1.2 + lfoMod * 0.3), 0.0, 1.0);
        baseColor = hsv2rgb(hsv);
    } else {
        float3 hsv = rgb2hsv(baseColor);
        hsv.y = clamp(hsv.y * 1.3, 0.0, 1.0); // Vivid saturate
        baseColor = hsv2rgb(hsv);
    }
    
    // Combine lighting
    float3 finalColor = baseColor * (ambient + diffuse + rim) + float3(specular);
    
    // Master energy boost - keep it higher for color retention
    finalColor *= (0.8 + masterEnergy * 0.4);
    
    // Alpha blending
    float alpha = smoothstep(threshold * 0.05, threshold * 0.4, totalInfluence);
    float glow = smoothstep(0.0, threshold, totalInfluence) * glowIntensity * 0.5;
    alpha = max(alpha, glow);
    
    return float4(clamp(finalColor, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
}
"""
}
