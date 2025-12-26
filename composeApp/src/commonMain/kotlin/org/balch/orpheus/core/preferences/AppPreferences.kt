package org.balch.orpheus.core.preferences

import kotlinx.serialization.Serializable

@Serializable
data class AppPreferences(
    val lastVizId: String? = null,
    val lastPresetName: String? = null
)
