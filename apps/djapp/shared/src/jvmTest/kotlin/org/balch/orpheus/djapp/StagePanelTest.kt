package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [StageSheet] in a phone's stage: the portrait panel, or with `landscape` the side sheet. */
class StagePanelTest {
    private class Panel(landscape: Boolean = false) {
        var dismissed = 0
        var open by mutableStateOf(true)
        var content = Rect.Zero
        var shown = false
        var dispatcher: NavigationEventDispatcher? = null
        private var now = 0L
        val scene = ImageComposeScene(if (landscape) 700 else 360, if (landscape) 360 else 700, Density(1f)) {
            OrpheusTheme {
                dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
                StageSheet(isLandscape = landscape, open = open, onDismiss = { dismissed++ }) {
                    DisposableEffect(Unit) {
                        shown = true
                        onDispose { shown = false }
                    }
                    Box(Modifier.fillMaxSize().onGloballyPositioned { content = it.boundsInRoot() })
                }
            }
        }
        private val back by lazy { DirectNavigationEventInput().also { assertNotNull(dispatcher).addInput(it) } }

        init { frames(1_000) }

        fun frames(ms: Long) = repeat((ms / 16).toInt()) { now += 16; scene.render(now * 1_000_000) }

        fun tap(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = now, type = PointerType.Touch, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = now + 20, type = PointerType.Touch, button = PointerButton.Primary)
            frames(32)
        }

        fun pressBack() = back.backCompleted()

        fun close() = scene.close()
    }

    @Test
    fun thePanelRisesFromTheStageBottomToMostOfItsHeight() {
        val panel = Panel()
        try {
            assertEquals(700f, panel.content.bottom, 1f)
            assertTrue(panel.content.top in 70f..140f, "top at ${panel.content.top}")
        } finally { panel.close() }
    }

    @Test
    fun aTapOnTheDimmedStageDismisses() {
        val panel = Panel()
        try {
            panel.tap(Offset(180f, 40f))
            assertEquals(1, panel.dismissed)
        } finally { panel.close() }
    }

    @Test
    fun aTapOnThePanelDoesNot() {
        val panel = Panel()
        try {
            panel.tap(Offset(180f, 500f))
            assertEquals(0, panel.dismissed)
        } finally { panel.close() }
    }

    @Test
    fun backDismisses() {
        val panel = Panel()
        try {
            panel.pressBack()
            assertEquals(1, panel.dismissed)
        } finally { panel.close() }
    }

    @Test
    fun backDismissesTheSideSheet() {
        val sheet = Panel(landscape = true)
        try {
            sheet.pressBack()
            assertEquals(1, sheet.dismissed)
        } finally { sheet.close() }
    }

    // Closed, the sheet stays up only to slide away; Back belongs to whatever is under it by then.
    @Test
    fun backDuringTheExitIsNotTheSheets() {
        val sheet = Panel(landscape = true)
        try {
            sheet.open = false
            sheet.frames(32)
            sheet.pressBack()
            assertEquals(0, sheet.dismissed)
        } finally { sheet.close() }
    }

    @Test
    fun thePanelSlidesDownAndThenLeaves() {
        val panel = Panel()
        try {
            val top = panel.content.top
            panel.open = false
            panel.frames(80)
            assertTrue(panel.shown && panel.content.top > top + 20f, "mid-exit top at ${panel.content.top} from $top")
            panel.frames(1_000)
            assertFalse(panel.shown, "gone once the exit has run")
        } finally { panel.close() }
    }

    @Test
    fun theSideSheetSlidesRightAndThenLeaves() {
        val sheet = Panel(landscape = true)
        try {
            val left = sheet.content.left
            sheet.open = false
            sheet.frames(80)
            assertTrue(sheet.shown && sheet.content.left > left + 20f, "mid-exit left at ${sheet.content.left} from $left")
            sheet.frames(1_000)
            assertFalse(sheet.shown, "gone once the exit has run")
        } finally { sheet.close() }
    }
}
