package org.balch.orpheus.djapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.pulsar.kraken.KrakenPadSurface
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class KrakenPadTest {
    private var presses = 0
    private var releases = 0

    private fun scene() = ImageComposeScene(200, 120, Density(1f)) {
        OrpheusTheme {
            KrakenPadSurface(
                target = 0, engaged = false, latched = false, active = false,
                onPress = { presses++ }, onRelease = { releases++ },
            )
        }
    }

    private fun ImageComposeScene.pad(): SemanticsNode {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }.first { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Kraken") }
        }
    }

    @Test
    fun aTouchIsOnePressAndOneRelease() {
        val scene = scene()
        try {
            scene.render()
            val at = scene.pad().boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, at); scene.render()
            assertEquals(1 to 0, presses to releases)
            scene.sendPointerEvent(PointerEventType.Release, at); scene.render()
            assertEquals(1 to 1, presses to releases)
        } finally {
            scene.close()
        }
    }

    @Test
    fun aHeldKeyIsOnePressAndOneRelease() {
        val scene = scene()
        try {
            scene.render()
            scene.pad().config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
            scene.render()
            repeat(4) { scene.sendKeyEvent(keyEvent(KeyEventType.KeyDown)); scene.render() }
            scene.sendKeyEvent(keyEvent(KeyEventType.KeyUp)); scene.render()
            assertEquals(1 to 1, presses to releases, "auto-repeat must not re-press")
        } finally {
            scene.close()
        }
    }

    @Test
    fun removingAHeldPadReleasesOnce() {
        var shown by mutableStateOf(true)
        val scene = ImageComposeScene(200, 120, Density(1f)) {
            OrpheusTheme {
                if (shown) {
                    KrakenPadSurface(
                        target = 0, engaged = false, latched = false, active = false,
                        onPress = { presses++ }, onRelease = { releases++ },
                    )
                }
            }
        }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Press, scene.pad().boundsInRoot.center); scene.render()
            assertEquals(1 to 0, presses to releases)
            shown = false
            scene.render(); scene.render()
            assertEquals(1 to 1, presses to releases)
        } finally {
            scene.close()
        }
    }

    @Test
    fun removingAPadWithAHeldKeyReleasesOnce() {
        var shown by mutableStateOf(true)
        val scene = ImageComposeScene(200, 120, Density(1f)) {
            OrpheusTheme {
                if (shown) {
                    KrakenPadSurface(
                        target = 0, engaged = false, latched = false, active = false,
                        onPress = { presses++ }, onRelease = { releases++ },
                    )
                }
            }
        }
        try {
            scene.render()
            scene.pad().config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
            scene.render()
            scene.sendKeyEvent(keyEvent(KeyEventType.KeyDown)); scene.render()
            assertEquals(1 to 0, presses to releases)
            shown = false
            scene.render(); scene.render()
            assertEquals(1 to 1, presses to releases)
        } finally {
            scene.close()
        }
    }

    @Test
    fun losingFocusWithAHeldKeyReleasesOnce() {
        var other by mutableStateOf<FocusRequester?>(null)
        val scene = ImageComposeScene(200, 120, Density(1f)) {
            OrpheusTheme {
                KrakenPadSurface(
                    target = 0, engaged = false, latched = false, active = false,
                    onPress = { presses++ }, onRelease = { releases++ },
                    modifier = Modifier.size(100.dp),
                )
                val r = remember { FocusRequester() }
                other = r
                Box(Modifier.size(20.dp).focusRequester(r).focusable())
            }
        }
        try {
            scene.render()
            scene.pad().config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
            scene.render()
            scene.sendKeyEvent(keyEvent(KeyEventType.KeyDown)); scene.render()
            other!!.requestFocus(); scene.render()
            assertEquals(1 to 1, presses to releases)
            scene.sendKeyEvent(keyEvent(KeyEventType.KeyUp)); scene.render()
            assertEquals(1 to 1, presses to releases, "no double release")
        } finally {
            scene.close()
        }
    }

    private fun keyEvent(type: KeyEventType) = KeyEvent(Key.DirectionCenter, type)
}
