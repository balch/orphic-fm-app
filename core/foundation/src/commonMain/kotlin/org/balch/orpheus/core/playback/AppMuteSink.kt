package org.balch.orpheus.core.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.AppSymbol

/**
 * Default MuteSink for both apps: writes [AppSymbol.MUTED] via [SynthController]
 * so the change propagates through the controlFlow → ViewModels (e.g.
 * PulsarViewModel.globalPaused) as well as down to the engine. Going through
 * synthEngine.setPluginPort directly would update the engine but skip the
 * flow notification, leaving the in-app play/pause icon stuck on its
 * previous state. Playing → 0 (audible), Paused/Stopped → 1 (silent).
 */
@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class, binding = binding<MuteSink>())
class AppMuteSink(private val synthController: SynthController) : MuteSink {
    override fun apply(state: PlaybackState) {
        val mutedValue = if (state == PlaybackState.Playing) 0 else 1
        synthController.setPluginControl(
            id = AppSymbol.MUTED.controlId,
            value = IntValue(mutedValue),
            origin = ControlEventOrigin.UI,
        )
    }
}
