package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider

/**
 * Fire Sky (OG) — FROZEN A/B reference: the founding hook stated exactly, kept so riff
 * variants can be compared against the original statement. Catalog status WIP: dev-only,
 * visible on debuggable / `-Pcatalog=wip` builds; not for LIVE promotion — the shipping
 * vibes are the variants, this file is the reference. Reuses the live [FireSkyVibe]
 * wholesale and swaps ONLY the lick + mutation, so an A/B isolates the riff. Do not
 * edit; git commit ee8677e0 is the hard, fully-frozen snapshot.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSkyOgVibe : VibeProvider {
    override val name: VibeName = VibeNames.FIRE_SKY_OG

    override val vibe: Vibe by lazy {
        FireSkyVibeBase().vibe.copy(
            name = name.value,
            lick = ogLick,
            lickMutation = 0.10f,
            stepCount = 32
        )
    }
}

/**
 * Fire Sky .5f — a flat 60 BPM Fire Sky (no half-time — every section, including
 * intro/build, runs at the same tempo, and the build's exit scratch is off) that
 * ROTATES between [aiLick] and [tweakLick] per section, with the rare [ogLick]
 * anomaly: a flash of the founding hook stated exactly, also fired by the
 * manual anomaly trigger.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSky05Vibe : VibeProvider {
    override val name: VibeName = VibeNames.FIRE_SKY_05F

    override val vibe: Vibe by lazy {
        FireSkyVibeBase(baseBpm = 60f, halfTimeMult = 1.0f, buildScratchMs = 0).vibe.copy(
            name = name.value,
            lick = tweakLick,  // fallback seed; the pool overrides it at load
            lickRotation = LickRotation(pool = listOf(tweakLick, aiLick)),
            anomalies = listOf(
                // A rare flash of the founding hook, stated exactly; also fired by
                // the manual anomaly trigger.
                LickAnomaly(lick = ogLick, chance = 0.005f),
            ),
            lickMutation = 0.20f,
            stepCount = 32,
        )
    }
}
