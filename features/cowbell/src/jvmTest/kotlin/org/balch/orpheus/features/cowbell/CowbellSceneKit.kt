package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.features.cowbell.motion.JoltWindow
import org.balch.orpheus.features.cowbell.motion.MotionSample
import org.balch.orpheus.features.cowbell.motion.MotionSource
import org.balch.orpheus.ui.panels.LocalCompactMode
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.assertNotNull

/** Records every gesture call the panel makes, in order. */
internal class RecordingActions {
    val calls = mutableListOf<String>()
    val strikes = mutableListOf<Float>()
    val strikePoints = mutableListOf<Offset>()
    val bends = mutableListOf<Float>()
    val modes = mutableListOf<CowbellMode>()
    val fills = mutableListOf<CowbellFill>()
    val motionAvailable = mutableListOf<Boolean>()
    val tilts = mutableListOf<Float>()
    val locks = mutableListOf<Float>()
    val mores = mutableListOf<Float>()

    val actions = CowbellPanelActions(
        onMetal = {}, onRing = {}, onIntensity = {}, onLevel = {}, onGridStrength = {},
        onEngine = {}, onOctave = {}, onGrid = {},
        onMode = { modes += it; calls += "mode" },
        onFill = { fills += it; calls += "fill" },
        onStrike = { v, x, y -> strikes += v; strikePoints += Offset(x, y); calls += "strike" },
        onHold = { _, _ -> calls += "hold" },
        onMove = { _, _ -> calls += "move" },
        onRelease = { calls += "release" },
        onBend = { bends += it; calls += "bend" },
        onBendDone = { calls += "bendDone" },
        releaseAll = { calls += "releaseAll" },
        onTilt = { tilts += it; calls += "tilt" },
        onMotionAvailable = { motionAvailable += it },
        onFillStrike = { calls += "fillStrike" },
        onLock = { locks += it; calls += "lock" },
        onBendScale = { calls += "bendScale" },
        onMore = { mores += it },
    )

    fun feature(state: CowbellUiState): CowbellFeature = object : CowbellFeature {
        override val stateFlow: StateFlow<CowbellUiState> = MutableStateFlow(state)
        override val actions: CowbellPanelActions = this@RecordingActions.actions
    }
}

/**
 * A lifecycle of the scene's own, RESUMED. The default owner is shared by every scene in the JVM,
 * and a closing scene's observer removal races the next scene's observer add. Even alone, a
 * scene adds observers from the test thread (a resume effect) and from the Swing thread
 * (`collectAsStateWithLifecycle`), and a registry is not safe for that, so every call is locked.
 */
private class SceneLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    override val lifecycle: Lifecycle = object : Lifecycle() {
        override val currentState: State get() = synchronized(registry) { registry.currentState }
        override val currentStateFlow get() = registry.currentStateFlow
        override fun addObserver(observer: LifecycleObserver) = synchronized(registry) { registry.addObserver(observer) }
        override fun removeObserver(observer: LifecycleObserver) = synchronized(registry) { registry.removeObserver(observer) }
    }
}

/** An off-screen scene on a hand-driven clock, at 1 px per dp, laid out as a slot lays out panels. */
@OptIn(ExperimentalComposeUiApi::class)
internal class CowbellScene(width: Int, height: Int, fontScale: Float = 1f, content: @Composable () -> Unit) : AutoCloseable {
    var now = 0L
        private set

    private val lifecycleOwner = SceneLifecycle()

    val scene = ImageComposeScene(width, height, Density(1f, fontScale)) {
        OrpheusTheme {
            CompositionLocalProvider(LocalCompactMode provides true, LocalLifecycleOwner provides lifecycleOwner) {
                Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) { content() }
            }
        }
    }

    init {
        repeat(2) { frame() }
    }

    fun frame(ms: Long = 16) {
        now += ms
        scene.render(now * 1_000_000)
    }

    fun frames(totalMs: Long) {
        var left = totalMs
        while (left > 0) { frame(16); left -= 16 }
    }

    fun pointer(
        type: PointerEventType,
        at: Offset,
        button: PointerButton? = null,
        pointerType: PointerType = PointerType.Mouse,
        stepMs: Long = 8,
        buttons: PointerButtons? = null,
    ) {
        scene.sendPointerEvent(type, at, timeMillis = now, type = pointerType, buttons = buttons, button = button)
        frame(stepMs)
    }

    fun press(at: Offset, button: PointerButton = PointerButton.Primary) = pointer(PointerEventType.Press, at, button)
    fun move(at: Offset) = pointer(PointerEventType.Move, at)
    fun release(at: Offset, button: PointerButton = PointerButton.Primary) = pointer(PointerEventType.Release, at, button)

    fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.walk() }

    fun tagged(tag: String): SemanticsNode =
        assertNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, "nothing tagged $tag")

    fun bell(): Rect = tagged(CowbellBellTag).boundsInRoot

    fun save(name: String) = runCatching {
        val dir = File("build/cowbell-panel").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(scene.render(now * 1_000_000).encodeToData()!!.bytes)
    }

    override fun close() = scene.close()
}

internal fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }

internal fun SemanticsNode.text(): String =
    config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }

/** Where the node lays out, unclipped: a clipped boundsInRoot would hide an overflow. */
internal val SemanticsNode.laidOut: Rect
    get() = Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)

/** A motion source that counts how the panel starts and stops it. */
internal class FakeMotion(
    override val available: Boolean = true,
    override val jolts: JoltWindow = JoltWindow(capacity = 1),
) : MotionSource {
    var starts = 0
    var stops = 0
    val running get() = starts > stops

    override fun start() { starts++ }

    override fun stop() { stops++ }

    override fun read(into: MotionSample) = into.rest()
}

/** The node or one of its ancestors is disabled: a pill's text sits below its button. */
internal fun SemanticsNode.isDisabled(): Boolean {
    var node: SemanticsNode? = this
    while (node != null) {
        if (SemanticsProperties.Disabled in node.config) return true
        node = node.parent
    }
    return false
}

/** The text drew wider than its box. A clipped label still reports a whole-looking node size. */
internal fun SemanticsNode.textOverflows(): Boolean = textWidthShortfall() > 0.5f

/** How many px the text wants beyond the width it was given, 0 when it fits. */
internal fun SemanticsNode.textWidthShortfall(): Float {
    val results = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    return results.maxOfOrNull { it.multiParagraph.maxIntrinsicWidth - size.width }?.coerceAtLeast(0f) ?: 0f
}
