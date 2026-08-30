package org.balch.orpheus.djapp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface DjRoute : NavKey {
    val icon: ImageVector
    val label: String
    /** When true, the nav item opens an overlay sheet instead of navigating to an in-place destination. */
    val opensAsSheet: Boolean get() = false
}

@Serializable
data object DjTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.Album
    override val label: String = "DJ"
}

@Serializable
data object TimerTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.Timer
    override val label: String = "Timer"
}

@Serializable
data object MixTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.Tune
    override val label: String = "Mix"
}

@Serializable
data object HornTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.SurroundSound
    override val label: String = "Horn"
}

@Serializable
data object AiTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.AutoAwesome
    override val label: String = "AI"
    override val opensAsSheet: Boolean = true
}

/**
 * The Vibe Info overlay's route. On phone/tablet it is title-triggered and opens as a sheet
 * (never listed in [djTabs] or as a [DjTabContribution]) — it participates in the host's single
 * "which sheet is open" state so it shares open/replace/dismiss logic with tab-sheet
 * contributions there. On TV it is instead a genuine dock toggle, same as DJ/Mix/Horn/Timer/Ends
 * (see [largeScreenPanels]) — [opensAsSheet] only governs the phone/tablet nav path.
 */
@Serializable
data object VibeInfoTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.Info
    override val label: String = "Vibe Info"
    override val opensAsSheet: Boolean = true
}

/**
 * Pulsar's route. Only listed in the rail in [DjLayoutMode.LargeScreen] — the other two
 * layouts render Pulsar unconditionally, so it is not a destination they can navigate to.
 */
@Serializable
data object PulsarTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.GridView
    override val label: String = "Pulsar"
}

/**
 * The vibe-ending picker's route. A first-class TV-dockable panel (see [EndsPanel]) — the
 * bottom bar's "Ends" item is a dock toggle like DJ/Mix/Horn/Timer, not a sheet trigger. Not a
 * nav tab — never in [djTabs] — since it only exists on the TV/LargeScreen layout; phone/tablet
 * reach the same controls via Pulsar's own ENDING pill instead.
 */
@Serializable
data object EndsTab : DjRoute {
    // Chequered flag reads as "finish" at a glance; plain Flag doesn't.
    override val icon: ImageVector get() = Icons.Rounded.SportsScore
    override val label: String = "Ends"
}

val djTabs: List<DjRoute> = listOf(DjTab, MixTab, HornTab, TimerTab)

/**
 * Panels that can dock in TV mode, in rail order. Tab-contribution sheet routes (e.g. AiTab) are
 * excluded: they stay phone/tablet overlays rather than becoming docked panels. [VibeInfoTab] and
 * [EndsTab] are TV-only dock toggles by construction (never in [djTabs]), so both are appended
 * explicitly rather than needing to survive the sheet filter.
 */
fun largeScreenPanels(tabs: List<DjRoute> = djTabs): List<DjRoute> =
    listOf(PulsarTab) + tabs.filterNot { it.opensAsSheet } + VibeInfoTab + EndsTab
