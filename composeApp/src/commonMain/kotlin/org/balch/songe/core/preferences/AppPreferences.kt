package org.balch.songe.core.preferences

import kotlinx.serialization.Serializable

@Serializable
data class AppPreferences(
    val lastVizId: String? = null
)
