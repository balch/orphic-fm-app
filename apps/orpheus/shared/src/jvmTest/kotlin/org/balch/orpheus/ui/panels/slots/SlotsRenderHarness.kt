package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.panels.HeaderPanelActions
import org.balch.orpheus.ui.panels.HeaderPanelUiState
import org.balch.orpheus.ui.panels.shrinksToScroll
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/**
 * Renders [PanelSlotsArea] with fake panels to PNGs for visual inspection. Asserts nothing, so a
 * headless machine without skia natives cannot fail the build.
 *
 * ./gradlew :apps:orpheus:shared:jvmTest --tests '*SlotsRenderHarness*' --rerun -PrenderHarness
 */
@OptIn(ExperimentalComposeUiApi::class)
class SlotsRenderHarness {

    private val outDir get() = File("build/orpheus-slots-render").apply { mkdirs() }

    private fun fakePanel(id: PanelId, label: String, color: Color, tall: Boolean = false): FeaturePanel =
        featurePanelPreview(panelId = id, label = label, color = color) { modifier, isExpanded, onExpandedChange, _ ->
            CollapsibleColumnPanel(
                modifier = modifier,
                title = label,
                expandedTitle = label,
                color = color,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                val rows = if (tall) 6 else 2
                if (tall) Text("TOP of a 500dp body", color = Color.White, fontSize = 12.sp)
                repeat(rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(4) {
                            Box(Modifier.size(48.dp).background(color.copy(alpha = 0.55f), CircleShape))
                        }
                    }
                    if (!tall) Text("Row ${row + 1} knobs", color = Color.White, fontSize = 11.sp)
                }
                if (tall) Text("BOTTOM of the tall body", color = Color.White, fontSize = 12.sp)
            }
        }

    /** A tall body inside a BoxWithConstraints, as Pulsar's grid and the DJ strip are. */
    private fun constraintsPanel(id: PanelId, label: String, color: Color): FeaturePanel =
        featurePanelPreview(panelId = id, label = label, color = color) { modifier, isExpanded, onExpandedChange, _ ->
            CollapsibleColumnPanel(
                modifier = modifier,
                title = label,
                expandedTitle = label,
                color = color,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cell = (maxWidth - 30.dp) / 4
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("TOP: BoxWithConstraints body", color = Color.White, fontSize = 12.sp)
                        repeat(4) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(4) {
                                    Box(Modifier.size(width = cell, height = 60.dp).background(color.copy(alpha = 0.55f)))
                                }
                            }
                        }
                        Text("BOTTOM: BoxWithConstraints body", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

    /** ASL Maestro's shape: a fill Box whose fill child is the VIZ/OFF field, with corner overlays. */
    private fun fillBoxPanel(id: PanelId, label: String, color: Color): FeaturePanel =
        featurePanelPreview(panelId = id, label = label, color = color) { modifier, isExpanded, onExpandedChange, _ ->
            CollapsibleColumnPanel(
                modifier = modifier,
                title = label,
                color = color,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                    Text("OFF field fills the body", color = color, fontSize = 12.sp)
                    Text(
                        "VIZ | OFF | CAM",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopStart).background(color.copy(alpha = 0.4f)).padding(4.dp),
                    )
                    Text(
                        "selection bar",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }
            }
        }

    /** DjPanel's shape: an outer Box(modifier) around the panel, with a background layer behind it. */
    private fun wrappedPanel(id: PanelId, label: String, color: Color): FeaturePanel =
        featurePanelPreview(panelId = id, label = label, color = color) { modifier, isExpanded, onExpandedChange, _ ->
            Box(modifier) {
                CollapsibleColumnPanel(
                    modifier = Modifier.fillMaxSize(),
                    title = label,
                    expandedTitle = "Itchy & Scratchy",
                    color = color,
                    isExpanded = isExpanded,
                    onExpandedChange = onExpandedChange,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(color.copy(alpha = 0.05f), color.copy(alpha = 0.45f))),
                            ),
                        )
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(2) { Box(Modifier.size(72.dp).background(color.copy(alpha = 0.55f), CircleShape)) }
                    }
                    Text("chrome and trace reach the slot bottom", color = Color.White, fontSize = 11.sp)
                }
            }
        }

    /** Mimics the Presets list: a vertical scroller with no height bound, which an unbounded measure would crash. */
    private fun selfScrollingPanel(id: PanelId, label: String, color: Color): FeaturePanel =
        featurePanelPreview(panelId = id, label = label, color = color) { modifier, isExpanded, onExpandedChange, _ ->
            CollapsibleColumnPanel(
                modifier = modifier,
                title = label,
                expandedTitle = label,
                color = color,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    repeat(30) { Text("Preset ${it + 1}", color = Color.White, fontSize = 12.sp) }
                }
            }
        }

    /** Mimics the live-code editor: a fill body whose own verticalScroll holds tall content. */
    private fun codeEditorPanel(id: PanelId, label: String, color: Color): FeaturePanel =
        featurePanelPreview(panelId = id, label = label, color = color) { modifier, isExpanded, onExpandedChange, _ ->
            CollapsibleColumnPanel(
                modifier = modifier,
                title = label,
                expandedTitle = label,
                color = color,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp)) {
                        Text("TOP: d1 \$ s \"bd sn\"", color = color, fontSize = 12.sp)
                        repeat(40) { Text("d${it + 2} \$ gates:4 5 6 7", color = Color.White, fontSize = 12.sp) }
                        Text("BOTTOM of the code", color = color, fontSize = 12.sp)
                    }
                }
            }
        }

    /** The live-code shape after the fix: a toolbar above a scroller marked [shrinksToScroll]. */
    private fun markedEditorPanel(id: PanelId, label: String, color: Color): FeaturePanel =
        featurePanelPreview(panelId = id, label = label, color = color) { modifier, isExpanded, onExpandedChange, _ ->
            CollapsibleColumnPanel(
                modifier = modifier,
                title = label,
                expandedTitle = label,
                color = color,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                Text("TOOLBAR stays put", color = color, fontSize = 12.sp)
                Box(Modifier.shrinksToScroll(96.dp).fillMaxSize().background(Color.Black)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp)) {
                        Text("TOP of the code", color = color, fontSize = 12.sp)
                        repeat(40) { Text("d${it + 2} \$ gates:4 5 6 7", color = Color.White, fontSize = 12.sp) }
                        Text("BOTTOM of the code", color = color, fontSize = 12.sp)
                    }
                }
            }
        }

    private val panels: Map<PanelId, FeaturePanel> = listOf(
        markedEditorPanel(PanelId.TIMER, "Marked", OrpheusColors.aiRepl),
        fakePanel(PanelId.PULSAR, "Pulsar", OrpheusColors.cosmicPurple),
        fakePanel(PanelId.DISTORTION, "Volume", OrpheusColors.warmGlow),
        fakePanel(PanelId.DELAY, "Delay", OrpheusColors.electricBlue),
        fakePanel(PanelId.REVERB, "Reverb", OrpheusColors.krakenTeal),
        fakePanel(PanelId.LFO, "Duo LFO", OrpheusColors.synthGreen, tall = true),
        constraintsPanel(PanelId.BEATS, "Grid", OrpheusColors.neonOrange),
        fillBoxPanel(PanelId.ASL_MAESTRO, "ASL", OrpheusColors.aiDrone),
        wrappedPanel(PanelId.DJ, "DJ", OrpheusColors.neonCyan),
        selfScrollingPanel(PanelId.PRESETS, "Presets", OrpheusColors.patchOrange),
        codeEditorPanel(PanelId.CODE, "Code", OrpheusColors.aiRepl),
    ).associateBy { it.panelId }

    private val entries = PanelNavEntries.associateBy { it.id }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        slots: PanelSlots,
        pulse: PanelPulse? = null,
        scrollAt: Offset? = null,
        rethrowLayoutErrors: Boolean = false,
    ) {
        val scale = 1.5f
        runCatching {
            val scene = ImageComposeScene(
                (widthDp * scale).toInt(),
                (heightDp * scale).toInt(),
                Density(scale),
            ) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize().background(OrpheusColors.darkVoid)) {
                        PanelSlotsArea(
                            panels = panels,
                            entries = entries,
                            state = HeaderPanelUiState(slots = slots),
                            actions = HeaderPanelActions.EMPTY,
                            pulse = pulse,
                            onDialogActiveChange = {},
                        )
                    }
                }
            }
            try {
                // First frame measures the area; later frames draw the slots and run the pulse.
                scene.render(1_000_000_000L).close()
                scene.render(1_016_000_000L).close()
                // A mouse wheel over the slot, in px, to show the overflow body really scrolls.
                if (scrollAt != null) {
                    repeat(40) {
                        scene.sendPointerEvent(
                            eventType = PointerEventType.Scroll,
                            position = scrollAt * scale,
                            scrollDelta = Offset(0f, 3f),
                        )
                    }
                    scene.render(1_032_000_000L).close()
                }
                File(outDir, "$name.png").writeBytes(scene.render(1_050_000_000L).encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure {
            // Missing skia natives is a LinkageError; a measure crash is a state or argument error.
            if (rethrowLayoutErrors && (it is IllegalStateException || it is IllegalArgumentException)) throw it
            println("[slots-render] $name skipped: $it")
        }
    }

    @Test
    fun fold2Slots() = render(
        name = "fold-2slots",
        widthDp = 850,
        heightDp = 400,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.DISTORTION, 1), OpenPanel(PanelId.DELAY, 1)),
            pinned = setOf(PanelId.DISTORTION),
            capacity = 2,
        ),
    )

    @Test
    fun desktop5Slots() = render(
        name = "desktop-5slots",
        widthDp = 1900,
        heightDp = 330,
        slots = PanelSlots(
            open = listOf(
                OpenPanel(PanelId.PULSAR, 2),
                OpenPanel(PanelId.DISTORTION, 1),
                OpenPanel(PanelId.DELAY, 1),
                OpenPanel(PanelId.REVERB, 1),
            ),
            pinned = setOf(PanelId.PULSAR),
            capacity = 5,
        ),
        pulse = PanelPulse(PanelId.DELAY, seq = 1),
    )

    @Test
    fun oneFree() = render(
        name = "onefree",
        widthDp = 850,
        heightDp = 400,
        slots = PanelSlots(open = listOf(OpenPanel(PanelId.REVERB, 1)), capacity = 2),
    )

    @Test
    fun tallOverflow() = render(
        name = "tall-overflow",
        widthDp = 850,
        heightDp = 250,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.LFO, 1), OpenPanel(PanelId.DELAY, 1)),
            capacity = 2,
        ),
    )

    @Test
    fun tallOverflowScrolled() = render(
        name = "tall-overflow-scrolled",
        widthDp = 850,
        heightDp = 250,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.LFO, 1), OpenPanel(PanelId.DELAY, 1)),
            capacity = 2,
        ),
        scrollAt = Offset(200f, 150f),
    )

    /** Intrinsics throw on a BoxWithConstraints body, so it keeps the viewport: no scroll, clips. */
    @Test
    fun constraintsFallbackClips() = render(
        name = "bwc-fallback-clips-no-scroll",
        widthDp = 850,
        heightDp = 250,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.BEATS, 1), OpenPanel(PanelId.PRESETS, 1)),
            capacity = 2,
        ),
        scrollAt = Offset(200f, 150f),
    )

    /** Bodies with their own verticalScroll in overflowing slots: they get a bounded measure and scroll. */
    @Test
    fun selfScrollingOverflow() = render(
        name = "self-scroll-overflow",
        widthDp = 850,
        heightDp = 250,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.PRESETS, 1), OpenPanel(PanelId.CODE, 1)),
            capacity = 2,
        ),
        rethrowLayoutErrors = true,
    )

    @Test
    fun selfScrollingOverflowScrolled() = render(
        name = "self-scroll-overflow-scrolled",
        widthDp = 850,
        heightDp = 250,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.PRESETS, 1), OpenPanel(PanelId.CODE, 1)),
            capacity = 2,
        ),
        scrollAt = Offset(625f, 150f),
        rethrowLayoutErrors = true,
    )

    /** A marked scroller fits the slot, so its toolbar stays while the inner list scrolls. */
    @Test
    fun markedScrollerKeepsToolbar() = render(
        name = "marked-scroller-keeps-toolbar",
        widthDp = 850,
        heightDp = 250,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.CODE, 1), OpenPanel(PanelId.TIMER, 1)),
            capacity = 2,
        ),
        scrollAt = Offset(625f, 170f),
        rethrowLayoutErrors = true,
    )

    @Test
    fun fillBodies() = render(
        name = "fill-bodies",
        widthDp = 850,
        heightDp = 400,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.ASL_MAESTRO, 1), OpenPanel(PanelId.DJ, 1)),
            capacity = 2,
        ),
    )

    @Test
    fun fillBodiesShort() = render(
        name = "fill-bodies-250",
        widthDp = 850,
        heightDp = 250,
        slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.ASL_MAESTRO, 1), OpenPanel(PanelId.DJ, 1)),
            capacity = 2,
        ),
    )

    /**
     * Clicks each 40dp header target in its 32dp band, where it overhangs the body by 8dp, and just
     * below it on the body. The calls land in this test's <system-out>.
     */
    @Test
    fun headerHitTargets() {
        val scale = 1.5f
        val calls = mutableListOf<String>()
        val actions = HeaderPanelActions.EMPTY.copy(
            close = { calls += "close(${it.id})" },
            togglePin = { calls += "togglePin(${it.id})" },
            touch = { calls += "touch(${it.id})" },
        )
        val slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.DISTORTION, 1), OpenPanel(PanelId.DELAY, 1)),
            capacity = 2,
        )
        runCatching {
            val scene = ImageComposeScene((850 * scale).toInt(), (400 * scale).toInt(), Density(scale)) {
                OrpheusTheme {
                    PanelSlotsArea(panels, entries, HeaderPanelUiState(slots = slots), actions, null, {})
                }
            }
            try {
                scene.render(1_000_000_000L).close()
                scene.render(1_016_000_000L).close()
                // Slot 0 spans 2..423dp x 2..398dp; close is the rightmost 40dp square, pin the one before it.
                val probes = listOf(
                    "close in band (403,18)" to Offset(403f, 18f),
                    "pin in band (363,18)" to Offset(363f, 18f),
                    "close overhang (403,40)" to Offset(403f, 40f),
                    "pin overhang (363,40)" to Offset(363f, 40f),
                    "body below targets (403,48)" to Offset(403f, 48f),
                )
                probes.forEachIndexed { i, (label, dp) ->
                    calls.clear()
                    val at = dp * scale
                    val t = 2_000L + i * 500L
                    scene.sendPointerEvent(
                        PointerEventType.Press, at, timeMillis = t,
                        buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
                    )
                    scene.sendPointerEvent(
                        PointerEventType.Release, at, timeMillis = t + 50,
                        buttons = PointerButtons(), button = PointerButton.Primary,
                    )
                    scene.render(1_100_000_000L + i * 100_000_000L).close()
                    println("[slots-hit] $label -> $calls")
                }
            } finally {
                scene.close()
            }
        }.onFailure { println("[slots-hit] skipped: $it") }
    }
}
