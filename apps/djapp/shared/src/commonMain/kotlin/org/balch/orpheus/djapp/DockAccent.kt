package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects

/**
 * The visualization's accent for the dock's draws. Some visualizations change it every frame: read
 * in composition it recomposes whatever wears it, read through here in a draw, a [ColorProducer][androidx.compose.ui.graphics.ColorProducer]
 * or a layer block it only redraws that. The local's value is a snapshot state, so the read is observed there.
 */
@Stable
internal class DockAccent {
    internal var node: DockAccentNode? = null

    /** The accent now; unspecified until the modifier it was handed to is attached. */
    fun color(): Color = node?.accent() ?: Color.Unspecified
}

@Composable
internal fun rememberDockAccent(): DockAccent = remember { DockAccent() }

/** Where [accent] reads from: this node's place in the composition, so on the root of whatever draws with it. */
internal fun Modifier.dockAccent(accent: DockAccent): Modifier = this then DockAccentElement(accent)

private data class DockAccentElement(val accent: DockAccent) : ModifierNodeElement<DockAccentNode>() {
    override fun create() = DockAccentNode(accent)

    override fun update(node: DockAccentNode) = node.retarget(accent)
}

/**
 * An [icon] tinted in draw, as M3's Icon tints it in composition: a [tint] that follows the
 * accent every frame redraws the icon alone.
 */
@Composable
internal fun DockIcon(icon: ImageVector, contentDescription: String?, tint: ColorProducer, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(icon)
    Spacer(
        modifier
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics {
                    this.contentDescription = contentDescription
                    role = Role.Image
                },
            )
            .drawWithCache {
                // The filter is rebuilt only when the colour moves.
                var shown = Color.Unspecified
                var filter: ColorFilter? = null
                onDrawBehind {
                    val color = tint()
                    if (color != shown) {
                        shown = color
                        filter = if (color.isSpecified) ColorFilter.tint(color) else null
                    }
                    with(painter) { draw(size, colorFilter = filter) }
                }
            },
    )
}

internal class DockAccentNode(private var accent: DockAccent) : Modifier.Node(), CompositionLocalConsumerModifierNode {
    fun accent(): Color = currentValueOf(LocalLiquidEffects).title.titleColor

    fun retarget(to: DockAccent) {
        if (to === accent) return
        if (accent.node === this) accent.node = null
        accent = to
        if (isAttached) to.node = this
    }

    override fun onAttach() {
        accent.node = this
    }

    override fun onDetach() {
        if (accent.node === this) accent.node = null
    }
}
