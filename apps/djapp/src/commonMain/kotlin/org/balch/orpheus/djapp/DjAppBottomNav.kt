package org.balch.orpheus.djapp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Adaptive navigation scaffold: bottom bar in portrait, side rail in landscape.
 * Includes a centered Play/Pause button that toggles global audio mute.
 */
@Composable
fun DjAppNavScaffold(
    currentRoute: DjRoute,
    onRouteSelected: (DjRoute) -> Unit,
    isLandscape: Boolean,
    pulsarFeature: PulsarFeature,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layoutType = if (isLandscape) NavigationSuiteType.NavigationRail
                     else NavigationSuiteType.NavigationBar

    val pulsarState by pulsarFeature.stateFlow.collectAsState()
    // Insert play/pause in the center for portrait, at the end for landscape
    val playPauseIndex = if (isLandscape) djTabs.size else djTabs.size / 2

    NavigationSuiteScaffold(
        modifier = modifier,
        layoutType = layoutType,
        containerColor = Color.Transparent,
        contentColor = Color.White,
        navigationSuiteItems = {
            val paused = pulsarState.globalPaused
            val playIcon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause
            val playLabel = if (paused) "Play" else "Pause"

            fun addPlayPause() = item(
                selected = false,
                onClick = pulsarFeature.actions.toggleGlobalPause,
                icon = { Icon(playIcon, contentDescription = playLabel, tint = OrpheusColors.cosmicPurple) },
                label = { Text(playLabel, style = MaterialTheme.typography.labelSmall, color = OrpheusColors.cosmicPurple) },
            )

            djTabs.forEachIndexed { index, route ->
                if (index == playPauseIndex) addPlayPause()

                val selected = currentRoute == route
                item(
                    selected = selected,
                    onClick = { onRouteSelected(route) },
                    icon = {
                        Icon(
                            imageVector = route.icon,
                            contentDescription = route.label,
                            tint = if (selected) OrpheusColors.neonCyan
                                   else Color.White.copy(alpha = 0.6f),
                        )
                    },
                    label = {
                        Text(
                            text = route.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) OrpheusColors.neonCyan
                                    else Color.White.copy(alpha = 0.6f),
                        )
                    },
                )
            }
            // Landscape: play/pause at the end of the rail
            if (playPauseIndex == djTabs.size) addPlayPause()
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = Color.Transparent,
            navigationRailContainerColor = Color.Transparent,
        ),
        content = content,
    )
}
