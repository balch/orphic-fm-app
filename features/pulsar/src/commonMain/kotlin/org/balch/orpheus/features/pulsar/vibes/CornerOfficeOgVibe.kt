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
 * Corner Office (OG) — FROZEN faithful-reproduction backup of the original 4-bar funk strut,
 * kept as a dev A/B reference. Catalog status WIP: dev-only, visible on debuggable /
 * `-Pcatalog=wip` builds for A/B but MUST NEVER be promoted to LIVE. It is a verbatim
 * reproduction of a copyrighted strut, so a LIVE (release) listing would ship it to users.
 * Reuses the live [CornerOfficeVibe] wholesale and swaps ONLY the lick + mutation back to
 * faithful, so an A/B isolates the strut. Do not edit, and never flip it to LIVE — proven
 * reference. Git commit ee8677e0 is the hard, fully-frozen snapshot.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class CornerOfficeOgVibe : VibeProvider {
    override val name: String = "Corner Office OG"

    // Faithful 4-bar strut (MINOR: 0=E,2=G/b3,3=A/4,4=B/5,5=C/b6,6=D/b7). Preserved verbatim.
    private val ogLick = Lick(
        steps = listOf(
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 1.00f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.70f),
            LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.85f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.82f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.90f),
            LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.70f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.60f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.78f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 4, duration = 0.75f, velocity = 0.85f),
            LickStep(scaleDegree = 6, duration = 0.25f, velocity = 0.70f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 1.00f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.68f),
            LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),
            LickStep(scaleDegree = 6, duration = 0.25f, velocity = 0.82f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.84f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.76f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.74f),
            LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.90f),
            LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.72f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.60f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.78f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.82f),
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 6, duration = 0.25f, velocity = 0.72f),
            LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.68f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.76f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.80f),
        ),
        loopLength = 16,
    )

    override val vibe: Vibe by lazy {
        CornerOfficeVibe().vibe.copy(name = name, lick = ogLick, lickMutation = 0.16f, stepCount = 64)
    }
}
