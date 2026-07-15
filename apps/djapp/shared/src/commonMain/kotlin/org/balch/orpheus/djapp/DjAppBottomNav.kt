package org.balch.orpheus.djapp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.time.Duration

/**
 * Alpha applied to the selected-tab indicator pill. Kept low so the pill reads as a
 * neon wash behind the icon/label rather than a solid block — the hand-set icon and
 * text tints (see [DjAppNavScaffold]) already carry the actual selection signal.
 */
private const val NavIndicatorAlpha = 0.16f

/**
 * Selected-tab indicator color for both the bottom bar and the rail. M3's default
 * indicator (`colorScheme.secondaryContainer`) renders invisible against our
 * transparent nav background in dark posture, and baseline lavender under a light
 * system theme — a deliberate neon-cyan wash replaces it instead.
 */
private val NavIndicatorColor = OrpheusColors.neonCyan.copy(alpha = NavIndicatorAlpha)

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
    timerFeature: TimerFeature,
    onTogglePlayback: () -> Unit,
    tabs: List<DjRoute> = djTabs,
    onOpenSheet: (DjRoute) -> Unit = {},
    openSheetRoute: DjRoute? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layoutType = if (isLandscape) NavigationSuiteType.NavigationRail
                     else NavigationSuiteType.NavigationBar

    val pulsarState by pulsarFeature.stateFlow.collectAsState()
    val timerState by timerFeature.stateFlow.collectAsState()
    // Insert play/pause in the center for portrait, at the end for landscape
    val playPauseIndex = if (isLandscape) tabs.size else tabs.size / 2

    // NavigationSuiteScaffold has no scaffold-level default-item-colors hook in this
    // M3 version — NavigationSuiteScope.item() takes its own `colors`, so this is
    // built once here and passed to every item() call below. Only the indicator is
    // overridden; icon/text colors are left at their theme defaults since the icon
    // and label content already sets an explicit tint/color that takes precedence
    // over whatever NavigationBarItemColors/NavigationRailItemColors would resolve.
    val navItemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(indicatorColor = NavIndicatorColor),
        navigationRailItemColors = NavigationRailItemDefaults.colors(indicatorColor = NavIndicatorColor),
    )

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
                onClick = onTogglePlayback,
                icon = { Icon(playIcon, contentDescription = playLabel, tint = OrpheusColors.cosmicPurple) },
                label = { Text(playLabel, style = MaterialTheme.typography.labelSmall, color = OrpheusColors.cosmicPurple) },
                colors = navItemColors,
            )

            tabs.forEachIndexed { index, route ->
                if (index == playPauseIndex) addPlayPause()

                val selected = if (route.opensAsSheet) route == openSheetRoute
                               else currentRoute == route
                item(
                    selected = selected,
                    onClick = {
                        if (route.opensAsSheet) onOpenSheet(route) else onRouteSelected(route)
                    },
                    icon = {
                        val showCountdown = route is TimerTab
                            && (timerState.status == TimerStatus.RUNNING
                                || timerState.status == TimerStatus.PAUSED)
                        if (showCountdown) {
                            val baseAlpha =
                                if (timerState.status == TimerStatus.PAUSED) 0.45f
                                else runningAlphaPulse()
                            Box(
                                modifier = Modifier,
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = formatNavCountdown(timerState.remainingTime),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = OrpheusColors.sleepMoonlight.copy(alpha = baseAlpha),
                                )
                            }
                        } else {
                            Icon(
                                imageVector = route.icon,
                                contentDescription = route.label,
                                tint = if (selected) OrpheusColors.neonCyan
                                       else Color.White.copy(alpha = 0.6f),
                            )
                        }
                    },
                    label = {
                        Text(
                            text = route.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) OrpheusColors.neonCyan
                                    else Color.White.copy(alpha = 0.6f),
                        )
                    },
                    colors = navItemColors,
                )
            }
            // Landscape: play/pause at the end of the rail
            if (playPauseIndex == tabs.size) addPlayPause()
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = Color.Transparent,
            navigationRailContainerColor = Color.Transparent,
        ),
        content = content,
    )
}

/**
 * Format a countdown for the 24dp nav icon slot. Unit suffix makes each
 * bucket self-describing:
 *   - >= 1h: "H:MM"  (e.g. "1:07")
 *   - >= 1m: "Nm"    (e.g. "42m")
 *   - <  1m: "Ns"    (e.g. "42s")
 */
private fun formatNavCountdown(remaining: Duration): String {
    val totalSeconds = remaining.inWholeSeconds.coerceAtLeast(0L)
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val totalMinutes = totalSeconds / 60L
    if (totalMinutes < 60L) return "${totalMinutes}m"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val mm = if (minutes < 10L) "0$minutes" else "$minutes"
    return "$hours:$mm"
}

/**
 * Alpha oscillator that mirrors the flip-clock colon pulse (1-second
 * reversing tween between 0.7 and 1.0).
 */
@Composable
private fun runningAlphaPulse(): Float {
    val transition = rememberInfiniteTransition(label = "navCountdownPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "navCountdownAlpha",
    )
    return alpha
}
