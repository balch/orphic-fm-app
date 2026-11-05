package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import org.balch.orpheus.ui.panels.LocalPanelTopInset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.cowbell.bell3d.BellCamera
import org.balch.orpheus.features.cowbell.dj.CowbellDjCollapseTag
import org.balch.orpheus.features.cowbell.dj.CowbellDjPanel
import org.balch.orpheus.features.cowbell.bell3d.BellTransition
import org.balch.orpheus.features.cowbell.bell3d.FillPainter
import org.balch.orpheus.features.cowbell.bell3d.LIQUID_SECONDS
import org.balch.orpheus.features.cowbell.bell3d.LiquidRenderer
import org.balch.orpheus.features.cowbell.bell3d.SHARDS_SECONDS
import org.balch.orpheus.features.cowbell.bell3d.ShardRenderer
import org.balch.orpheus.features.cowbell.bell3d.drawBellMesh
import org.balch.orpheus.features.cowbell.bell3d.forMode
import org.balch.orpheus.features.cowbell.bell3d.paddedMeshFrame
import org.balch.orpheus.features.cowbell.shake.FillPhysics
import org.balch.orpheus.features.cowbell.shake.FillStrikes
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.random.Random
import kotlin.test.Test

/**
 * PNGs of the panel under a slot's 32dp header bar, to look at. Asserts nothing. Sizes name the
 * panel body, as [CowbellPanelFitTest] does; the header comes on top.
 *
 * ./gradlew :features:cowbell:jvmTest --tests '*CowbellPanelRenderHarness*' -PrenderHarness
 */
class CowbellPanelRenderHarness {

    private fun render(
        name: String,
        width: Int,
        bodyHeight: Int,
        moreOpen: Boolean,
        state: CowbellUiState = CowbellUiState(),
    ) {
        CowbellScene(width, bodyHeight + 32) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(CowbellPanelColor.copy(alpha = 0.12f), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .padding(start = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("BELL", color = CowbellPanelColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                CowbellPanelContent(
                    feature = CowbellViewModel.previewFeature(state),
                    modifier = Modifier.height(bodyHeight.dp).fillMaxWidth(),
                    isExpanded = true,
                    moreOpenByDefault = moreOpen,
                )
            }
        }.use { s ->
            s.save(name)
            println("[cowbell-render] build/cowbell-panel/$name.png")
        }
    }

    /** The DJ panel with its controls open, then tucked away. */
    @Test
    fun renderDjPanels() {
        for ((w, h, scale) in listOf(Triple(412, 290, 1f), Triple(412, 205, 1.3f))) {
            CowbellScene(w, h, scale) {
                CowbellDjPanel(CowbellViewModel.previewFeature(), Modifier.fillMaxSize())
            }.use { s ->
                s.frames(64)
                s.save("dj-${w}x$h-open")
                val at = s.tagged(CowbellDjCollapseTag).laidOut.center
                s.press(at)
                s.release(at)
                s.frames(600)
                s.save("dj-${w}x$h-collapsed")
            }
        }
    }

    /** The shaker over its Fill chip, open and tucked away. */
    @Test
    fun renderDjShaker() {
        CowbellScene(412, 290) {
            CowbellDjPanel(
                CowbellViewModel.previewFeature(CowbellUiState(mode = CowbellMode.SHAKE, fill = CowbellFill.SEEDS)),
                Modifier.fillMaxSize(),
            )
        }.use { s ->
            s.frames(64)
            s.save("dj-412x290-shake")
            val at = s.tagged(CowbellDjCollapseTag).laidOut.center
            s.press(at)
            s.release(at)
            s.frames(600)
            s.save("dj-412x290-shake-collapsed")
        }
    }

    /** A phone's first column, with Motion under the chips. */
    @Test
    fun renderDjMotion() {
        CowbellScene(412, 290) {
            CowbellDjPanel(
                CowbellViewModel.previewFeature(CowbellUiState(motionAvailable = true, motion = true)),
                Modifier.fillMaxSize(),
            )
        }.use { s ->
            s.frames(64)
            s.save("dj-412x290-motion")
        }
    }

    /** The body menu open over the DJ panel. */
    @Test
    fun renderBodyMenu() {
        CowbellScene(412, 290) {
            CowbellDjPanel(CowbellViewModel.previewFeature(), Modifier.fillMaxSize())
        }.use { s ->
            s.frames(64)
            val at = s.tagged(CowbellBodyTag).laidOut.center
            s.press(at)
            s.release(at)
            s.frames(600)
            s.save("dj-412x290-body-menu")
        }
    }

    /** The phone-only bell + bender panel, at a phone's top slot. */
    @Test
    fun renderBenderPanels() {
        for ((w, h) in listOf(380 to 260, 380 to 180)) {
            CowbellScene(w, h) {
                CowbellBenderPanel(
                    feature = CowbellViewModel.previewFeature(),
                    modifier = Modifier.fillMaxSize(),
                    isExpanded = true,
                    showCollapsedHeader = false,
                )
            }.use { s ->
                s.save("bender-${w}x$h")
                println("[cowbell-render] build/cowbell-panel/bender-${w}x$h.png")
            }
        }
    }

    /** The phone's top section: the 44dp prev/next header is drawn over the body. */
    @Test
    fun renderPhoneTopSection() {
        CowbellScene(380, 380) {
            Box {
                CompositionLocalProvider(LocalPanelTopInset provides 44.dp) {
                    CowbellPanelContent(
                        feature = CowbellViewModel.previewFeature(),
                        modifier = Modifier.fillMaxSize(),
                        isExpanded = true,
                    )
                }
                Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp, vertical = 4.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp)))
            }
        }.use { s ->
            s.save("panel-phone-380x380")
            println("[cowbell-render] build/cowbell-panel/panel-phone-380x380.png")
        }
    }

    @Test
    fun renderPanels() {
        render("panel-320x330-closed", 320, 330, moreOpen = false)
        render("panel-320x330-open", 320, 330, moreOpen = true)
        render("panel-320x260-open", 320, 260, moreOpen = true)
        render(
            "panel-320x330-shake", 320, 330, moreOpen = true,
            state = CowbellUiState(mode = CowbellMode.SHAKE, fill = CowbellFill.SEEDS),
        )
    }

    /** The bare bell at a frozen time, filling the image so any stroke past the box would clip. */
    private fun renderBell(name: String, mode: CowbellMode, flash: Float = 0f, glow: Float = 0f) {
        CowbellScene(160, 300) {
            Canvas(Modifier.fillMaxSize()) {
                val padded = paddedMeshFrame(size, BellCamera.forMode(mode)) ?: return@Canvas
                inset(padded.pad) { drawBellMesh(padded.frame, timeSeconds = 3.0, flash = flash, glow = glow) }
            }
        }.use { s ->
            s.save(name)
            println("[cowbell-render] build/cowbell-panel/$name.png")
        }
    }

    /** A frozen morph frame at progress [p], through the liquid shader. */
    private fun renderMorph(name: String, to: CowbellMode, p: Double) {
        val liquid = LiquidRenderer()
        val transition = BellTransition(BellCamera.forMode(CowbellMode.TAP), BellCamera.forMode(to), 0.0, LIQUID_SECONDS)
        CowbellScene(160, 300) {
            Canvas(Modifier.fillMaxSize()) {
                drawBellMorph(transition, LIQUID_SECONDS * p, blobSeconds = 3.0, pull = 0f, flash = 0f, glow = 0f, liquid = liquid)
            }
        }.use { s ->
            s.save(name)
            println("[cowbell-render] build/cowbell-panel/$name.png")
        }
        liquid.dispose()
    }

    /** A frozen shard-morph frame at progress [p]. */
    private fun renderShards(name: String, to: CowbellMode, p: Double) {
        val shards = ShardRenderer()
        val transition = BellTransition(BellCamera.forMode(CowbellMode.TAP), BellCamera.forMode(to), 0.0, SHARDS_SECONDS)
        CowbellScene(160, 300) {
            Canvas(Modifier.fillMaxSize()) {
                drawBellMorph(transition, SHARDS_SECONDS * p, blobSeconds = 3.0, pull = 0f, flash = 0f, glow = 0f, liquid = null, shards = shards)
            }
        }.use { s ->
            s.save(name)
            println("[cowbell-render] build/cowbell-panel/$name.png")
        }
    }

    @Test
    fun renderShardMorphs() {
        listOf(0.04, 0.15, 0.3, 0.5, 0.7, 0.85, 0.97).forEach { p -> renderShards("morph-shards-tap-synth-$p", CowbellMode.SYNTH, p) }
        listOf(0.3, 0.5).forEach { p -> renderShards("morph-shards-tap-shake-$p", CowbellMode.SHAKE, p) }
    }

    @Test
    fun renderMorphs() {
        listOf(0.15, 0.35, 0.5, 0.75, 0.95).forEach { p -> renderMorph("morph-tap-synth-$p", CowbellMode.SYNTH, p) }
        renderMorph("morph-tap-shake-0.5", CowbellMode.SHAKE, 0.5)
    }

    @Test
    fun renderBells() {
        renderBell("bell-tap", CowbellMode.TAP)
        renderBell("bell-synth", CowbellMode.SYNTH)
        renderBell("bell-shake", CowbellMode.SHAKE)
        renderBell("bell-tap-flash", CowbellMode.TAP, flash = 1f, glow = 1f)
    }

    /** The SHAKE bell with [fill] settled by a seeded tilt into a corner, at a frozen time. */
    private fun renderFill(name: String, fill: CowbellFill, flash: Float = 0f) {
        val physics = FillPhysics(fill, Random(11))
        val out = FillStrikes()
        repeat(360) {
            physics.advance(1.0 / 60.0, 0.5f, -0.7f, 0f, 0f, out)
            out.drain { }
        }
        val painter = FillPainter()
        CowbellScene(160, 300) {
            Canvas(Modifier.fillMaxSize()) {
                val camera = BellCamera.forMode(CowbellMode.SHAKE)
                val padded = paddedMeshFrame(size, camera) ?: return@Canvas
                inset(padded.pad) {
                    drawBellMesh(padded.frame, timeSeconds = 3.0, flash = flash, glow = flash) {
                        painter.draw(this, fill, physics.particles, camera, 0f, padded.frame, flash)
                    }
                }
            }
        }.use { s ->
            s.save(name)
            println("[cowbell-render] build/cowbell-panel/$name.png")
        }
    }

    @Test
    fun renderFills() {
        renderFill("bell-shake-marble", CowbellFill.MARBLE)
        renderFill("bell-shake-beads", CowbellFill.BEADS)
        renderFill("bell-shake-seeds", CowbellFill.SEEDS)
        renderFill("bell-shake-jingles", CowbellFill.JINGLES)
    }
}
