package org.balch.orpheus.djapp.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.balch.orpheus.djapp.AiTab
import org.balch.orpheus.djapp.DjTab
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.djapp.MixTab
import org.balch.orpheus.djapp.TimerTab
import org.balch.orpheus.djapp.ai.collab.CollabEvent
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.mixer.AiMixerFeature
import org.balch.orpheus.djapp.ai.mixer.AiMixerUiState
import org.balch.orpheus.djapp.ai.mixer.AiMixerViewModel
import org.balch.orpheus.djapp.ai.mixer.CHANGES_SCRIM_TAG
import org.balch.orpheus.djapp.ai.mixer.CHANGES_TAG
import org.balch.orpheus.djapp.ai.mixer.EMPTY_METERS
import org.balch.orpheus.djapp.ai.mixer.MixerTrack
import org.balch.orpheus.djapp.ai.mixer.SHEET_MIXER_TAG
import org.balch.orpheus.djapp.ai.mixer.stripTag
import org.balch.orpheus.djapp.djTabs
import org.balch.orpheus.djapp.variant.LocalStageChromeOverhang
import org.balch.orpheus.djapp.variant.mergeTabContributions
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.ai.PromptIntent
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.ui.theme.OrpheusTheme

/** The Fold's inner screen and a 1000x700dp desktop window, as canvas size and scale. */
internal enum class SheetCanvas(val width: Float, val height: Float, val scale: Float) {
    FOLD(1280f, 1153f, 0.652f),
    DESKTOP(1000f / 0.78f, 700f / 0.78f, 0.78f),
    DESKTOP_NATIVE(1280f, 800f, 1f),
    PHONE(412f, 900f, 1f),
}

internal val sheetMixerState = AiMixerUiState(
    vibeName = "Rust Belt Sunrise",
    sections = listOf("intro", "groove", "drop"),
    section = 1,
    tracks = BenchMeters.CHANNEL_NAMES.take(8).mapIndexed { t, name ->
        MixerTrack(t, name, "VA/FM", levelDb = 0f, pan = 0f, filePan = 0f, reverb = 0.2f, delay = 0.1f,
            muted = false, soloed = false, silenced = false)
    },
)

internal fun sheetMixer(): AiMixerFeature = AiMixerViewModel.previewFeature(sheetMixerState, EMPTY_METERS)

/** A chat feature over plain state: the draft, the mode and the Mixer's visibility land where a test can read them. */
internal class FakeChat(initial: DjAiUiState) : DjAiFeature {
    val state = MutableStateFlow(initial)
    var mixerVisible = false
    var sheetOpen = false
    override val stateFlow: StateFlow<DjAiUiState> = state
    override val actions = DjAiPanelActions.EMPTY.copy(
        updateDraft = { t -> state.update { it.copy(promptDraft = t) } },
        setSheetMode = { m -> state.update { it.copy(sheetMode = m) } },
        setMixerVisible = { mixerVisible = it },
        setSheetOpen = { sheetOpen = it },
    )
}

/** [AiSheet] on a canvas, rendered at 2px per canvas dp times the scale; bounds come back in canvas dp. */
internal class SheetScene(
    val canvas: SheetCanvas,
    overhang: Dp = 0.dp,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val density = 2f * canvas.scale
    val scene = ImageComposeScene(
        (canvas.width * density).roundToInt(),
        (canvas.height * density).roundToInt(),
        Density(density),
    ) {
        CompositionLocalProvider(LocalCanvasScale provides canvas.scale, LocalStageChromeOverhang provides overhang) {
            OrpheusTheme { Box(Modifier.fillMaxSize().background(Color(0xFF15131F))) { content() } }
        }
    }
    private var now = 0L

    init {
        frames(40)
    }

    fun frames(n: Int = 40) = repeat(n) {
        now += 16
        scene.render(now * 1_000_000)
    }

    fun png(): ByteArray = scene.render(now * 1_000_000).encodeToData()!!.bytes

    fun nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out
    }

    private fun tagged(tag: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    fun has(tag: String) = tagged(tag) != null

    fun bounds(tag: String): Rect {
        val b = assertNotNull(tagged(tag), "no $tag").boundsInRoot
        return Rect(b.left / density, b.top / density, b.right / density, b.bottom / density)
    }

    /** Clicks the clickable holding the text or content description [label]. */
    fun click(label: String) {
        var n: SemanticsNode? = assertNotNull(
            nodes().firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                    n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
            },
            "no \"$label\"",
        )
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        assertNotNull(n, "\"$label\" is not in a clickable").config[SemanticsActions.OnClick].action!!()
        frames()
    }

    private fun field() = assertNotNull(nodes().firstOrNull { it.config.contains(SemanticsProperties.EditableText) }, "no input")

    fun type(text: String) {
        field().config[SemanticsActions.SetText].action!!(AnnotatedString(text))
        frames()
    }

    fun inputText(): String = field().config[SemanticsProperties.EditableText].text

    fun focusInput() {
        field().config[SemanticsActions.RequestFocus].action!!()
        frames()
    }

    fun inputFocused(): Boolean = field().config.getOrNull(SemanticsProperties.Focused) == true

    /** Invokes the click action of the node tagged [tag]. */
    fun clickTag(tag: String) {
        assertNotNull(tagged(tag), "no $tag").config[SemanticsActions.OnClick].action!!()
        frames()
    }

    @OptIn(InternalComposeUiApi::class)
    fun key(key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        frames(4)
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        frames()
    }

    override fun close() = scene.close()
}

class AiSheetLayoutTest {

    private fun sheet(
        canvas: SheetCanvas,
        chat: DjAiFeature,
        landscape: MutableState<Boolean> = mutableStateOf(true),
        overhang: Dp = 0.dp,
    ) = SheetScene(canvas, overhang) { AiSheet(chat, sheetMixer(), landscape.value, onDismiss = {}) }

    private val mixerMode = DjAiUiState(isKeySet = true, sheetMode = AiSheetMode.MIXER)

    private fun SheetScene.assertWholeStrips() {
        val mixer = bounds(SHEET_MIXER_TAG)
        for (i in 0 until 8) {
            val strip = bounds(stripTag(i))
            assertTrue(strip.width >= 72f - 0.5f, "${canvas.name}: strip $i is ${strip.width}dp")
        }
        assertTrue(bounds(stripTag(7)).right <= mixer.right + 0.5f, "${canvas.name}: the last strip runs past the Mixer")
    }

    @Test
    fun mixerModeShowsMixerAndChatSideBySide() {
        for (canvas in listOf(SheetCanvas.FOLD, SheetCanvas.DESKTOP)) {
            val chat = FakeChat(mixerMode)
            sheet(canvas, chat, overhang = 40.dp).use { s ->
                val mixer = s.bounds(SHEET_MIXER_TAG)
                val column = s.bounds(SHEET_CHAT_TAG)
                assertTrue(abs(column.width - 440f) < 0.5f, "${canvas.name}: chat is ${column.width}dp")
                assertTrue(mixer.right <= column.left, "${canvas.name}: the Mixer ends at ${mixer.right}, the chat starts at ${column.left}")
                assertTrue(abs(column.right - (canvas.width - 8f)) < 0.5f, "${canvas.name}: the chat ends at ${column.right}")
                // The chat stops above the bar's chrome, as the Mixer's strips do.
                assertTrue(column.bottom <= canvas.height - 8f - 40f + 1f, "${canvas.name}: the chat ends at ${column.bottom}")
                s.assertWholeStrips()
                assertTrue(chat.mixerVisible, "${canvas.name}: the Mixer is on screen")
                assertTrue(chat.sheetOpen, "${canvas.name}: the sheet is on screen")
            }
            assertFalse(chat.mixerVisible, "${canvas.name}: the closed sheet hides the Mixer")
            assertFalse(chat.sheetOpen, "${canvas.name}: the sheet closed")
        }
    }

    @Test
    fun chatModeShowsChatOnly() {
        val chat = FakeChat(DjAiUiState(isKeySet = true))
        sheet(SheetCanvas.FOLD, chat).use { s ->
            assertFalse(s.has(SHEET_MIXER_TAG))
            assertFalse(s.has(stripTag(0)))
            val column = s.bounds(SHEET_CHAT_TAG)
            assertTrue(abs(column.width - 440f) < 0.5f, "the side sheet is ${column.width}dp")
            assertFalse(chat.mixerVisible)

            // The header's toggle brings the Mixer up beside the chat, and back.
            s.click("Mixer")
            assertEquals(AiSheetMode.MIXER, chat.state.value.sheetMode)
            assertTrue(s.has(SHEET_MIXER_TAG))
            assertTrue(chat.mixerVisible)
            s.click("Mixer")
            assertFalse(s.has(SHEET_MIXER_TAG))
            assertFalse(chat.mixerVisible)
        }
    }

    @Test
    fun closingAnOverlayHandsFocusBackToTheInput() {
        var escapesOut = 0
        SheetScene(SheetCanvas.DESKTOP) {
            // Counts Esc halves the sheet lets through, as the window's Back handler would see them.
            Box(Modifier.fillMaxSize().onKeyEvent { if (it.key == Key.Escape) escapesOut++; false }) {
                AiSheet(FakeChat(mixerMode), sheetMixer(), isLandscape = true, onDismiss = {})
            }
        }.use { s ->
            fun settingsOpen() = s.nodes().any { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Close settings" }
            s.focusInput()
            assertTrue(s.inputFocused())

            // The chat's settings: Esc closes them and the input has focus again; neither half of the Esc leaks.
            s.click("Chat settings")
            assertTrue(settingsOpen())
            assertFalse(s.inputFocused(), "the settings take focus")
            s.key(Key.Escape)
            assertFalse(settingsOpen(), "Esc should close the settings")
            assertTrue(s.inputFocused(), "focus did not come back to the input")
            assertEquals(0, escapesOut)

            // The Mixer's changes: Esc and a tap outside both hand focus back to the chat's input.
            s.clickTag(CHANGES_TAG)
            assertTrue(s.has(CHANGES_SCRIM_TAG))
            assertFalse(s.inputFocused())
            s.key(Key.Escape)
            assertFalse(s.has(CHANGES_SCRIM_TAG), "Esc should close the changes")
            assertTrue(s.inputFocused())
            assertEquals(0, escapesOut)
            s.clickTag(CHANGES_TAG)
            s.clickTag(CHANGES_SCRIM_TAG)
            assertFalse(s.has(CHANGES_SCRIM_TAG))
            assertTrue(s.inputFocused())

            // With nothing open, Esc goes on to the sheet as before.
            s.key(Key.Escape)
            assertEquals(2, escapesOut)
        }
    }

    @Test
    fun collapsedChatGivesMixerTheWidth() {
        for (canvas in listOf(SheetCanvas.FOLD, SheetCanvas.DESKTOP)) {
            sheet(canvas, FakeChat(mixerMode)).use { s ->
                val before = s.bounds(SHEET_MIXER_TAG).width
                s.click("Collapse chat")
                assertFalse(s.has(SHEET_CHAT_TAG))
                // 48dp as it reads on the screen, whatever the canvas scale.
                val railWidth = 48f / canvas.scale
                val rail = s.bounds(SHEET_RAIL_TAG)
                assertTrue(abs(rail.width - railWidth) < 0.5f, "${canvas.name}: the rail is ${rail.width}dp")
                val mixer = s.bounds(SHEET_MIXER_TAG)
                assertTrue(abs(mixer.width - (before + 440f - railWidth)) < 0.5f, "${canvas.name}: the Mixer is ${mixer.width}dp")
                s.assertWholeStrips()

                s.click("Show chat")
                assertTrue(abs(s.bounds(SHEET_CHAT_TAG).width - 440f) < 0.5f)
                assertNull(s.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == SHEET_RAIL_TAG })
            }
        }
    }

    @Test
    fun portraitStacksMixerAboveChat() {
        val chat = FakeChat(mixerMode)
        sheet(SheetCanvas.PHONE, chat, landscape = mutableStateOf(false)).use { s ->
            val mixer = s.bounds(SHEET_MIXER_TAG)
            val column = s.bounds(SHEET_CHAT_TAG)
            assertTrue(mixer.bottom <= column.top + 0.5f, "the Mixer ends at ${mixer.bottom}, the chat starts at ${column.top}")
            val share = mixer.height / (mixer.height + column.height)
            assertTrue(abs(share - 0.45f) < 0.02f, "the Mixer takes $share of the sheet")
            assertTrue(abs(mixer.width - SheetCanvas.PHONE.width) < 1f && abs(column.width - SheetCanvas.PHONE.width) < 1f)
            assertTrue(chat.mixerVisible)
        }
    }

    @Test
    fun modeAndDraftSurviveLayoutChange() {
        val chat = FakeChat(mixerMode)
        val landscape = mutableStateOf(true)
        sheet(SheetCanvas.FOLD, chat, landscape).use { s ->
            s.type("warmer bass")
            assertEquals("warmer bass", chat.state.value.promptDraft)

            landscape.value = false
            s.frames()
            assertEquals(AiSheetMode.MIXER, chat.state.value.sheetMode)
            assertTrue(s.has(SHEET_MIXER_TAG), "portrait still shows the Mixer")
            assertTrue(s.bounds(SHEET_MIXER_TAG).bottom <= s.bounds(SHEET_CHAT_TAG).top + 0.5f)
            assertEquals("warmer bass", s.inputText())
            assertTrue(chat.mixerVisible)

            landscape.value = true
            s.frames()
            assertEquals("warmer bass", s.inputText())
            assertTrue(s.bounds(SHEET_MIXER_TAG).right <= s.bounds(SHEET_CHAT_TAG).left)
        }
    }

    @Test
    fun openMixerShowsTheMixerBeforeTheWaitRunsOut() {
        val state = MutableStateFlow(DjAiUiState(isKeySet = true))
        val collab = MixCollab()
        val sent = mutableListOf<PromptIntent>()
        val core = DjAiChat(state, collab, CoroutineScope(Dispatchers.Unconfined), sendPrompt = { sent += it }, restartAgent = {})
        val chat = object : DjAiFeature {
            override val stateFlow: StateFlow<DjAiUiState> = state
            override val actions = DjAiPanelActions.EMPTY.copy(
                onAction = core::onAction,
                setSheetMode = { m -> state.update { it.copy(sheetMode = m) } },
                setMixerVisible = collab::setMixerVisible,
            )
        }
        core.send("louder bass", null)
        core.onCollabEvent(CollabEvent.OpenMixerRequested("closed"))
        core.onActivity(AgentActivityEvent.Assistant("Open it for me."))

        sheet(SheetCanvas.FOLD, chat).use { s ->
            assertFalse(collab.mixerVisible.value)
            val started = System.nanoTime()
            s.click("Open Mixer")
            assertTrue(collab.mixerVisible.value, "the composed Mixer tells the agent it is on screen")
            assertTrue(s.has(SHEET_MIXER_TAG))
            assertEquals("The Mixer is open.", sent.last().displayText)
            assertTrue(sent.last().prompt.startsWith("[Mixer: open."), sent.last().prompt)
            assertTrue((System.nanoTime() - started) / 1_000_000 < MIXER_WAIT_MS, "sent before the wait ran out")
        }
        assertFalse(collab.mixerVisible.value, "closing the sheet hides the Mixer")
    }

    @Test
    fun theDockHasOneAiEntry() {
        assertEquals(listOf(DjTab, MixTab, AiTab, TimerTab), mergeTabContributions(djTabs, listOf(AiTabContribution())))
    }
}
