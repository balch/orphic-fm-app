package org.balch.orpheus.djapp

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Adaptive navigation scaffold: bottom bar in portrait, side rail in landscape.
 * [isLandscape] overrides the default window-based detection to ensure correct
 * layout on all platforms (JVM desktop may not report window size class correctly).
 */
@Composable
fun DjAppNavScaffold(
    currentRoute: DjRoute,
    onRouteSelected: (DjRoute) -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layoutType = if (isLandscape) NavigationSuiteType.NavigationRail
                     else NavigationSuiteType.NavigationBar

    NavigationSuiteScaffold(
        modifier = modifier,
        layoutType = layoutType,
        containerColor = Color.Transparent,
        contentColor = Color.White,
        navigationSuiteItems = {
            djTabs.forEach { route ->
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
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = Color.Transparent,
            navigationRailContainerColor = Color.Transparent,
        ),
        content = content,
    )
}
