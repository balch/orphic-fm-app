package org.balch.orpheus.djapp.ai.mixer

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.OrpheusEngineId.BD
import org.balch.orpheus.core.audio.OrpheusEngineId.CHD
import org.balch.orpheus.core.audio.OrpheusEngineId.DX
import org.balch.orpheus.core.audio.OrpheusEngineId.FM
import org.balch.orpheus.core.audio.OrpheusEngineId.GRN
import org.balch.orpheus.core.audio.OrpheusEngineId.HH
import org.balch.orpheus.core.audio.OrpheusEngineId.MOD
import org.balch.orpheus.core.audio.OrpheusEngineId.NSE
import org.balch.orpheus.core.audio.OrpheusEngineId.SD
import org.balch.orpheus.core.audio.OrpheusEngineId.STR
import org.balch.orpheus.core.audio.OrpheusEngineId.VA
import org.balch.orpheus.core.audio.OrpheusEngineId.VOWEL_FOF
import org.balch.orpheus.core.audio.OrpheusEngineId.WTB
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.knobMeaning
import org.balch.orpheus.features.pulsar.bench.knobSteps
import org.balch.orpheus.features.pulsar.bench.knobValueText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.djapp.AiTab
import org.balch.orpheus.djapp.DjAppNavScaffold
import org.balch.orpheus.djapp.DjLayout
import org.balch.orpheus.djapp.DjTab
import org.balch.orpheus.djapp.DjTvBottomBar
import org.balch.orpheus.djapp.HornTab
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.djapp.MixTab
import org.balch.orpheus.djapp.PulsarTab
import org.balch.orpheus.djapp.djTabs
import org.balch.orpheus.djapp.variant.LocalStageChromeOverhang
import org.balch.orpheus.djapp.variant.stageChromeOverhang
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.ChannelMeter
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme

/** ./gradlew :apps:djapp:ai:jvmTest --tests '*AiMixerRenderHarness*' -PrenderHarness --rerun */
class AiMixerRenderHarness {
    private companion object {
        const val FOLD_SCALE = 0.652f
    }

    private val engines = listOf("BD/BD", "SD/HH", "HH/HH", "VA/FM", "CHD/WT", "STR/MOD", "GRN/NOI", "SPK/MST")
    private val slots = listOf(
        BD to BD, SD to HH, HH to HH, VA to FM, CHD to WTB, STR to MOD, GRN to NSE, DX to VOWEL_FOF,
    )

    // A mix of pinned, macro-led and edited knobs; track 5 has its switch on the Space engine, which is not playing.
    private fun voiceOf(t: Int, id: OrpheusEngineId) = EngineKnob.entries.mapIndexed { k, knob ->
        val meaning = knobMeaning(id, knob)
        val value = (0.15f + 0.3f * k + 0.04f * t).coerceAtMost(0.95f)
        val pinned = (t + k) % 3 == 0 || knobSteps(id, knob) != null
        MixerVoiceKnob(
            knob = knob, engineId = id, label = meaning.short, meaning = meaning.long, value = value,
            valueText = knobValueText(id, knob, value, ::f2), pinned = pinned, changed = t == 3 && k == 0,
            source = if (pinned) null else if (knob == EngineKnob.MORPH) Macro.SPACE else Macro.MOOD,
            active = meaning.active, steps = knobSteps(id, knob),
        )
    }

    private val tracks = BenchMeters.CHANNEL_NAMES.take(8).mapIndexed { t, name ->
        MixerTrack(
            index = t,
            name = name,
            engine = engines[t],
            levelDb = if (t == 3) -4f else 0f,
            pan = listOf(0f, -0.2f, 0.35f, 0f, -0.4f, 0.25f, -0.6f, 0.5f)[t],
            filePan = 0f,
            reverb = 0.1f * t,
            delay = 0.05f * t,
            muted = t == 1,
            soloed = t == 4,
            silenced = t != 4,
            slot = if (t == 5) EngineSlot.SPACE else EngineSlot.EDM,
            slotHeard = t != 5,
            edmEngine = slots[t].first.name,
            spaceEngine = slots[t].second.name,
            voice = voiceOf(t, if (t == 5) slots[t].second else slots[t].first),
        )
    }

    private fun ago(min: Int) = System.currentTimeMillis() - min * 60_000L

    private val state = AiMixerUiState(
        vibeName = "Rust Belt Sunrise",
        sections = listOf("intro", "groove", "drop"),
        section = 1,
        loopSection = 1,
        sectionScopeAvailable = true,
        scope = Scope.VIBE,
        tracks = tracks,
        macros = listOf(
            MixerMacro(Macro.ENERGY, 0.6f, 0.5f, changed = true, note = ""),
            MixerMacro(Macro.COMPLEXITY, 0.45f, 0.45f, changed = false, note = ""),
            MixerMacro(Macro.MOOD, 0.3f, 0.4f, changed = false, note = "app knob; file 0.40"),
            MixerMacro(Macro.SPACE, 0.55f, 0.55f, changed = false, note = "heard 0.66 (x1.20)"),
        ),
        changes = listOf(
            CaptureRow(Scope.VIBE, null, null, 3, CaptureField.VOLUME, null, 0.5f, 0.315f),
            CaptureRow(Scope.VIBE, null, null, 5, CaptureField.PAN, null, 0f, 0.25f),
            CaptureRow(Scope.SECTION, 1, "groove", 4, CaptureField.REVERB_SEND, null, 0.2f, 0.35f),
            CaptureRow(Scope.VIBE, null, null, null, CaptureField.MACRO, Macro.ENERGY, 0.5f, 0.6f),
        ),
        capture = MixerCapture(
            ok = true,
            message = "",
            file = "~/.config/orpheus-dj/mix-captures/rust-belt-sunrise-1.json",
            rows = 4,
            stale = false,
        ),
        notice = "This send is 0 here, so a whole-vibe edit can't scale it; switch to section scope.",
        pins = setOf(3),
        agentTouched = setOf(5, 6),
        history = listOf(
            ChangeRow(
                1, "you", "KICK · pan", "0.00", "0.04", "whole vibe", ago(3), on = true, covered = false,
                writes = listOf(CaptureRow(Scope.VIBE, null, null, 0, CaptureField.PAN, null, 0f, 0.04f)),
            ),
            ChangeRow(2, "Orpheus", "TEXT · pan", "0.40", "0.34", "whole vibe", ago(3), on = false, covered = false),
            PointRow(3, "before the TEXT cuts", ago(2)),
            ChangeRow(4, "you", "TEXT · level", "-2.0 dB", "-7.3 dB", "First Light", ago(1), on = true, covered = true),
            ChangeRow(
                5, "Claude Code", "KICK · reverb", "0.08", "0.85", "Overhead", ago(0), on = true, covered = false,
                writes = listOf(
                    CaptureRow(Scope.SECTION, 2, "Overhead", 0, CaptureField.REVERB_SEND, null, 0.08f, 0.85f),
                    CaptureRow(Scope.SECTION, 3, "Overhead II", 0, CaptureField.REVERB_SEND, null, 0.08f, 0.85f),
                ),
            ),
        ),
        onCount = 3,
        loadedAtMs = ago(4),
    )

    // A different sine envelope per channel, with a little carrier wobble so the bins read as audio.
    private fun wave(c: Int): FloatArray {
        val w = FloatArray(400)
        val amp = 0.9f - 0.05f * c
        for (j in 0 until 200) {
            val env = 0.15f + 0.85f * abs(sin(j * PI.toFloat() * (c % 4 + 1) / 200f + c * 0.7f))
            w[2 * j] = -amp * env * (0.7f + 0.3f * cos(j * 1.7f + c))
            w[2 * j + 1] = amp * env * (0.7f + 0.3f * sin(j * 1.3f + c))
        }
        return w
    }

    private val meters = MeterSnapshot(
        lufsShort = -14.2,
        lufsIntegrated = -14.8,
        preClipPeakDb = -0.6,
        pastKneePct = 2.3,
        correlation = 0.82,
        channels = BenchMeters.CHANNEL_NAMES.mapIndexed { c, name ->
            ChannelMeter(
                name = name,
                relLu = if (c == BenchMeters.CHANNEL_NAMES.size - 2) 0.0 else -4.0 - c * 1.3,
                peakDb = -3.0 - c * 2.5,
                pan = 0.0,
                subPct = 0.0,
                lowPct = 0.0,
                wave = wave(c),
            )
        },
        section = 1,
    )

    /** Touch input in scene pixels, with the frame clock advanced between events. */
    private class Touch(val scene: ImageComposeScene) {
        var now = 0L

        fun frame() {
            now += 16
            scene.render(now * 1_000_000)
        }

        fun pointer(type: PointerEventType, at: Offset) {
            val button = if (type == PointerEventType.Move) null else PointerButton.Primary
            scene.sendPointerEvent(type, at, timeMillis = now, type = PointerType.Touch, button = button)
            frame()
        }

        /** Taps the node tagged [tag] 20 dp in from its left edge (where a mixer row shows its name), or its centre if narrower. */
        fun tap(tag: String) {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            walk(scene.semanticsOwners.first().rootSemanticsNode)
            val b = out.first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.boundsInRoot
            val at = Offset(minOf(b.left + 40f, b.center.x), b.center.y)
            pointer(PointerEventType.Press, at)
            pointer(PointerEventType.Release, at)
            repeat(20) { frame() }
        }

        /** A slow upward drag (no fling) of [dy] pixels at [x]. */
        fun dragUp(x: Float, fromY: Float, dy: Float) {
            pointer(PointerEventType.Press, Offset(x, fromY))
            val steps = 40
            for (k in 1..steps) pointer(PointerEventType.Move, Offset(x, fromY - dy * k / steps))
            repeat(10) { frame() }
            pointer(PointerEventType.Release, Offset(x, fromY - dy))
            repeat(60) { frame() }
        }
    }

    private fun render(name: String, w: Int, h: Int, scale: Float = 1f, interact: Touch.() -> Unit = {}) =
        renderScene(name, w, h, interact, scale) { feature -> AiMixerPanel(feature, Modifier.fillMaxSize()) }

    private val pulsar: PulsarFeature = run {
        val base = PulsarViewModel.previewFeature()
        object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = true))
            override val vibeNavFlow: StateFlow<VibeNavState> =
                MutableStateFlow(VibeNavState("Orphic Lore", "Dog House", "Stay Asleep", progress = 0.62f))
        }
    }

    /** The open sheet as the app composes it on the stage, told [layout]'s chrome overhang. */
    @Composable
    private fun Stage(feature: AiMixerFeature, layout: DjLayout) {
        CompositionLocalProvider(LocalStageChromeOverhang provides stageChromeOverhang(layout)) {
            MixerSheetContent(feature, onDismiss = {})
        }
    }

    /**
     * The sheet over a desktop dock's stage: a stand-in for the top bar and song band (108dp, as DjAppTvChrome lays
     * them out), the stage, then the real bottom bar drawn after it so its dome and pill rise over the sheet.
     */
    private fun renderDock(name: String, w: Int, h: Int, interact: Touch.() -> Unit = {}) = renderScene(name, w, h, interact) { feature ->
        Column(Modifier.fillMaxSize().background(MixerInk.sourceCard)) {
            Spacer(Modifier.height(108.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { Stage(feature, DjLayout.LargeScreen) }
            DjTvBottomBar(
                panels = listOf(DjTab, MixTab, PulsarTab, AiTab),
                isDocked = { it == AiTab },
                onToggle = {},
                pulsarFeature = pulsar,
                onTogglePlayback = {},
                domeRingSize = 160.dp,
                focusHighlights = false,
            )
        }
    }

    /** The sheet in the real phone scaffold, its bar's ring rising over the stage's bottom edge. */
    private fun renderPhone(name: String, interact: Touch.() -> Unit = {}) = renderScene(name, 360, 780, interact) { feature ->
        DjAppNavScaffold(
            isSelected = { it == AiTab },
            onItemClick = {},
            layout = DjLayout.Portrait,
            pulsarFeature = pulsar,
            timerFeature = TimerViewModel.previewFeature(),
            onTogglePlayback = {},
            tabs = djTabs.map { if (it == HornTab) AiTab else it },
            modifier = Modifier.fillMaxSize().background(MixerInk.sourceCard),
        ) { Stage(feature, DjLayout.Portrait) }
    }

    /** [scale] is the canvas shrink the legible text undoes: 0.652 on the Fold, 1 at native density. */
    private fun renderScene(
        name: String,
        w: Int,
        h: Int,
        interact: Touch.() -> Unit = {},
        scale: Float = 1f,
        content: @Composable (AiMixerFeature) -> Unit,
    ) {
        val feature = AiMixerViewModel.previewFeature(state, meters)
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) {
            CompositionLocalProvider(LocalCanvasScale provides scale) {
                OrpheusTheme { content(feature) }
            }
        }
        try {
            val touch = Touch(scene)
            repeat(4) { touch.frame() }
            touch.interact()
            touch.frame()
            val png = scene.render(touch.now * 1_000_000).encodeToData()!!.bytes
            val out = File("build/djapp-render/mixer").apply { mkdirs() }
            File(out, "$name.png").writeBytes(png)
            println("wrote ${File(out, "$name.png").absolutePath}")
        } catch (t: Throwable) {
            println("render $name failed: $t")
        } finally {
            scene.close()
        }
    }

    @Test fun tablet() = render("tablet", 834, 1194)

    // The Fold's canvas beside the 440dp chat, and the Fold's whole canvas with the chat collapsed.
    @Test fun foldBesideChat() = render("fold_beside_chat_840x1100", 840, 1100, scale = FOLD_SCALE)

    @Test fun foldFull() = render("fold_full_1280x1153", 1280, 1153, scale = FOLD_SCALE)

    // The changes popover open from the header.
    @Test fun foldHistory() = render("fold_history_840x1100", 840, 1100, scale = FOLD_SCALE) { tap(HISTORY_TAG) }

    // The History popover from the header, then the compact list scrolled to its History block.
    @Test fun historyPopover() = render("history_popover_1280x800", 1280, 800) { tap(HISTORY_TAG) }

    @Test fun historyCompact() = render("history_compact", 360, 780) {
        repeat(3) { dragUp(x = 180f * 2, fromY = 700f * 2, dy = 600f * 2) }
    }

    @Test fun desktop() = render("desktop", 1280, 800)

    // Short windows get the compact list too, with pan, R and D inline in each row.
    @Test fun phoneLandscape() = render("phone_landscape", 800, 360)

    // Inline sends leave the expanded row just the level fader.
    @Test fun phoneLandscapeExpanded() = render("phone_landscape_expanded", 800, 360) { tap(rowTag(0)) }

    @Test fun phone() = render("phone", 360, 780)

    // BASS opened: its level fader (amber at -4 dB) and pan, R and D under the row.
    @Test fun phoneExpanded() = render("phone_expanded", 360, 780) { tap(rowTag(3)) }

    // Scrolled to the end: sources, bus, the changes list, Capture and Reset, the result and the notice.
    @Test fun phoneChanges() = render("phone_changes", 360, 780) {
        repeat(3) { dragUp(x = 180f * 2, fromY = 700f * 2, dy = 600f * 2) }
    }

    // The dock's dome pill over the sheet: 1280x800 leaves the strips 440dp under the clearance, tight but whole.
    @Test fun dockSheet() = renderDock("dock_sheet_1280x800", 1280, 800)

    @Test fun dockSheetTall() = renderDock("dock_sheet_1512x982", 1512, 982)

    // The wide layout's floor: exactly 400dp of content height still gets whole strips.
    @Test fun wideFloor() = render("wide_400", 1280, 400)

    // Scrolled to the end in the phone scaffold: the last rows clear the bar's raised ring.
    @Test fun phoneSheetChanges() = renderPhone("phone_sheet_changes") {
        repeat(3) { dragUp(x = 180f * 2, fromY = 560f * 2, dy = 480f * 2) }
    }

}
