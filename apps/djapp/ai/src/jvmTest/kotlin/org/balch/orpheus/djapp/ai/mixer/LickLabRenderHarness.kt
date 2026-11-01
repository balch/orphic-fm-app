package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.djapp.ai.licklab.ExportedLick
import org.balch.orpheus.djapp.ai.licklab.LickLabActions
import org.balch.orpheus.djapp.ai.licklab.LickLabFeature
import org.balch.orpheus.djapp.ai.licklab.LickLabUiState
import org.balch.orpheus.djapp.ai.licklab.LiveNote
import org.balch.orpheus.djapp.ai.licklab.Take
import org.balch.orpheus.djapp.ai.licklab.TakeNote
import org.balch.orpheus.djapp.ai.licklab.TrackChip
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/** Writes Lick Lab inside the wide mixer to build/djapp-render/mixer; asserts nothing (run with -PrenderHarness). */
class LickLabRenderHarness {

    private val mixer = AiMixerViewModel.previewFeature(
        AiMixerUiState(
            vibeName = "Test Vibe",
            sections = listOf("intro", "groove", "drop"),
            tracks = BenchMeters.CHANNEL_NAMES.take(8).mapIndexed { t, name ->
                MixerTrack(t, name, "SPK/MST", levelDb = 0f, pan = 0f, filePan = 0f, reverb = 0f, delay = 0f,
                    muted = false, soloed = false, silenced = false)
            },
        ),
        EMPTY_METERS,
    )

    // B minor from B3: degrees 0..6 are B C# D E F# G A.
    private val bMinor = listOf(0, 2, 3, 5, 7, 8, 10)

    private fun note(midi: Int, start: Double, end: Double?, degree: Int?) = LiveNote(midi, start, end, degree = degree)

    private val state = LickLabUiState(
        shown = true,
        armedTrack = 4,
        callerTrack = 3,
        recording = true,
        baseMidi = 59,
        keyLabel = "B minor · 0 = B3",
        scale = bMinor,
        poolSize = 2,
        chordRootDegree = 5,
        heldMidi = 62,
        tracks = listOf(3, 4, 5, 6).map { TrackChip(it, BenchMeters.CHANNEL_NAMES[it], "SPK/MST") },
        callNotes = listOf(
            note(71, 2.0, 2.5, 7), note(74, 2.5, 3.0, 9), note(78, 3.0, 4.0, 11), note(79, 4.0, 4.5, 12),
            note(78, 4.5, 5.0, 11), note(74, 5.0, 6.0, 9),
        ),
        answerNotes = listOf(
            note(62, 6.0, 6.5, 2), note(66, 6.5, 7.0, 4), note(67, 7.0, 7.5, 5), note(65, 7.5, 8.0, null),
            note(62, 8.0, 9.0, 2), note(59, 9.0, 9.4, 0), note(62, 9.5, null, 2),
        ),
        takes = listOf(
            Take(1, List(5) { TakeNote(it * 0.5, it * 0.5 + 0.4, 59 + it, 0.8f, false, -1f) }, 0, 4),
            Take(2, List(12) { TakeNote(it * 0.5, it * 0.5 + 0.4, 62, 0.8f, false, -1f) }, 1, 4, starred = true),
        ),
    )

    private val lab = object : LickLabFeature {
        override val stateFlow: StateFlow<LickLabUiState> = MutableStateFlow(state)
        override val actions: LickLabActions = LickLabActions.EMPTY
        override fun currentBeat() = 10.0
        override fun exportFor(takeId: Long) = if (takeId == 2L) {
            ExportedLick(List(70) { LickStep(0, 0.25f, 0.8f) }, 20, listOf(3), listOf(65), mergedCount = 2, clamped = mapOf(9 to 70))
        } else {
            ExportedLick(List(5) { LickStep(it, 0.5f, 0.8f) }, 4)
        }
    }

    private fun render(name: String, w: Int, h: Int) {
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) {
            OrpheusTheme { AiMixerPanel(mixer, Modifier.fillMaxSize(), lickLab = lab) }
        }
        try {
            repeat(4) { scene.render(it * 16_000_000L) }
            val png = scene.render(80_000_000L).encodeToData()!!.bytes
            val out = File("build/djapp-render/mixer").apply { mkdirs() }
            File(out, "$name.png").writeBytes(png)
            println("wrote ${File(out, "$name.png").absolutePath}")
        } finally {
            scene.close()
        }
    }

    @Test fun desktop() = render("lick_lab_1280x800", 1280, 800)

    @Test fun narrowWide() = render("lick_lab_700x560", 700, 560)
}
