package org.balch.orpheus.djapp.playlist

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.assertNotNull

/**
 * [PlaylistContent] on the sheet's surface and a virtual clock: frames, coroutines and pointer events
 * all advance together. Sizes are in dp.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaylistScene(
    initial: PlaylistView,
    initialCurrent: String,
    width: Int = 400,
    height: Int = 900,
    density: Float = 1f,
) {
    var view by mutableStateOf(initial)
    var current by mutableStateOf(initialCurrent)
    val edits = mutableListOf<PlaylistEdit>()
    private val scheduler = TestCoroutineScheduler()
    private var now = 0L
    private val scene = ImageComposeScene(
        (width * density).toInt(), (height * density).toInt(), Density(density), StandardTestDispatcher(scheduler),
    ) {
        OrpheusTheme {
            Surface(color = OrpheusColors.deepPurple, contentColor = OrpheusColors.onSurfaceDark) {
                PlaylistContent(view, current, "Outlook groovy", onEdit = { edits += it }, onShuffle = {}, onPlay = {})
            }
        }
    }

    init { idle(64) }

    fun idle(ms: Long) = repeat((ms / 16).toInt()) {
        now += 16
        scheduler.advanceTimeBy(16)
        scheduler.runCurrent()
        scene.render(now * 1_000_000)
    }

    /** Long enough for rows that changed place or left to finish animating. */
    fun settle() = idle(800)

    private fun nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(scene.semanticsOwners.first().rootSemanticsNode)
        return out
    }

    private fun described(prefix: String): List<SemanticsNode> = nodes().filter { n ->
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    private fun namesBy(prefix: String): List<String> = described(prefix).sortedBy { it.boundsInRoot.top }
        .map { it.config[SemanticsProperties.ContentDescription].first().removePrefix(prefix) }

    fun handleOf(name: String): Offset = assertNotNull(
        described("Reorder ").firstOrNull { it.config[SemanticsProperties.ContentDescription].contains("Reorder $name") },
    ).boundsInRoot.center

    /** The Up next names, top to bottom. */
    fun upNext(): List<String> = namesBy("Reorder ")

    /** The Set aside names, top to bottom. */
    fun setAside(): List<String> = namesBy("Bring back ")

    /** Whether some node reads exactly [text]. */
    fun shows(text: String): Boolean =
        nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

    /** Where the row naming [name] is drawn, mid-animation included. */
    fun rowTop(name: String): Float = assertNotNull(
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == name } == true },
        "no row for $name",
    ).boundsInRoot.top

    fun click(label: String) {
        val node = assertNotNull(
            nodes().firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                    n.config.getOrNull(SemanticsActions.OnClick) != null
            },
            "nothing clickable labelled \"$label\"",
        )
        node.config[SemanticsActions.OnClick].action?.invoke()
    }

    fun pointer(type: PointerEventType, at: Offset) {
        val button = if (type == PointerEventType.Press || type == PointerEventType.Release) PointerButton.Primary else null
        scene.sendPointerEvent(type, at, timeMillis = now, type = PointerType.Touch, button = button)
    }

    /** This frame as a PNG. */
    fun png(): ByteArray = assertNotNull(scene.render(now * 1_000_000).encodeToData()).bytes

    fun close() = scene.close()
}
