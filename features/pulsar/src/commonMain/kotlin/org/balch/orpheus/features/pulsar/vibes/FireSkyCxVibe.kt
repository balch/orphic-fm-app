package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider

/**
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSkyCxVibe : VibeProvider {
    override val name: String = "Fire Sky CX"

    private val cxLick = Lick(
        steps = listOf(
            // bar 1 — the climb, stated once: G D F, the b7 rings out the bar
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.90f),
            LickStep(scaleDegree = 5, duration = 3.0f, velocity = 0.88f),  // the hook lands — let ring
            // bar 2 — the answer: climb again, but fall through the b5 to home
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.90f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.72f),  // the b5, descending crush
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.82f),
            LickStep(scaleDegree = 0, duration = 2.0f, velocity = 0.95f),  // home, rings 2 beats
        ),
        loopLength = 8,
    )

    override val vibe: Vibe by lazy {
        FireSkyVibe().vibe.copy(name = name, lick = cxLick, lickMutation = 0.10f, stepCount = 32)
    }
}
