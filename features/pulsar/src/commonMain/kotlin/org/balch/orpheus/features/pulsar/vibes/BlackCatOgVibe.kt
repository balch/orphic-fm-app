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
 * Black Cat (OG) — faithful-reproduction backup of the original two-bar blues hook,
 * kept as a dev A/B reference. Catalog status WIP: dev-only, visible on debuggable /
 * `-Pcatalog=wip` builds for A/B but MUST NEVER be promoted to LIVE. It is a faithful
 * reproduction of a copyrighted hook, so a LIVE (release) listing would ship it to users.
 * Reuses the live [BlackCatVibe] wholesale and swaps ONLY the lick + mutation back to
 * faithful, so an A/B isolates the hook. Transcription is by ear and pending its ear-check;
 * once verified, freeze it like the other OG references and never flip it to LIVE.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class BlackCatOgVibe : VibeProvider {
    override val name: String = "Black Cat OG"

    // Faithful 2-bar hook (BLUES: 0=C#, 1=E/b3, 2=F#/4, 4=G#/5, 5=B/b7, 6=C#/oct).
    // Bar 1: root anchor, breath, stutter, then the pentatonic climb to the 5th.
    // Bar 2: over the top (b7 -> octave), then the walk back down home. Pure
    // pentatonic — the b5 (degree 3) never appears; that color belongs to the lead.
    private val ogLick by lazy {
        Lick(
            steps = listOf(
                // Bar 1 — the climb
                LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.95f),   // C# root anchor
                LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),   // (breath)
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.88f),   // C# stutter pickup
                LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.84f),   // E  b3 ┐
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.86f),   // F# 4  │ the climb
                LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.92f),   // G# 5  ┘ held
                // Bar 2 — over the top, then home
                LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.86f),   // B  b7
                LickStep(
                    scaleDegree = 6,
                    duration = 0.5f,
                    velocity = 0.94f
                ),   // C# octave — the peak
                LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.82f),   // B  b7
                LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.80f),   // G# 5
                LickStep(scaleDegree = 1, duration = 1.0f, velocity = 0.85f),   // E  b3
                LickStep(
                    scaleDegree = 0,
                    duration = 1.0f,
                    velocity = 0.96f
                ),   // C# home — ring into the loop
            ),
            loopLength = 8,  // 2 bars; steps sum to 8.0 exactly
        )
    }

    override val vibe: Vibe by lazy {
        BlackCatVibe().vibe.copy(name = name, lick = ogLick, lickMutation = 0.08f)
    }
}
