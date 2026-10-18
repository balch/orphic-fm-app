package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/**
 * Renders [PanelNavigationLayout] with fake panels and a live reducer to PNGs for visual
 * inspection. Asserts nothing, so a headless machine without skia natives cannot fail the build.
 *
 * ./gradlew :apps:orpheus:shared:jvmTest --tests '*NavigationLayoutRenderHarness*' --rerun
 */
class NavigationLayoutRenderHarness {

    private val outDir get() = File("build/orpheus-nav-layout-render").apply { mkdirs() }

    private fun fakePanel(id: PanelId, label: String, color: Color): FeaturePanel =
        featurePanelPreview(
            panelId = id,
            label = label,
            color = color,
            description = "$label controls",
        ) { modifier, isExpanded, onExpandedChange, _ ->
            CollapsibleColumnPanel(
                modifier = modifier,
                title = label,
                expandedTitle = label,
                color = color,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(4) {
                            Box(Modifier.size(48.dp).background(color.copy(alpha = 0.55f), CircleShape))
                        }
                    }
                    Text("Row ${row + 1} knobs", color = Color.White, fontSize = 11.sp)
                }
            }
        }

    // REC and a few others are left out, so the rail, strip and search show only these.
    private val panels: Map<PanelId, FeaturePanel> = listOf(
        fakePanel(PanelId.PULSAR, "Pulsar", OrpheusColors.cosmicPurple),
        fakePanel(PanelId.BEATS, "Beats", OrpheusColors.neonOrange),
        fakePanel(PanelId.DRUMS, "808", OrpheusColors.neonMagenta),
        fakePanel(PanelId.BASS, "Bass", OrpheusColors.electricBlue),
        fakePanel(PanelId.LFO, "Duo LFO", OrpheusColors.synthGreen),
        fakePanel(PanelId.TIDES, "Waves", OrpheusColors.neonCyan),
        fakePanel(PanelId.EVO, "Evo", OrpheusColors.evoGold),
        fakePanel(PanelId.DISTORTION, "Volume", OrpheusColors.warmGlow),
        fakePanel(PanelId.DELAY, "Delay", OrpheusColors.electricBlue),
        fakePanel(PanelId.REVERB, "Reverb", OrpheusColors.krakenTeal),
        fakePanel(PanelId.GRAINS, "Grains", OrpheusColors.grainsRed),
        fakePanel(PanelId.PRESETS, "Presets", OrpheusColors.patchOrange),
        fakePanel(PanelId.MIDI, "MIDI", OrpheusColors.steelGrey),
        fakePanel(PanelId.VIZ, "Viz", OrpheusColors.vizGreen),
    ).associateBy { it.panelId }

    /** Restored slots opened oldest first at capacity 0, so the reducer trims to the measured capacity. */
    private fun seeded(railVisible: Boolean?): HeaderPanelUiState {
        val slots = listOf(PanelId.LFO, PanelId.TIDES, PanelId.REVERB, PanelId.DELAY, PanelId.DISTORTION, PanelId.PULSAR)
            .fold(PanelSlots()) { s, id -> s.open(id, ::isWidePanel) }
        return reduceHeader(HeaderPanelUiState(), HeaderIntent.Restore(slots, railVisible, recentsStripVisible = true))
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        railVisible: Boolean? = null,
        stripVisible: Boolean = true,
        search: SearchRequest? = null,
        restored: Boolean = true,
    ) {
        val scale = 1.5f
        var state by mutableStateOf(
            reduceHeader(seeded(railVisible), HeaderIntent.SetRecentsStripVisible(stripVisible))
                .let { if (restored) it else it.copy(restored = false) },
        )
        fun send(intent: HeaderIntent) {
            state = reduceHeader(state, intent)
        }
        val actions = HeaderPanelActions(
            setExpanded = { id, open -> send(if (open) HeaderIntent.Open(id) else HeaderIntent.Close(id)) },
            open = { send(HeaderIntent.Open(it)) },
            close = { send(HeaderIntent.Close(it)) },
            togglePin = { send(HeaderIntent.TogglePin(it)) },
            touch = { send(HeaderIntent.Touch(it)) },
            setCapacity = { send(HeaderIntent.SetCapacity(it)) },
            setRailVisible = { send(HeaderIntent.SetRailVisible(it)) },
            setRecentsStripVisible = { send(HeaderIntent.SetRecentsStripVisible(it)) },
        )
        val dialogFlags = mutableListOf<Boolean>()
        runCatching {
            val scene = ImageComposeScene((widthDp * scale).toInt(), (heightDp * scale).toInt(), Density(scale)) {
                OrpheusTheme {
                    val nav = rememberPanelNavState(state, actions)
                    LaunchedEffect(Unit) { search?.let { nav.openSearch(it.autoFocus) } }
                    // Shaped like DesktopSynthScreen: the layout over a voices area, search over both.
                    Box(Modifier.fillMaxSize().background(OrpheusColors.darkVoid)) {
                        Column(Modifier.fillMaxSize()) {
                            PanelNavigationLayout(
                                panels = panels,
                                state = state,
                                actions = actions,
                                nav = nav,
                                onDialogActiveChange = { dialogFlags += it },
                                modifier = Modifier.fillMaxWidth().weight(0.75f),
                            )
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("VOICES AND TITLE", color = OrpheusColors.slateGrey, fontSize = 14.sp)
                            }
                        }
                        nav.searchRequest?.let { request ->
                            val catalog = rememberPanelNavCatalog(panels)
                            PanelSearchOverlay(
                                candidates = catalog.candidates,
                                colors = catalog.colors,
                                autoFocus = request.autoFocus,
                                onPick = {},
                                onDismiss = {},
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                }
            }
            try {
                // Measure the layout, then the slot area, then apply the capacity and draw.
                repeat(5) { scene.render(1_000_000_000L + it * 16_000_000L).close() }
                File(outDir, "$name.png").writeBytes(scene.render(1_200_000_000L).encodeToData()!!.bytes)
                val slots = state.slots
                println(
                    "[nav-render] $name capacity=${slots.capacity} " +
                        "open=${slots.open.map { "${it.id.id}x${it.units}" }} " +
                        "recents=${slots.recents.map { it.id }} dialogFlags=$dialogFlags",
                )
            } finally {
                scene.close()
            }
        }.onFailure { println("[nav-render] $name skipped: $it") }
    }

    @Test
    fun fold() = render(name = "nav-fold", widthDp = 850, heightDp = 420)

    @Test
    fun foldRail() = render(name = "nav-fold-rail", widthDp = 850, heightDp = 420, railVisible = true)

    @Test
    fun foldHandle() = render(name = "nav-fold-handle", widthDp = 850, heightDp = 420, stripVisible = false)

    @Test
    fun desktop() = render(name = "nav-desktop", widthDp = 1900, heightDp = 420)

    /** Whole-window search, as the screen hoists it: the scrim covers the voices too. */
    @Test
    fun search() = render(
        name = "nav-search-1280x800",
        widthDp = 1280,
        heightDp = 800,
        search = SearchRequest(autoFocus = true),
    )

    /** Before the restore lands nothing renders and no capacity is sent. */
    @Test
    fun unrestored() = render(name = "nav-unrestored", widthDp = 1280, heightDp = 420, restored = false)
}
