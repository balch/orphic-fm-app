package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchFamily
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/**
 * Renders the bay with a few cables (All, Flux focused) and the legacy LFO patch with LFO focused
 * (popover and sheet spacing) to
 * `build/widgets-render/patch-bay-*.png`. Asserts nothing. Run:
 *
 *   ./gradlew :ui:widgets:jvmTest --tests '*PatchBayRenderHarness*' --rerun -PrenderHarness
 */
class PatchBayRenderHarness {

    private val patch = DuoPatch()
        .connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.4f)
        .connect(PatchSource.LFO_A90, PatchDest.PITCH, amount = 0.15f)
        .connect(PatchSource.WAVES_2, PatchDest.HARMONICS, amount = -0.3f)
        .connect(PatchSource.FLUX_X1, PatchDest.TIMBRE, amount = 0.35f)
        .setBridged(PatchDest.ENV, false)
        .connect(PatchSource.FLUX_X3, PatchDest.ENV, VoiceSlot.A, amount = -0.2f)
        .connect(PatchSource.VOICE_FM, PatchDest.TIMBRE, amount = 0.25f)

    /** The legacy LFO conversion on OSC, where every knob midpoint shares one x. */
    private val legacyLfo = DuoPatch()
        .connect(PatchSource.LFO_AB, PatchDest.TIMBRE, amount = 0.5f)
        .connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.25f)
        .connect(PatchSource.LFO_B, PatchDest.HARMONICS, amount = 0.25f)
        .connect(PatchSource.LFO_A90, PatchDest.PITCH, amount = 0.04f)
        .connect(PatchSource.LFO_AB, PatchDest.FM, amount = 0.5f)

    private class Case(
        val name: String,
        val patch: DuoPatch,
        val engine: OrpheusEngineId,
        val focus: PatchFocus,
        val widthDp: Int = 560,
        val heightDp: Int = 520,
        val roomy: Boolean = false,
    )

    @Test
    fun renderBay() {
        try {
            val outDir = File("build/widgets-render").apply { mkdirs() }
            listOf(
                Case("all", patch, OrpheusEngineId.GRN, PatchFocus.All),
                Case("flux", patch, OrpheusEngineId.GRN, PatchFocus.Family(PatchFamily.FLUX)),
                Case("lfo-legacy", legacyLfo, OrpheusEngineId.OSC, PatchFocus.Family(PatchFamily.LFO)),
                Case("lfo-legacy-sheet", legacyLfo, OrpheusEngineId.OSC, PatchFocus.Family(PatchFamily.LFO), 400, 700, roomy = true),
            ).forEach { case ->
                val name = case.name
                val scene = ImageComposeScene(case.widthDp * 2, case.heightDp * 2, Density(2f)) {
                    OrpheusTheme {
                        val state = remember { PatchBayState().also { it.focus = case.focus } }
                        PatchBay(
                            patch = case.patch,
                            engine = case.engine,
                            onPatchChange = {},
                            roomy = case.roomy,
                            state = state,
                            modifier = Modifier.fillMaxSize().background(OrpheusColors.pickerSurface).padding(10.dp),
                        )
                    }
                }
                try {
                    // Jack centres land on the first frame; cables draw from the second.
                    scene.render(0L)
                    scene.render(16_000_000L)
                    File(outDir, "patch-bay-$name.png")
                        .writeBytes(scene.render(500_000_000L).encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }
        } catch (t: Throwable) {
            println("PatchBayRenderHarness skipped: $t")
        }
    }
}
