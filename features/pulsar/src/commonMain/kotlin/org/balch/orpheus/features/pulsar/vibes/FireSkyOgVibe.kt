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
 * Fire Sky (OG) — FROZEN faithful-reproduction backup of the original blues-rock riff, kept as
 * a dev A/B reference. Catalog status WIP: dev-only, visible on debuggable / `-Pcatalog=wip`
 * builds for A/B but MUST NEVER be promoted to LIVE. It is a verbatim reproduction of a
 * copyrighted riff, so a LIVE (release) listing would ship it to users. Reuses the live
 * [FireSkyVibe] wholesale and swaps ONLY the lick + mutation back to faithful, so an A/B
 * isolates the riff. This is the highest-exposure original of the three — do not edit, and
 * never flip it to LIVE. Git commit ee8677e0 is the hard, fully-frozen snapshot.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSkyOgVibe : VibeProvider {
    override val name: String = "Fire Sky OG"

    // Faithful 2-bar riff (BLUES: 0=G,1=Bb/b3,2=C/4,3=Db/b5,4=D/5,5=F/b7). Preserved verbatim.
    private val ogLick = Lick(
        steps = listOf(
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.70f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.85f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.90f),
        ),
        loopLength = 8,
    )

    override val vibe: Vibe by lazy {
        FireSkyVibe().vibe.copy(name = name, lick = ogLick, lickMutation = 0.10f, stepCount = 32)
    }
}
