package org.balch.orpheus.djapp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
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
 * The Vibe Info overlay's route. Not a nav tab (never listed in [djTabs] and not a
 * [DjTabContribution]) — it is title-triggered — but it is a first-class member of the host's
 * single "which sheet is open" state so it participates in the same open/replace/dismiss logic
 * as tab-sheet contributions. Rendered via its dedicated composable, not generically.
 */
@Serializable
data object VibeInfoTab : DjRoute {
    override val icon: ImageVector get() = Icons.Rounded.Info
    override val label: String = "Vibe Info"
    override val opensAsSheet: Boolean = true
}

val djTabs: List<DjRoute> = listOf(DjTab, MixTab, HornTab, TimerTab)
