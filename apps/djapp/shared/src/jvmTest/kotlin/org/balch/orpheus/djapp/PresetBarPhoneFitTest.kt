package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.djapp.vibepresets.PresetRow
import org.balch.orpheus.djapp.vibepresets.VibePresetBar
import org.balch.orpheus.djapp.vibepresets.VibePresetsUiState
import org.balch.orpheus.djapp.vibepresets.VibePresetsViewModel
import org.balch.orpheus.djapp.vibepresets.rememberPresetsTransportLead
import org.balch.orpheus.features.horn.HornViewModel
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.mixer.MixerFeature
import org.balch.orpheus.features.pulsar.mixer.MixerPanelActions
import org.balch.orpheus.features.pulsar.mixer.MixerUiState
import org.balch.orpheus.features.pulsar.mixer.TrackTuning
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone Portrait screen with the real preset bar under the panels: the bar's height comes out
 * of the lower slot, so Mix (both views, RESET row included) and Horn must still fit it whole.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*PresetBarPhoneFitTest*'
 */
class PresetBarPhoneFitTest {

    private class Size(val width: Int, val height: Int, val fontScale: Float = 1f) {
        override fun toString() = "${width}x$height" + if (fontScale != 1f) "@$fontScale" else ""
    }

    // Two bar rows (dots, then name and age) is the bar at its tallest.
    private val presets = VibePresetsViewModel.previewFeature(
        VibePresetsUiState(
            available = true,
            barShown = true,
            vibeName = "A Whale's Dream",
            presets = listOf(PresetRow(1, "Quiet Night", 0L), PresetRow(2, "Deep Bass", 0L), PresetRow(3, "Loud", 0L)),
            selectedId = 2,
            changed = true,
        ),
    )

    // A touched mix, so the RESET pill shows in the toggle row.
    private val mixer = object : MixerFeature {
        override val stateFlow = MutableStateFlow(MixerUiState(tuning = TrackTuning().toggleMute(2)))
        override val actions = MixerPanelActions(setGroupGain = { _, _ -> }, setDrive = {})
    }

    private fun scene(size: Size, route: DjRoute, barShown: Boolean = true) =
        ImageComposeScene(size.width, size.height, Density(1f, size.fontScale)) {
            OrpheusTheme {
                CompositionLocalProvider(LocalShowTuning provides true) {
                    val pulsar = PulsarViewModel.previewFeature()
                    val engine = RenderProbeSynthEngine()
                    DjAppNavScaffold(
                        isSelected = { it == route },
                        onItemClick = {},
                        layout = DjLayout.Portrait,
                        pulsarFeature = pulsar,
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = {},
                        modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)),
                    ) {
                        DjAppMainContent(
                            layout = DjLayout.Portrait,
                            dockedPanels = emptyList(),
                            pairPanels = emptyList(),
                            pulsarFeature = pulsar,
                            synthEngine = engine,
                            vizFeature = VizViewModel.previewFeature(),
                            onShowVibeInfo = {},
                            routePanel = { _, _, _ -> },
                            // As DjAppScreen's NavDisplay hosts its destinations.
                            navContent = { mod ->
                                Box(mod) {
                                    val slot = Modifier.fillMaxSize().testTag(SlotTag)
                                    when (route) {
                                        MixTab -> MixRoutePanel(mixer, engine, slot, showTitle = false, fill = true)
                                        HornTab -> HornRoutePanel(
                                            synthEngine = engine,
                                            modifier = slot,
                                            showTitle = false,
                                            fill = true,
                                            lift = hornPillLift(DjLayout.Portrait, docked = false, barShown = barShown),
                                            feature = HornViewModel.previewFeature(),
                                        )
                                        else -> Unit
                                    }
                                }
                            },
                            // PRESETS in PERC's place, as the OG edition shows it.
                            pulsarTransportLead = rememberPresetsTransportLead(presets),
                            presetBar = { mod ->
                                if (barShown) VibePresetBar(presets, onManage = {}, modifier = mod.testTag(BarTag))
                            },
                        )
                    }
                }
            }
        }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> =
        semanticsOwners.flatMap { it.unmergedRootSemanticsNode.walk() }

    private fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }

    private fun SemanticsNode.description() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

    private fun SemanticsNode.name() = text().ifBlank { description().firstOrNull().orEmpty() }

    /** Where the node lays out, unclipped: a clipped boundsInRoot would hide an overflow. */
    private val SemanticsNode.laidOut: Rect
        get() = Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)

    private fun ImageComposeScene.tagged(tag: String): SemanticsNode =
        assertNotNull(nodes().firstOrNull { it.tag() == tag }, "nothing tagged $tag")

    private fun ImageComposeScene.tap(text: String) {
        val at = nodes().first { it.text() == text }.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, at); render()
        sendPointerEvent(PointerEventType.Release, at); render()
        repeat(2) { render() }
    }

    private fun save(scene: ImageComposeScene, name: String) = runCatching {
        val dir = File("build/preset-bar-fit").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(scene.render().encodeToData()!!.bytes)
    }

    /**
     * Every labelled node in the lower slot lays out whole, unclipped, inside the panel, which is
     * the slot less [lift] at its foot; and the slot ends above the bar.
     */
    private fun ImageComposeScene.assertSlotHoldsItsPanel(what: String, lift: Float = 0f) {
        val slotNode = tagged(SlotTag)
        val slot = slotNode.laidOut
        val panelBottom = slot.bottom - lift
        val bar = tagged(BarTag).laidOut
        val labelled = slotNode.walk().drop(1).filter { it.name().isNotBlank() }
        assertTrue(labelled.size > 4, "$what: only ${labelled.size} labelled nodes in the slot")
        val end = labelled.maxOf { it.laidOut.bottom } - slot.top
        println("[preset-bar-fit] $what: panel ${panelBottom - slot.top}dp, content ends at ${end}dp, bar ${bar.height}dp")
        assertTrue(slot.bottom <= bar.top + 0.5f, "$what: the slot $slot runs under the bar $bar")
        for (n in labelled) {
            val b = n.laidOut
            assertTrue(
                b.top >= slot.top - 0.5f && b.bottom <= panelBottom + 0.5f,
                "$what: '${n.name()}' $b runs past the ${panelBottom - slot.top}dp panel (content ends at ${end}dp)",
            )
            assertTrue(b.height > 4f, "$what: '${n.name()}' is squeezed to ${b.height}dp")
            assertTrue(n.boundsInRoot.height >= b.height - 0.5f, "$what: '${n.name()}' is clipped to ${n.boundsInRoot}")
        }
    }

    /** The bar's dots stay tappable: none of them under the dome or its name pill. */
    private fun ImageComposeScene.assertDotsClearTheTransport(what: String) {
        val transport = assertNotNull(
            nodes().firstOrNull { n -> n.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == "Next vibe" } },
            "$what: no vibe transport",
        ).boundsInRoot
        val dots = nodes().filter { n -> n.description().any { it.startsWith("Preset ") } }
        assertEquals(3, dots.size, "$what: the bar's dots")
        for (dot in dots) {
            assertTrue(!dot.boundsInRoot.overlaps(transport), "$what: ${dot.name()} ${dot.boundsInRoot} is under the transport $transport")
        }
    }

    @Test
    fun mixFitsUnderTheBarInBothViews() {
        for (size in Sizes) {
            val scene = scene(size, MixTab)
            try {
                // Pulsar's grid sizes from the slot it measured the frame before.
                repeat(3) { scene.render() }
                scene.assertDotsClearTheTransport("$size")
                // BAND first, as the tab opens; each view then reserves the other's height.
                listOf("BAND", "TRACKS", "BAND").forEachIndexed { i, view ->
                    if (i > 0) scene.tap(view)
                    save(scene, "mix-$view-$size")
                    scene.assertSlotHoldsItsPanel("Mix $view at $size")
                    assertTrue(scene.nodes().any { it.text() == "RESET" }, "Mix $view at $size: no RESET row")
                }
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun hornFitsUnderTheBar() {
        for (size in Sizes) {
            val scene = scene(size, HornTab)
            try {
                repeat(3) { scene.render() }
                save(scene, "horn-$size")
                scene.assertDotsClearTheTransport("$size")
                val lift = hornPillLift(DjLayout.Portrait, docked = false, barShown = true)
                scene.assertSlotHoldsItsPanel("Horn at $size", lift = lift.value)
            } finally {
                scene.close()
            }
        }
    }

    /**
     * The bar's height comes out of Pulsar's slot too. From about 700dp tall the macro knob labels
     * keep their full height; shorter screens (360x640, the closed Duo) squeeze them.
     */
    @Test
    fun pulsarKeepsItsMacroLabelsOnATypicalPhone() {
        for (size in listOf(Size(360, 720), Size(360, 780), Size(360, 840, fontScale = 1.3f))) {
            val scene = scene(size, MixTab)
            try {
                repeat(3) { scene.render() }
                for (label in listOf("ENERGY", "COMPLEXITY", "MOOD", "SPACE", "MIX")) {
                    val n = scene.nodes().first { it.text() == label }
                    assertTrue(
                        n.laidOut.height > 4f && n.boundsInRoot.height >= n.laidOut.height - 0.5f,
                        "$size: Pulsar's $label is squeezed or clipped: ${n.laidOut}, visible ${n.boundsInRoot}",
                    )
                }
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun hornDropsThePillLiftOnlyWhileTheBarShows() {
        assertEquals(HornPillClearance, hornPillLift(DjLayout.Portrait, docked = false, barShown = false))
        assertEquals(0.dp, hornPillLift(DjLayout.Portrait, docked = false, barShown = true))
        assertEquals(0.dp, hornPillLift(DjLayout.Portrait, docked = true, barShown = false))
        assertEquals(0.dp, hornPillLift(DjLayout.Landscape, docked = false, barShown = false))
    }

    private companion object {
        const val SlotTag = "lowerSlot"
        const val BarTag = "presetBar"

        val Sizes = listOf(
            Size(360, 640),
            // A closed iPhone Duo: 678pt less the 34pt home indicator.
            Size(466, 644),
            // The Fold 8's cover screen upright, at its 1.3 font scale.
            Size(360, 840, fontScale = 1.3f),
        )
    }
}
