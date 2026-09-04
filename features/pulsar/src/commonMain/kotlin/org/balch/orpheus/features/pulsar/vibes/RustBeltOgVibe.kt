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
 * Rusted Coast (OG) — FROZEN faithful-reproduction backup of the original bass hook, kept as a
 * dev A/B reference. Catalog status WIP: dev-only, visible on debuggable / `-Pcatalog=wip`
 * builds for A/B but MUST NEVER be promoted to LIVE, so nothing faithful is ever in a release.
 * It is a verbatim reproduction of a copyrighted hook. It reuses the live [RustedCoastVibe]
 * wholesale and swaps ONLY the lick + mutation back to the faithful original, so an A/B against
 * the live vibe isolates exactly the hook. The live vibe carries the copyright-safe rewrite.
 * Do not edit, and never flip it to LIVE — this is the proven tool-validation reference. Git
 * commit ee8677e0 is the hard, fully-frozen snapshot; this class exists only so the OG hook is
 * playable alongside the rewrite.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class RustedCoastOgVibe : VibeProvider {
    override val name: String = "Goat Soup & Whisky OG"

    // The faithful 2-bar hook: three thumps on the root, a jump up to the 5th/b7, a staccato
    // walk-up back into the thumps. Preserved verbatim (DORIAN: 0=D,2=F/b3,3=G/4,4=A/5,5=B/6,6=C/b7).
    private val ogLick = Lick(
        steps = listOf(
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 1.00f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.90f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.90f),
            LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.98f),
            LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.90f),
            LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.72f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.76f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.84f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.90f),
            LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.92f),
        ),
        loopLength = 8,
    )

    override val vibe: Vibe by lazy {
        RustedCoastVibe().vibe.copy(name = name, lick = ogLick, lickMutation = 0.14f, stepCount = 32)
    }
}
