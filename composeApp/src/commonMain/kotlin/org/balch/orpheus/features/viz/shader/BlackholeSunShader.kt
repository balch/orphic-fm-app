package org.balch.orpheus.features.viz.shader

import androidx.compose.ui.graphics.Color

/**
 * Data class representing a plasma emitter orbiting off-screen.
 */
data class PlasmaEmitterData(
    val angle: Float,       // Current angle position on border (radians)
    val energy: Float,      // 0-1 emission energy (from voice level)
    val trailLength: Float, // 0-1 trail length (from knob2)
    val color: Color,       // Emitter color
    val voiceIndex: Int,    // Which voice this emitter represents
    val orbitSpeed: Float = 1f  // Individual orbit speed multiplier
)

/**
 * Configuration for the plasma emitters shader.
 */
data class PlasmaEmittersConfig(
    val emitterCount: Int = 8,        // Number of emitters (one per voice)
    val glowIntensity: Float = 0.6f,  // Glow effect strength
    val streamWidth: Float = 0.015f,  // Base stream width
    val orbitRadius: Float = 0.65f    // Off-screen orbit radius (>0.5 = outside viewport)
)

/**
 * SKSL shader source for Blackhole Sun visualization.
 * Optimized: pre-computed values, reduced iterations, true black center.
 */
object BlackholeSunShaderSource {
    
    const val SKSL_SOURCE = """
uniform float2 resolution;
uniform float time;
uniform int emitterCount;
uniform float glowIntensity;
uniform float streamWidth;
uniform float orbitRadius;
uniform float lfoMod;
uniform float masterEnergy;
uniform float orbitSpeed;
uniform float trailLength;

uniform float4 emitters[8];
uniform float4 emitterColors[8];

// Event horizon
const float EVENT_HORIZON = 0.14;
const float BLACK_HOLE_RADIUS = 0.08;  // True black center

// Fast hash
float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float hash1(float n) {
    return fract(sin(n) * 43758.5453);
}

// Optimized simplex-ish noise
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n = i.x + i.y * 57.0;
    return mix(
        mix(hash1(n), hash1(n + 1.0), f.x),
        mix(hash1(n + 57.0), hash1(n + 58.0), f.x),
        f.y
    );
}

// Compact bolt glow
float boltGlow(float dist, float width) {
    float core = smoothstep(width, width * 0.15, dist);
    float glow = exp(-dist * 60.0);
    return core * 0.6 + glow * 0.8;
}

float4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / resolution.y;
    float2 centered = (uv - 0.5) * float2(aspect, 1.0);
    
    float distFromCenter = length(centered);
    float angleFromCenter = atan(centered.y, centered.x);
    
    float3 colorSum = float3(0.0);
    float alpha = 0.0;
    
    // Pre-compute total energy once
    float totalEnergy = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i < emitterCount) totalEnergy += emitters[i].y;
    }
    totalEnergy = min(totalEnergy * 0.25, 1.0);
    
    // Pre-compute common values
    float orbitSign = sign(orbitSpeed);
    float timeScaled = time * 0.5;
    
    // === BLACK HOLE CENTER - truly black ===
    float blackHoleMask = smoothstep(BLACK_HOLE_RADIUS, BLACK_HOLE_RADIUS * 0.3, distFromCenter);
    
    // Dark background (will be masked by black hole)
    float bgGrad = 1.0 - smoothstep(0.0, 0.45, distFromCenter);
    colorSum = float3(0.006, 0.003, 0.014) * (1.0 + bgGrad * 0.5);
    
    // === ACCRETION DISK with fuzzy edges ===
    float diskInner = EVENT_HORIZON;
    float diskOuter = EVENT_HORIZON + 0.14;
    
    if (distFromCenter > BLACK_HOLE_RADIUS && distFromCenter < diskOuter + 0.1) {
        // Noise-based edge distortion for organic shape
        float edgeNoise = noise(float2(angleFromCenter * 3.0, timeScaled)) * 0.04;
        float innerEdge = diskInner + edgeNoise - noise(float2(angleFromCenter * 5.0 + 1.0, timeScaled * 0.7)) * 0.025;
        float outerEdge = diskOuter + noise(float2(angleFromCenter * 4.0 - 2.0, timeScaled * 0.8)) * 0.05 - 0.02;
        
        float diskT = (distFromCenter - innerEdge) / (outerEdge - innerEdge);
        
        if (diskT > -0.1 && diskT < 1.2) {
            // Spiral patterns - simplified
            float spiral = sin(angleFromCenter * 4.0 - time * 2.2 + distFromCenter * 28.0) * 0.5 +
                          sin(angleFromCenter * 6.0 + time * 1.5 - distFromCenter * 22.0) * 0.3;
            
            // Disk intensity - glows with activity
            float diskIntensity = (0.15 + totalEnergy * 0.85) * (0.4 + masterEnergy * 0.6);
            
            // Fuzzy inner and outer edges
            float innerFade = smoothstep(-0.1, 0.15, diskT);
            float outerFade = smoothstep(1.1, 0.7, diskT);
            float edgeFade = innerFade * outerFade;
            
            // Additional noise fade at edges
            float noiseFade = noise(float2(angleFromCenter * 8.0, distFromCenter * 20.0 + timeScaled));
            edgeFade *= 0.6 + noiseFade * 0.4;
            
            float diskBrightness = (0.3 + smoothstep(-0.2, 0.8, spiral) * 0.7) * edgeFade * diskIntensity;
            
            // Hot horizon glow
            float horizonGlow = exp(-abs(distFromCenter - EVENT_HORIZON) * 20.0) * diskIntensity;
            diskBrightness += horizonGlow * 0.5;
            
            // Color shifts from purple (quiet) to orange-white (active)
            float3 diskColor = mix(
                float3(0.35, 0.12, 0.55),
                float3(0.95, 0.5, 0.15),
                totalEnergy
            );
            diskColor = mix(diskColor, float3(1.0, 0.95, 0.85), horizonGlow * 0.4);
            
            colorSum += diskColor * diskBrightness;
            alpha = max(alpha, diskBrightness * 0.35);
        }
    }
    
    // === BOLTS - optimized loop ===
    for (int i = 0; i < 8; i++) {
        if (i >= emitterCount) break;
        
        float energy = emitters[i].y;
        if (energy < 0.01) continue;
        
        float emitterAngle = emitters[i].x;
        float trail = emitters[i].z;
        float speedMult = emitters[i].w;
        float4 eCol = emitterColors[i];
        
        // Pre-compute emitter values
        float cosA = cos(emitterAngle);
        float sinA = sin(emitterAngle);
        float2 emitterPos = float2(cosA, sinA) * orbitRadius;
        float2 tangent = float2(-sinA, cosA) * orbitSign;
        float2 toCenter = -float2(cosA, sinA);  // Normalized toward center
        
        float baseSpeed = 0.7 + energy * 0.35;
        float invNumParticles = 1.0 / (3.0 + trail * 7.0);
        int numParticles = int(3.0 + trail * 7.0);
        
        // 5 bolts per voice
        for (int b = 0; b < 5; b++) {
            float boltSeed = float(i) * 6.7 + float(b) * 11.3;
            
            // Random parameters (computed once per bolt)
            float angleOff = (hash1(boltSeed) - 0.5) * 0.45;
            float speedOff = 0.75 + hash1(boltSeed + 1.0) * 0.5;
            float phaseOff = hash1(boltSeed + 2.0);
            float curveOff = (hash1(boltSeed + 3.0) - 0.5) * 0.7;
            float tangentW = 0.5 + hash1(boltSeed + 4.0) * 0.35;
            
            // Bolt starting position
            float bAngle = emitterAngle + angleOff * 0.28;
            float cosBa = cos(bAngle);
            float sinBa = sin(bAngle);
            float2 bPos = float2(cosBa, sinBa) * orbitRadius;
            float2 bTangent = float2(-sinBa, cosBa) * orbitSign;
            float2 bToCenter = -float2(cosBa, sinBa);
            float2 bDir = normalize(bTangent * tangentW + bToCenter * (1.0 - tangentW));
            
            // Curve perpendicular
            float2 perpDir = float2(-bDir.y, bDir.x);
            bDir = normalize(bDir + perpDir * curveOff * 0.25);
            
            for (int p = 0; p < 10; p++) {
                if (p >= numParticles) break;
                
                float t = float(p) * invNumParticles;
                float phase = fract(time * baseSpeed * speedOff + phaseOff - t * (0.45 - trail * 0.25));
                
                if (phase > trail * 0.7 + 0.35) continue;
                
                float travelTime = phase * 1.9;
                
                // Position calculation
                float2 pos = bPos + bDir * travelTime * (0.28 + speedOff * 0.08);
                
                // Gravity with curve
                float2 gravToCenter = -pos / max(length(pos), 0.01);
                float2 curvePerpDir = float2(-gravToCenter.y, gravToCenter.x);
                float2 curvedGrav = normalize(gravToCenter + curvePerpDir * curveOff * 0.35);
                
                float distToC = length(pos);
                float horizonF = smoothstep(EVENT_HORIZON * 0.7, EVENT_HORIZON * 2.2, distToC);
                pos += curvedGrav * travelTime * travelTime * 0.22 * horizonF;
                
                // Near horizon orbital blend
                float nearH = smoothstep(EVENT_HORIZON + 0.06, EVENT_HORIZON, length(pos));
                if (nearH > 0.2) {
                    float orbA = atan(pos.y, pos.x) + travelTime * orbitSign * 1.3 * nearH;
                    float orbD = max(length(pos), EVENT_HORIZON + 0.015);
                    pos = mix(pos, float2(cos(orbA), sin(orbA)) * orbD, nearH * 0.6);
                }
                
                float dist = length(centered - pos);
                if (dist > 0.15) continue;  // Early exit for distant particles
                
                float pSize = streamWidth * (0.35 + energy * 0.3) * (1.0 - phase * 0.18);
                float brightness = (0.5 + energy * 0.5) * (1.0 - phase * 0.5);
                float glow = boltGlow(dist, pSize) * brightness * glowIntensity * 1.4;
                
                colorSum += eCol.rgb * glow * eCol.a;
                alpha = max(alpha, glow * eCol.a * 0.4);
            }
        }
    }
    
    // Apply black hole mask - center is truly black
    colorSum *= (1.0 - blackHoleMask);
    
    // Subtle vignette
    float2 vUV = abs(uv - 0.5) * 2.0;
    colorSum *= 1.0 - dot(vUV, vUV) * 0.06;
    
    // Final brightness
    colorSum *= 1.15;
    
    return float4(clamp(colorSum, 0.0, 1.0), clamp(alpha + 0.94, 0.0, 1.0));
}
"""
}
