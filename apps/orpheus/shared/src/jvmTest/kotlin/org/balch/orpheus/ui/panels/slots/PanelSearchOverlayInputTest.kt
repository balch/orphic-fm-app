@file:OptIn(InternalComposeUiApi::class)

package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals

// Key events are built with the internal KeyEvent constructor: scenes reject AWT events.
/** Enter, Escape and scrim taps on [PanelSearchOverlay], driven through a headless scene. */
class PanelSearchOverlayInputTest {

    private val candidates = PanelNavEntries.map { SearchCandidate(it, it.shortLabel, "") }
    private val picked = mutableListOf<PanelId>()
    private var dismissed = 0

    private fun withOverlay(
        autoFocus: Boolean,
        initialQuery: String = "",
        block: (ImageComposeScene) -> Unit,
    ) {
        picked.clear()
        dismissed = 0
        val scene = ImageComposeScene(700, 900, Density(1f)) {
            OrpheusTheme {
                PanelSearchOverlay(
                    candidates = candidates,
                    colors = emptyMap(),
                    autoFocus = autoFocus,
                    onPick = { picked += it },
                    onDismiss = { dismissed++ },
                    initialQuery = initialQuery,
                )
            }
        }
        try {
            // Let the focus request in LaunchedEffect run before sending input.
            repeat(5) { scene.render(it * 16_000_000L) }
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.press(key: Key) {
        sendKeyEvent(ComposeKeyEvent(key, KeyEventType.KeyDown))
        sendKeyEvent(ComposeKeyEvent(key, KeyEventType.KeyUp))
        render(100_000_000L)
    }

    private fun ImageComposeScene.tap(x: Float, y: Float) {
        sendPointerEvent(PointerEventType.Press, Offset(x, y))
        sendPointerEvent(PointerEventType.Release, Offset(x, y))
        render(200_000_000L)
    }

    @Test
    fun enterPicksTopMatchWithFieldFocused() = withOverlay(autoFocus = true, initialQuery = "de") { scene ->
        scene.press(Key.Enter)
        assertEquals(listOf(PanelId.DELAY), picked)
    }

    @Test
    fun enterPicksTopMatchWithoutFieldFocus() = withOverlay(autoFocus = false, initialQuery = "de") { scene ->
        scene.press(Key.Enter)
        assertEquals(listOf(PanelId.DELAY), picked)
    }

    @Test
    fun escapeDismissesInBothFocusModes() {
        withOverlay(autoFocus = true) { scene ->
            scene.press(Key.Escape)
            assertEquals(1, dismissed)
        }
        withOverlay(autoFocus = false) { scene ->
            scene.press(Key.Escape)
            assertEquals(1, dismissed)
        }
    }

    /** DesktopSynthScreen's shape: a focusable key-handling Column with the overlay as its sibling. */
    @Test
    fun keysSkipTheSiblingSynthHandlerWhileSearchHoldsFocus() {
        for (autoFocus in listOf(true, false)) {
            val synthKeys = mutableListOf<Key>()
            var open by mutableStateOf(false)
            val columnFocus = FocusRequester()
            val scene = ImageComposeScene(700, 900, Density(1f)) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize()
                                .focusRequester(columnFocus)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) synthKeys += event.key
                                    false
                                },
                        ) {}
                        if (open) {
                            PanelSearchOverlay(
                                candidates = candidates,
                                colors = emptyMap(),
                                autoFocus = autoFocus,
                                onPick = {},
                                onDismiss = {
                                    open = false
                                    columnFocus.requestFocus()
                                },
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                }
            }
            var t = 0L
            fun frames(n: Int = 3) = repeat(n) { scene.render(t).also { t += 16_000_000L } }
            fun type(key: Key) {
                scene.sendKeyEvent(ComposeKeyEvent(key, KeyEventType.KeyDown))
                scene.sendKeyEvent(ComposeKeyEvent(key, KeyEventType.KeyUp))
                frames(1)
            }
            try {
                frames()
                columnFocus.requestFocus()
                frames()
                type(Key.A)
                assertEquals(listOf(Key.A), synthKeys, "column handles keys before search (autoFocus=$autoFocus)")

                open = true
                frames(5)
                type(Key.S)
                type(Key.Escape)
                assertEquals(listOf(Key.A), synthKeys, "keys reached the synth handler under search (autoFocus=$autoFocus)")

                frames()
                type(Key.D)
                assertEquals(listOf(Key.A, Key.D), synthKeys, "focus returns after closing (autoFocus=$autoFocus)")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun scrimTapDismissesButCardTapDoesNot() = withOverlay(autoFocus = false) { scene ->
        // The card is centered in the top 520dp; (350, 5) is bare scrim, (350, 100) is card.
        scene.tap(350f, 100f)
        assertEquals(0, dismissed)
        scene.tap(5f, 5f)
        assertEquals(1, dismissed)
    }
}
