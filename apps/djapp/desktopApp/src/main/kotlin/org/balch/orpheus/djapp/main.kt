package org.balch.orpheus.djapp

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.djapp.bench.MixBench
import org.balch.orpheus.djapp.di.MixBenchExit
import org.balch.orpheus.ui.viz.PanelKeyBridge

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")

    application {
        val graph = remember { createGraphFactory<DjAppGraphDesktop.Factory>().create() }
        // Builds every @StartupRoot, then the graph's startup features.
        remember { graph.startupInitializer.run() }
        val bench = remember { if (MixBench.enabled) MixBench.launch(graph) else null }

        val windowState = rememberWindowState(width = 360.dp, height = 780.dp)

        // Compose delivers keys along the focus path, so on a window nobody has clicked yet the
        // app's own root handler never sees one and no key would bring the faded panels back.
        // The window hook sees every key regardless of focus, and runs the exact same rule.
        val panelKeys = remember { PanelKeyBridge() }
        val vibeKeys = remember { VibeStepKeys() }
        val aiEdition = remember { graph.djTabContributions.any { it.route == AiTab } }

        Window(
            onPreviewKeyEvent = { panelKeys.onKeyEvent(it) },
            // Bubbling phase, after the focused element: see vibeStepForKey.
            onKeyEvent = { event ->
                // Reached only if no focused field took the paste, so the chat keeps its own path.
                if (aiEdition && isPasteShortcut(event)) {
                    return@Window offerClipboard(readClipboardText(), graph.sharedTextInbox)
                }
                when (vibeKeys.stepFor(event)) {
                    SkipDirection.NEXT -> { graph.playbackController.onSkipNext(); true }
                    SkipDirection.PREVIOUS -> { graph.playbackController.onSkipPrevious(); true }
                    null -> false
                }
            },
            onCloseRequest = {
                // Stop the bench page, any stem capture, then the meter poll and taps, before the engine goes away.
                bench?.close()
                MixBenchExit.runIfCreated()
                // Fade out audio before exiting to avoid crackles/pops.
                // Set volume to 0, let the C++ smoother ramp down (~50ms),
                // then stop the engine and exit.
                graph.synthEngine.setMasterVolume(0f)
                Thread.sleep(80)
                graph.synthOrchestrator.stop()
                exitApplication()
            },
            title = "Orphic DJ",
            state = windowState,
        ) {
            if (bench != null) {
                MenuBar {
                    Menu("Bench") {
                        Item("Open Mix Bench", onClick = bench::open)
                    }
                }
            }
            // Any window big enough gets the dock; it was fullscreen-only once, but a large
            // window reflowing to the landscape layout wasted most of it.
            DesktopCanvasScale {
                DjApp(
                    graph = graph,
                    onTogglePlayback = {
                        val controller = graph.playbackController
                        if (controller.state.value == PlaybackState.Playing) controller.pause()
                        else controller.play()
                    },
                    startAudio = { graph.startDjAudio() },
                    panelKeys = panelKeys,
                )
            }
        }
    }
}
