package org.balch.orpheus.djapp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
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

val djTabs: List<DjRoute> = listOf(DjTab, MixTab, HornTab, TimerTab)
