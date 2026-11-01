package org.balch.orpheus.djapp.ai.collab

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.CaptureSnapshot
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.capture
import org.balch.orpheus.features.pulsar.playback.SongEndHold

/**
 * Keeps a song playing past its end while the Mixer holds edits (levels, pans, sends, macros, mutes, solos)
 * that the last capture did not take, since loading the next song would clear them. Providers keep the
 * feature graph unresolved until a song ends, as [CollabMixer] does.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SongEndHold>())
@Inject
class UncapturedMixHold(
    controllerProvider: () -> MixBenchController,
    pulsarProvider: () -> PulsarFeature,
) : SongEndHold {
    private val controller by lazy(controllerProvider)
    private val pulsar by lazy(pulsarProvider)
    private val captured = MutableStateFlow<CaptureSnapshot?>(null)

    /** A capture wrote [snap]: the mix as it stood then may be cleared by the next song. */
    fun markCaptured(snap: CaptureSnapshot) {
        captured.value = snap
    }

    override fun holdsSong(): Boolean {
        val c = controller
        val p = pulsar
        val layers = c.layers.value
        val muted = c.muted.value
        val soloed = c.soloed.value
        if (muted.isEmpty() && soloed.isEmpty() && capture(p.vibeFlow.value, layers).isEmpty()) return false
        val last = captured.value ?: return true
        return last.loadCount != p.vibeLoadCount.value || last.layers != layers || last.muted != muted || last.soloed != soloed
    }
}
