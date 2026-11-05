package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.cowbell.dj.CowbellDjPanel
import org.balch.orpheus.features.cowbell.CowbellUiState
import org.balch.orpheus.features.cowbell.CowbellViewModel
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The bell's test tag in :features:cowbell, where it is internal. */
private const val BellTag = "cowbell-bell"

private const val SlotTag = "cowbellSlot"

private const val DjFaderTag = "cowbell-dj-fader"

private const val CollapseTag = "cowbell-dj-collapse"

private const val BodyTag = "cowbell-body"
private const val ModeTag = "cowbell-ctl-mode"
private const val OctaveTag = "cowbell-ctl-octave"
private const val LevelTag = "cowbell-ctl-level"
/** Five 26dp chips and four 2dp gaps. */
private const val ChipsClosedUp = 138f
private const val LockTag = "cowbell-ctl-lock"
private const val SliderTag = "cowbell-ctl-slider"
private const val KnobsTag = "cowbell-dj-knobs"
private const val ToneTag = "cowbell-dj-knob-tone"
private const val RingTag = "cowbell-dj-knob-ring"
private const val IntensityTag = "cowbell-dj-knob-intensity"

/** What a rendered Cowbell panel laid out, measured from the nodes under [slot]. */
internal class CowbellFitReading(
    val slot: Rect,
    /** The panel's foot: the slot less the lift it sits up off the bottom bar. */
    val panelBottom: Float,
    val controlsBottom: Float,
    val bell: Rect?,
    /** The other three columns' widths, by tag. */
    val columnWidths: Map<String, Float>,
    val names: List<String>,
    val heights: List<Float>,
    /** Which of [names] are drawn text, as against a control's description. */
    val isText: List<Boolean>,
    /** Labelled nodes that lay out taller than they draw, so a parent clips them. */
    val clipped: List<String>,
    /** Labelled nodes whose text wants more width than it got: an ellipsized label keeps its height. */
    val overflowing: List<String>,
) {
    val labelled: Int get() = names.size
    val gap: Float get() = panelBottom - controlsBottom

    /**
     * Text drawn shorter than in [tall], the same panel with room to spare. A column short of
     * height hands its last children a squeezed or zero-height box rather than letting them
     * overflow, so the foot of the controls alone cannot tell a clipped panel. Matched by name:
     * the fader and the bell flex with the slot on purpose.
     */
    fun squeezedAgainst(tall: CowbellFitReading): List<String> {
        val tallHeights = tall.names.indices.filter { tall.isText[it] }.associate { tall.names[it] to tall.heights[it] }
        return names.indices
            .filter { isText[it] && tallHeights[names[it]] != null && heights[it] < tallHeights.getValue(names[it]) - 0.5f }
            .map { "${names[it]} ${heights[it]}dp of ${tallHeights.getValue(names[it])}dp" }
    }
}

private fun SemanticsNode.cowbellWalk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.cowbellWalk() }

private fun SemanticsNode.cowbellText(): String =
    config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }

private fun SemanticsNode.cowbellName(): String =
    cowbellText().ifBlank { config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().firstOrNull().orEmpty() }

/** Where the node lays out, unclipped: a clipped boundsInRoot would hide an overflow. */
private val SemanticsNode.cowbellLaidOut: Rect
    get() = Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)

/**
 * How many px the text wants beyond the width it was given, 0 when it fits. A whole pixel of slack
 * is allowed: at fractional font scales the measured width rounds down by one ("Tap ▾" at 1.15 is
 * 30.0 wanted against 29 laid out), and a real truncation costs a glyph, several px.
 */
private fun SemanticsNode.cowbellTextShortfall(): Float {
    val results = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    return results.maxOfOrNull { it.multiParagraph.maxIntrinsicWidth - size.width }?.let { if (it <= 1.5f) 0f else it } ?: 0f
}

internal fun ImageComposeScene.cowbellNodes(): List<SemanticsNode> =
    semanticsOwners.flatMap { it.unmergedRootSemanticsNode.cowbellWalk() }

/** Reads the panel tagged [tag], which sits [lift] px up off the foot of its slot. */
internal fun ImageComposeScene.readCowbellFit(tag: String, lift: Float): CowbellFitReading {
    val slotNode = assertNotNull(
        cowbellNodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag },
        "nothing tagged $tag",
    )
    val slot = slotNode.cowbellLaidOut
    val under = slotNode.cowbellWalk().drop(1)
    // The bell fills its row, so it is checked for a height but not compared control against control.
    val bellNode = under.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == BellTag }
    val labelled = under.filter { it !== bellNode && it.cowbellName().isNotBlank() }
    return CowbellFitReading(
        slot = slot,
        panelBottom = slot.bottom - lift,
        controlsBottom = labelled.maxOfOrNull { it.cowbellLaidOut.bottom } ?: slot.top,
        bell = bellNode?.cowbellLaidOut,
        columnWidths = listOf(ModeTag, KnobsTag, DjFaderTag).mapNotNull { tag ->
            under.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.let { tag to it.cowbellLaidOut.width }
        }.toMap(),
        names = labelled.map { it.cowbellName() },
        heights = labelled.map { it.cowbellLaidOut.height },
        isText = labelled.map { it.cowbellText().isNotBlank() },
        clipped = labelled.filter { it.boundsInRoot.height < it.cowbellLaidOut.height - 0.5f }.map { it.cowbellName() },
        overflowing = labelled.filter { it.cowbellTextShortfall() > 0.5f }.map { it.cowbellName() },
    )
}

/** What every DJ cowbell slot shows, whatever its size: the knobs by name, each chip by its leading word. */
private val DjLabels = listOf("TONE", "RING", "INTENSITY", "Metal", "Tap|Shake|Hold", "Oct", "Vol", "Lock")

/** The bell keeps at least this much height in the smallest slot under test. */
private const val BellFloor = 64f

/** Every control clears the panel's foot, none is clipped, squeezed against [tall] or truncated, and the bell has a height. */
internal fun assertCowbellClears(what: String, r: CowbellFitReading, tall: CowbellFitReading) {
    val panel = r.panelBottom - r.slot.top
    for (label in DjLabels) assertTrue(r.names.any { n -> label.split('|').any { n.startsWith(it) } }, "$what: no '$label' in ${r.names}")
    assertTrue(
        r.controlsBottom <= r.panelBottom + 0.5f,
        "$what: controls end at ${r.controlsBottom - r.slot.top}dp, past the ${panel}dp panel",
    )
    assertTrue(r.clipped.isEmpty(), "$what: ${r.clipped} are clipped in the ${panel}dp panel")
    assertTrue(r.overflowing.isEmpty(), "$what: ${r.overflowing} want more width than the panel gives them")
    val squeezed = r.squeezedAgainst(tall)
    assertTrue(squeezed.isEmpty(), "$what: squeezed in the ${panel}dp panel: $squeezed")
    val bell = assertNotNull(r.bell, "$what: no bell")
    assertTrue(bell.height >= BellFloor, "$what: the bell is only ${bell.height}dp tall")
    // The bell is the widest of the four columns: the controls, the knobs and the fader give way first.
    for ((tag, width) in r.columnWidths) {
        assertTrue(bell.width > width, "$what: the bell is ${bell.width}dp wide, no wider than $tag at ${width}dp")
    }
}

/** The DJ panel at [width] with 700dp of height, so nothing squeezes: what each label's box should be. */
internal fun cowbellTallReading(width: Int, fontScale: Float): CowbellFitReading {
    val scene = ImageComposeScene(width, 700, Density(1f, fontScale)) {
        OrpheusTheme {
            Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                CowbellDjPanel(
                    feature = CowbellViewModel.previewFeature(),
                    modifier = Modifier.fillMaxSize().testTag(SlotTag),
                )
            }
        }
    }
    try {
        // The compact-knob switch learns the panel's size a frame late.
        repeat(8) { scene.render() }
        return scene.readCowbellFit(SlotTag, lift = 0f)
    } finally {
        scene.close()
    }
}

/**
 * The Cowbell tab through [CowbellRoutePanel] in each screen layout: every label shows, clears the
 * panel's bottom edge and fits its width at the font scale it renders, and the bell keeps a height.
 * D-pad and Back on a TV scene are covered below.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*CowbellSlotFitTest*'
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class CowbellSlotFitTest {

    private class Case(
        val width: Int,
        val height: Int,
        val layout: DjLayout,
        val fontScale: Float = 1f,
        val pair: List<DjRoute> = emptyList(),
        val mode: CowbellMode = CowbellMode.TAP,
    ) {
        override fun toString() =
            "${width}x$height" + (if (fontScale != 1f) "@$fontScale" else "") + " ${layout::class.simpleName}" +
                (if (mode != CowbellMode.TAP) " $mode" else "")
    }

    private val engine = RenderProbeSynthEngine()

    private fun scene(case: Case, context: CoroutineContext = Dispatchers.Unconfined): ImageComposeScene =
        ImageComposeScene(case.width, case.height, Density(1f, case.fontScale), context) {
            OrpheusTheme {
                CompositionLocalProvider(LocalLiquidEffects provides VisualizationLiquidEffects.Default) {
                    val pulsar = PulsarViewModel.previewFeature()
                    val feature = remember { CowbellViewModel.previewFeature(CowbellUiState(mode = case.mode)) }
                    val cowbell: @Composable (Modifier, Boolean) -> Unit = { mod, docked ->
                        CowbellRoutePanel(
                            synthEngine = engine,
                            modifier = mod.testTag(SlotTag),
                            docked = docked,
                            lift = hornPillLift(case.layout, docked, barShown = false),
                            feature = feature,
                        )
                    }
                    val content: @Composable () -> Unit = {
                        DjAppMainContent(
                            layout = case.layout,
                            dockedPanels = if (case.layout == DjLayout.LargeScreen) listOf(CowbellTab) else emptyList(),
                            pairPanels = case.pair,
                            pulsarFeature = pulsar,
                            synthEngine = engine,
                            vizFeature = VizViewModel.previewFeature(),
                            onShowVibeInfo = {},
                            routePanel = { route, mod, docked ->
                                if (route == CowbellTab) cowbell(mod, docked) else PreviewRoutePanel(route, mod, docked = false)
                            },
                            navContent = { mod -> Box(mod) { cowbell(Modifier.fillMaxSize(), false) } },
                        )
                    }
                    if (case.layout == DjLayout.LargeScreen) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) { content() }
                    } else {
                        DjAppNavScaffold(
                            isSelected = { it == CowbellTab },
                            onItemClick = {},
                            layout = case.layout,
                            pulsarFeature = pulsar,
                            timerFeature = TimerViewModel.previewFeature(),
                            onTogglePlayback = {},
                            modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)),
                        ) { content() }
                    }
                }
            }
        }

    // A hand-driven clock: MORE's expand animation runs 300ms, so it needs frames that far apart in time.
    private var nowNs = 1_000_000_000L

    private fun ImageComposeScene.frame() {
        nowNs += 16_000_000L
        render(nowNs)
    }

    private fun ImageComposeScene.settle() = repeat(64) { frame() }

    private fun ImageComposeScene.hasText(text: String) = cowbellNodes().any { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    // Pictures are for a person to look at: only a -PrenderHarness run writes them.
    private fun ImageComposeScene.save(case: Case, state: String) = runCatching {
        if (System.getProperty("orpheus.renderHarness") != "true") return@runCatching
        val dir = File("build/cowbell-slot-fit").apply { mkdirs() }
        File(dir, "${case.toString().replace(' ', '-')}-${state.replace(' ', '-')}.png").writeBytes(render(nowNs).encodeToData()!!.bytes)
    }

    private fun ImageComposeScene.assertClears(case: Case, state: String, lift: Float) {
        save(case, state)
        val r = readCowbellFit(SlotTag, lift)
        val panelHeight = r.panelBottom - r.slot.top
        assertTrue(hasTag(DjFaderTag), "$case: the DJ panel is not on screen (no fader)")
        val tall = cowbellTallReading(r.slot.width.toInt(), case.fontScale)
        println(
            "[cowbell-fit] $case $state: panel ${panelHeight}dp, gap ${r.gap}dp, bell ${r.bell?.height}dp," +
                " squeezed ${r.squeezedAgainst(tall)}",
        )
        assertCowbellClears("$case $state", r, tall)
    }

    private fun ImageComposeScene.hasTag(tag: String) =
        cowbellNodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun assertFits(case: Case) {
        val lift = hornPillLift(case.layout, docked = false, barShown = false).value
        val scene = scene(case)
        try {
            scene.settle()
            scene.assertClears(case, "fits", lift)
            assertTrue(!scene.hasText("LEVEL"), "$case: the Orpheus MORE controls are on the DJ tab")
        } finally {
            scene.close()
        }
    }

    /** A closed iPhone Duo: 678pt less the 34pt home indicator, and a 205dp lower slot. */
    @Test
    fun cowbellFitsDuoClosedSlot() = assertFits(Case(466, 644, DjLayout.Portrait))

    /** The same closed Duo with the largest font scale: the tightest the column of chips and knobs gets. */
    @Test
    fun cowbellFitsDuoClosedSlotAtLargeFont() = assertFits(Case(466, 644, DjLayout.Portrait, fontScale = 1.3f))

    /** Shake adds the Fill dropdown under the bell, in the tightest slot. */
    @Test
    fun cowbellFitsDuoClosedSlotInShake() {
        assertFits(Case(466, 644, DjLayout.Portrait, fontScale = 1.3f, mode = CowbellMode.SHAKE))
        assertFits(Case(466, 644, DjLayout.Portrait, mode = CowbellMode.SHAKE))
    }

    /** The Fold 8's cover screen upright, at its 1.3 font scale. */
    @Test
    fun cowbellFitsFoldCoverPortrait() = assertFits(Case(360, 840, DjLayout.Portrait, fontScale = 1.3f))

    /** Two panels side by side, so each half is about 350dp wide. */
    @Test
    fun cowbellFitsPortraitPairHalf() =
        assertFits(Case(700, 900, DjLayout.PortraitPair, pair = listOf(CowbellTab, DjTab)))

    /** The Fold 8's cover screen sideways. */
    @Test
    fun cowbellFitsLandscapeSlot() = assertFits(Case(840, 360, DjLayout.Landscape, fontScale = 1.3f))

    /** The Fold tabletop from DjLayoutTest: 835x752 with the crease at 376dp, a pair on the flat half. */
    @Test
    fun cowbellFitsTabletopBelowHinge() {
        val hinge = Hinge(topPx = 376, bottomPx = 376)
        assertFits(
            Case(835, 752, DjLayout.Tabletop(hinge, pair = true), pair = listOf(CowbellTab, DjTab)),
        )
    }

    /** The dock wraps its content, so the panel takes [CowbellDockedHeight]. */
    @Test
    fun cowbellDockedKeepsItsHeight() = assertDockedFits(Case(1280, 720, DjLayout.LargeScreen))

    /** The dock at the largest font scale, where the knob stack is tallest. */
    @Test
    fun cowbellDockedFitsAtLargeFont() = assertDockedFits(Case(1280, 720, DjLayout.LargeScreen, fontScale = 1.3f))

    private fun assertDockedFits(case: Case) {
        val scene = scene(case)
        try {
            scene.settle()
            val r = scene.readCowbellFit(SlotTag, lift = 0f)
            assertEquals(CowbellDockedHeight.value, r.slot.height, 0.5f, "the docked panel's height")
            scene.assertClears(case, "docked", lift = 0f)
            // The bell column runs the panel's height, so it is at least as tall as the five chips closed up.
            assertTrue(r.bell!!.height >= ChipsClosedUp, "$case docked: the bell is only ${r.bell!!.height} tall")
        } finally {
            scene.close()
        }
    }

    // ---- Popups ----

    private fun ImageComposeScene.centreOf(tag: String): Offset =
        cowbellNodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.cowbellLaidOut.center

    private fun ImageComposeScene.touch(type: PointerEventType, at: Offset) {
        sendPointerEvent(type, at, type = PointerType.Touch)
        frame()
    }

    /** The mode dropdown and the Lock and Level sliders open inside the closed Duo slot, where the column is tightest. */
    @Test
    fun popupsOpenInTheDuoSlot() {
        val case = Case(466, 644, DjLayout.Portrait)
        scene(case).let { scene ->
            try {
                scene.settle()
                val mode = scene.centreOf(ModeTag)
                scene.touch(PointerEventType.Press, mode)
                scene.touch(PointerEventType.Release, mode)
                scene.settle()
                assertTrue(scene.hasText("Shake") && scene.hasText("Hold"), "the mode dropdown did not open")
                scene.save(case, "mode dropdown open")
            } finally {
                scene.close()
            }
        }
        for ((chip, state) in listOf(LockTag to "lock slider open", LevelTag to "level slider open")) {
            // The long press times out with a coroutine delay, so a test scheduler skips the wait.
            val clock = TestCoroutineScheduler()
            scene(case, UnconfinedTestDispatcher(clock)).let { scene ->
                try {
                    scene.settle()
                    val at = scene.centreOf(chip)
                    scene.touch(PointerEventType.Press, at)
                    clock.advanceTimeBy(900)
                    clock.runCurrent()
                    scene.settle()
                    assertTrue(scene.hasTag(SliderTag), "holding $chip did not open its slider")
                    scene.save(case, state)
                    scene.touch(PointerEventType.Release, at)
                } finally {
                    scene.close()
                }
            }
        }
    }

    // ---- TV: D-pad focus and Back ----

    private fun ImageComposeScene.key(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); frame()
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); frame()
    }

    /** What a TV platform does with an arrow no control consumed: search focus that way. */
    @Composable
    private fun TvRemote(content: @Composable () -> Unit) {
        val focus = LocalFocusManager.current
        Box(
            Modifier.onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> focus.moveFocus(FocusDirection.Right)
                    Key.DirectionLeft -> focus.moveFocus(FocusDirection.Left)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                    else -> false
                }
            },
        ) { content() }
    }

    /**
     * The focused control, named: its tag, else FADER or the knob it sits in (the knobs and the
     * fader's thumb carry no tag of their own).
     */
    private fun ImageComposeScene.focused(): String? {
        val nodes = cowbellNodes()
        val n = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true } ?: return null
        n.config.getOrNull(SemanticsProperties.TestTag)?.let { return it }
        val at = n.cowbellLaidOut.center
        fun inside(tag: String) = nodes.first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.cowbellLaidOut.contains(at)
        if (inside(DjFaderTag)) return "FADER"
        for ((tag, name) in listOf(ToneTag to "TONE knob", RingTag to "RING knob", IntensityTag to "INTENSITY knob")) {
            if (inside(tag)) return name
        }
        return n.cowbellWalk().firstNotNullOfOrNull { c -> c.cowbellText().ifBlank { null } }
    }

    /** The Cowbell tab in a TV scene whose own Back leaves the tab, as the app's does. */
    private fun tvScene(
        selected: MutableState<DjRoute>,
        dispatcher: (NavigationEventDispatcher) -> Unit = {},
    ) = ImageComposeScene(412, 400, Density(1f)) {
        OrpheusTheme {
            CompositionLocalProvider(LocalTvFocusChrome provides true, LocalLiquidEffects provides VisualizationLiquidEffects.Default) {
                dispatcher(assertNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher)
                // Composed before the panel, as the stage's own handler is: the panel's must win.
                NavigationBackHandler(
                    rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = true,
                    onBackCompleted = { selected.value = DjTab },
                )
                Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                    TvRemote {
                        if (selected.value == CowbellTab) {
                            CowbellRoutePanel(
                                synthEngine = engine,
                                modifier = Modifier.fillMaxSize().testTag(SlotTag),
                                docked = false,
                                lift = 0.dp,
                                feature = remember { CowbellViewModel.previewFeature() },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Presses [key] until focus moves off the current control (the bell and the fader take a few first). */
    private fun ImageComposeScene.pressUntilFocusMoves(key: Key, limit: Int = 30): String? {
        val from = focused()
        repeat(limit) {
            key(key)
            if (focused() != from) return focused()
        }
        return focused()
    }

    /** The collapse pill, mode, octave, level, lock, the three knobs, the body segments, the bell, then the fader: Tab walks that. */
    @Test
    fun dpadReachesEveryControlInOrder() {
        val scene = tvScene(mutableStateOf<DjRoute>(CowbellTab))
        try {
            scene.settle()
            scene.key(Key.Tab)
            val tab = mutableListOf(scene.focused())
            repeat(10) { scene.key(Key.Tab); tab += scene.focused() }
            assertEquals<List<String?>>(
                listOf(
                    CollapseTag, ModeTag, OctaveTag, LevelTag, LockTag,
                    "TONE knob", "RING knob", "INTENSITY knob", BodyTag, BellTag, "FADER",
                ),
                tab,
                "Tab order",
            )
            // The remote's arrows: down the chips and back up, then from the level chip right into the
            // knob beside it, up to TONE, down the knobs, right across the bell to the fader, then back
            // to the bell and up onto the selector over it.
            repeat(2) { scene.key(Key.Tab) }  // past the collapse pill to the mode chip
            val path = mutableListOf(scene.focused())
            val moves = List(3) { Key.DirectionDown } + List(3) { Key.DirectionUp } + List(2) { Key.DirectionDown }
            for (k in moves) path += scene.pressUntilFocusMoves(k)
            val knobs = listOf("TONE knob", "RING knob", "INTENSITY knob")
            val entered = scene.pressUntilFocusMoves(Key.DirectionRight)
            assertTrue(entered in knobs, "Right from the level chip went to $entered")
            repeat(knobs.indexOf(entered)) { scene.pressUntilFocusMoves(Key.DirectionUp) }
            path += scene.focused()
            for (k in listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp)) {
                path += scene.pressUntilFocusMoves(k)
            }
            println("[cowbell-fit] arrow path: $path")
            assertEquals<List<String?>>(
                listOf(ModeTag, OctaveTag, LevelTag, LockTag) +
                    listOf(LevelTag, OctaveTag, ModeTag) +
                    listOf(OctaveTag, LevelTag) + knobs + listOf(BellTag, "FADER", BellTag, BodyTag),
                path,
                "arrow path",
            )
        } finally {
            scene.close()
        }
    }

    /** Hold OK on the Lock chip to open its slider: the first key-down arms, a repeat is the hold. */
    private fun ImageComposeScene.holdOkOnLock() {
        repeat(14) { if (focused() != LockTag) key(Key.Tab) }
        assertEquals(LockTag, focused(), "never focused the Lock chip")
        sendKeyEvent(KeyEvent(Key.DirectionCenter, KeyEventType.KeyDown)); frame()
        sendKeyEvent(KeyEvent(Key.DirectionCenter, KeyEventType.KeyDown)); frame()
        sendKeyEvent(KeyEvent(Key.DirectionCenter, KeyEventType.KeyUp)); frame()
        settle()
    }

    /** Back with the Lock slider open closes the slider and leaves the tab put; the next Back leaves the tab. */
    @Test
    fun backClosesTheLockSliderBeforeTheAppLeavesTheTab() {
        val selected = mutableStateOf<DjRoute>(CowbellTab)
        var dispatcher: NavigationEventDispatcher? = null
        val scene = tvScene(selected) { dispatcher = it }
        try {
            scene.settle()
            val back = DirectNavigationEventInput().also { assertNotNull(dispatcher).addInput(it) }
            scene.holdOkOnLock()
            assertTrue(scene.hasTag(SliderTag), "holding OK on Lock did not open its slider")

            back.backCompleted()
            scene.settle()
            assertTrue(!scene.hasTag(SliderTag), "Back left the Lock slider open")
            assertEquals(CowbellTab, selected.value, "the Back that closed the slider also left the tab")
            assertTrue(scene.hasTag(DjFaderTag), "the panel did not stay on screen")

            // The popup ate the first hold's lift; a stale press would swallow this one.
            scene.holdOkOnLock()
            assertTrue(scene.hasTag(SliderTag), "a second hold on Lock did not reopen its slider")
            back.backCompleted()
            scene.settle()
            assertTrue(!scene.hasTag(SliderTag), "Back left the reopened Lock slider open")

            back.backCompleted()
            scene.settle()
            assertEquals(DjTab, selected.value, "with the slider shut, Back should reach the app")
        } finally {
            scene.close()
        }
    }
}
