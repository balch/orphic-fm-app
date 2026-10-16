package org.balch.orpheus.features.mediapipe

import androidx.compose.runtime.Composable

/**
 * Returns a check, safe to call while leaving composition, for whether the host is being rebuilt
 * for a configuration change (rotation, fold) rather than going away. Only Android rebuilds its
 * host this way; everywhere else the answer is always `false`.
 */
@Composable
expect fun rememberConfigurationChangeCheck(): () -> Boolean
