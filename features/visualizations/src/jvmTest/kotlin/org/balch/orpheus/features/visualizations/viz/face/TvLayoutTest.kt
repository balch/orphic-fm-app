package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvLayoutTest {

    private data class Case(val name: String, val window: Size, val stage: Rect?)

    /** Stages shaped the way the DJ app reports them: the band between the header and the nav. */
    private val staged = listOf(
        Case("phone portrait", Size(360f, 780f), Rect(0f, 44f, 360f, 700f)),
        Case("phone landscape", Size(780f, 360f), Rect(72f, 40f, 780f, 360f)),
        Case("desktop", Size(1440f, 900f), Rect(0f, 60f, 1440f, 752f)),
    )

    private val unstaged = listOf(
        Case("wide", Size(1280f, 800f), null),
        Case("tall", Size(400f, 860f), null),
        Case("square", Size(700f, 700f), null),
        Case("tiny", Size(10f, 10f), null),
        Case("empty", Size(0f, 0f), null),
    )

    /** Each of these must be treated exactly as if no stage had been reported at all. */
    private val degenerate = listOf(
        Case("zero stage", Size(360f, 780f), Rect.Zero),
        Case("negative stage", Size(360f, 780f), Rect(200f, 500f, 100f, 100f)),
        Case("NaN stage", Size(360f, 780f), Rect(Float.NaN, 0f, 360f, 700f)),
        Case("stage off the window", Size(360f, 780f), Rect(900f, 900f, 1200f, 1200f)),
        Case("stage larger than the window", Size(360f, 780f), Rect(-100f, -100f, 900f, 1400f)),
    )

    private val cases = staged + unstaged + degenerate

    private fun Case.layout(): TvLayout = tvLayout(window, stage)

    private fun TvLayout.all(): List<Pair<String, Rect>> = listOf(
        "ears" to ears, "cabinet" to cabinet, "screen" to screen, "controls" to controls,
        "inset" to inset, "banner" to banner, "ticker" to ticker,
    )

    /** Cabinet plus antenna: the whole silhouette the stage has to hold. */
    private fun TvLayout.set(): Rect = Rect(cabinet.left, ears.top, cabinet.right, cabinet.bottom)

    private fun assertInside(outer: Rect, inner: Rect, what: String, where: String) {
        assertTrue(
            inner.left >= outer.left - EPS && inner.top >= outer.top - EPS &&
                inner.right <= outer.right + EPS && inner.bottom <= outer.bottom + EPS,
            "$what $inner escapes $outer at $where",
        )
    }

    @Test fun `every rect is finite and never negative`() {
        cases.forEach { case ->
            case.layout().all().forEach { (name, r) ->
                assertTrue(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite(),
                    "$name is not finite at ${case.name}: $r")
                assertTrue(r.width >= 0f && r.height >= 0f, "$name has negative size at ${case.name}: $r")
            }
        }
    }

    @Test fun `the set nests screen in cabinet and the overlays in screen`() {
        cases.forEach { case ->
            val l = case.layout()
            assertInside(l.cabinet, l.screen, "screen", case.name)
            assertInside(l.cabinet, l.controls, "controls", case.name)
            assertInside(l.screen, l.inset, "inset", case.name)
            assertInside(l.screen, l.banner, "banner", case.name)
            assertInside(l.screen, l.ticker, "ticker", case.name)
        }
    }

    @Test fun `the story graphic never lands on the lower third`() {
        cases.forEach { case ->
            val l = case.layout()
            // The frame and the caption tab are drawn too, so the face rect alone is not the test.
            val drawn = l.insetFrame()
            assertTrue(drawn.bottom <= l.banner.top + EPS, "inset $drawn hits banner ${l.banner} at ${case.name}")
            assertInside(l.screen, drawn, "insetFrame", case.name)
        }
    }

    @Test fun `the antenna sits on the cabinet and the whole set fits the window`() {
        cases.forEach { case ->
            val l = case.layout()
            assertTrue(abs(l.ears.bottom - l.cabinet.top) < EPS,
                "ears ${l.ears} do not rest on cabinet ${l.cabinet} at ${case.name}")
            val window = Rect(0f, 0f, case.window.width, case.window.height)
            assertInside(window, l.cabinet, "cabinet", case.name)
            assertInside(window, l.ears, "ears", case.name)
        }
    }

    @Test fun `the set fills the stage it is given, inset and centred`() {
        staged.forEach { case ->
            val stage = requireNotNull(case.stage)
            val set = case.layout().set()
            val pad = min(stage.width, stage.height) * STAGE_INSET
            val room = Rect(stage.left + pad, stage.top + pad, stage.right - pad, stage.bottom - pad)

            assertInside(room, set, "set", case.name)
            assertTrue(abs(set.center.x - stage.center.x) < 1f,
                "set $set is not centred across stage $stage at ${case.name}")
            assertTrue(abs(set.center.y - stage.center.y) < 1f,
                "set $set is not centred down stage $stage at ${case.name}")
            // Scaled to FIT: one of the two dimensions has to be used up.
            val slack = min(room.width - set.width, room.height - set.height)
            assertTrue(slack < 1f, "set $set leaves ${slack}px in $room at ${case.name}")
        }
    }

    @Test fun `a stage that cannot be used is the same as no stage at all`() {
        degenerate.forEach { case ->
            assertEquals(tvLayout(case.window), case.layout(), "stage was not ignored at ${case.name}")
        }
    }

    @Test fun `the glass is four by three`() {
        cases.filter { it.window.width > 1f }.forEach { case ->
            val screen = case.layout().screen
            val aspect = screen.width / screen.height
            assertTrue(abs(aspect - 4f / 3f) < 4f / 3f * 0.01f, "screen aspect $aspect at ${case.name}")
        }
    }

    private companion object {
        const val EPS = 0.01f
    }
}
