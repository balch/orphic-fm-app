package org.balch.orpheus.features.ai

/**
 * State for AI features (Drone and Solo modes).
 * 
 * @param isDroneActive Whether the Drone AI accompaniment is active
 * @param isSoloActive Whether the Solo AI mode is active
 */
data class AiFeatureState(
    val isDroneActive: Boolean = false,
    val isSoloActive: Boolean = false
)
