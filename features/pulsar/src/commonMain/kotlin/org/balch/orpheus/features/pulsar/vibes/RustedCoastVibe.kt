package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.StormAnomaly
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingFills
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FillType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionInversion
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SectionWeather
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.StrikeEffect
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TonalTension
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Rusted Coast — a swampy heartland-rock pocket built on a bass hook, dry and lazy-tough.
 *
 * ## The feel
 * Mid-tempo, laid back but insistent. THE BASS IS THE SONG: a round, fingered two-bar
 * hook. Everything else sits behind it — a fat dry backbeat, a jangly chank on the
 * off-hand, a twangy slide answering between phrases, a low-mixed organ bed. Production
 * is dry and forward: garage floor, not arena.
 *
 * ## The hook
 * Two bars in DORIAN degrees (0=root, 2=b3, 3=4th, 4=5th, 5=6th, 6=b7). Bar 1 anchors the
 * root and leans DOWN through b7 -> 5 -> root; bar 2 lifts to the 6th, pops the octave,
 * then falls 4 -> b3 -> 2 back into the loop. Rests are real rests (negative degrees) —
 * the swamp is in the space between notes.
 *
 * ## Arrangement
 * Eight sections. intro (kit and texture build; the band walks on at the verse) -> verse
 * (one-chord vamp, so the hook never transposes) -> chorus (IV -> bVII -> i, the hook rides
 * the roots — the payoff) -> jam -> breakdown (drums + bass, one long build) -> outro
 * (terminal lift). Jam and breakdown can open instead into the storm pair: "cloud burst"
 * (rumble swallows the room, the band plays on buried and comes apart), then "storm"
 * (rain at full, everyone re-aligns, the kit walks back in), exiting to verse or chorus.
 * A/B against DogHouseVibe.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class RustedCoastVibe : VibeProvider {
    override val name: String = "Rusted Coast"

    // Verse hangs on the i: the hook carries all the motion, so the bed stays planted
    // and the bass never transposes until the chorus asks it to.
    private val verseProgression = chords(0)

    // The chorus lift, IV -> bVII -> i -> i; in dorian the major IV is the money chord.
    // ROOT_ONLY tracks transpose the hook up the lift and back.
    private val chorusProgression = chords(3, 6, 0, 0)

    // Jam seesaw — mostly home with a bVII lean every 4th bar to give soloists a shape.
    private val jamProgression = chords(0, 0, 0, 6)

    // Per-edge transition ramps named for their musical role.
    private val liftBars = 2
    private val dropBars = 2
    private val bigLiftBars = 4
    private val introBuildBars = 3

    // Deliberately ONE bar: the pre-roll drags the destination's macros AND weather back
    // into the outgoing section, so a longer lead-in guts the band and raises the rumble
    // well before the storm lands. One bar leaves a rise under the last bar, clap as arrival.
    private val stormLeadInBars = 1

    val sectionList by lazy {
        listOf(
            // 0: intro — kit and texture build; the band walks on at the verse downbeat.
            //    A ringing kick and a washy hat carry an eight-bar crescendo while the hook,
            //    the jangle and the twang sit far back and all come up together on the flip.
            //    Energy opens under the verse's and the 3-bar pre-roll ramps it home; the
            //    tension override stacks a per-bar staircase on top.
            //
            //    Morph pinning decides WHAT MOVES: the tension evolution below sweeps morph
            //    only on UNPINNED tracks, so pinning the kit (DECAY, for three distinct voices)
            //    and the bass leaves the shaker as the one drifting voice — the texture reads
            //    as the thing developing while the pocket holds.
            Section(
                name = "intro",
                barsMin = 4, barsMax = 4,   // 4 loop-cycles = 8 real bars at stepCount 32
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = introBuildBars),
                ),
                // The build pays off in a crack on the downbeat the band walks on. On the
                // section, not the edge, so it survives the intro gaining a destination.
                // EAR-TUNE(user owns after ear test)
                exitEffects = listOf(StrikeEffect(intensity = 0.85f, distance = 0.25f)),

                macroOverrides = MacroOverrides(
                    energy = 0.65f, complexity = 0.4f, space = 1.15f, mood = 0.9f,
                ),
                // Replaces the vibe tension for this section only. The tension counter is
                // free-running and starts at 0 on load, so the intro is the one section that
                // gets a clean 0 -> peak sweep.
                //
                // outerBars == innerBars is a CURVE control, not a long arc: both phases share
                // one p, so intensity collapses to (1 - depth) * p + depth * p^2 and outerDepth
                // becomes a linear-to-quadratic knob. 0.65 hangs the walk-up back and rushes
                // the last bar, paid for in peak height — the top lands at 0.63, not 0.75.
                tensionOverride = TensionProfile(
                    innerBars = 4,       // one staircase per visit
                    outerBars = 4,       // == innerBars: curves the walk-up, see above
                    outerDepth = 0.65f,  // 0 = linear, 1 = pure quadratic
                    volume = 0.9f,     // the crescendo
                    tonal = TonalTension(chromaticPassing = 0.16f),
                    timing = 0.10f,
                    // Wide on purpose: with the kit and the bass pinned this reaches the shaker
                    // alone, so it buys texture movement instead of smearing the pocket.
                    // Widen only while that pinning holds.
                    evolution = EvolutionTension(
                        timbreLow = 0.18f, timbreHigh = 0.78f, timbreProbability = 0.95f,
                        morphLow = 0.20f, morphHigh = 0.82f, morphProbability = 0.92f,
                        // A knee on the tone rather than the velocity: evolution is
                        // (intensity - attackPoint) / (1 - attackPoint), so the timbre barely
                        // stirs through the first half and opens late. Keep it well under the
                        // 0.63 peak above or the sweep loses most of its range.
                        attackPoint = 0.12f,
                        releaseSpeed = 0.25f,
                    ),
                    spurtChance = 0f,
                ),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(volume = 0.9f, morph = 0.90f, reverbSend = 0.36f, density = .32f),  // kick: loud, ringing
                    1 to TrackSectionOverride(volume = 0.62f, morph = 0.26f, density = .15f),  // snare: tight crack
                    2 to TrackSectionOverride(volume = 0.44f, morph = 0.54f, density = .85f),  // hat: open and washy
                    // Track 3 — THE HOOK, held right back so the kit carries the build. The
                    // morph pin is the point of this entry: unpinned, the tension evolution
                    // above walks the bass morph 0.22 -> 0.72 and the fingered PLUCK character
                    // wanders with it. 0.30 is what the verse's own Space resolves to through
                    // MELODIC's spaceDecay, so the un-pin at the boundary is inaudible.
                    3 to TrackSectionOverride(volume = 0.1f, morph = 0.30f, density = .15f),
                    4 to TrackSectionOverride(volume = 0.4f, density = .15f),   // jangle
                    5 to TrackSectionOverride(volume = 0.4f, density = .15f),   // twang
                    6 to TrackSectionOverride(volume = 0.4f, density = .15f),  // organ
                    // Track 7 — the texture, deliberately NOT morph-pinned so the evolution
                    // sweep is audible on it alone. Loud for an EFFECT track because that macro
                    // map caps energyVolume at 0.5x and texture_energy_curve ducks tracks 5-7
                    // again; the sends give it a tail so it reads as a layer, not dust on the kit.
                    7 to TrackSectionOverride(volume = 0.88f, reverbSend = 0.42f, delaySend = 0.24f, density = .5f),
                ),
            ),
            // 1: verse — the full pocket, baseline. One-chord vamp; the hook rules.
            Section(
                name = "verse",
                barsMin = 3, barsMax = 6,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.99f, transitionBars = liftBars),  // -> chorus
                    SectionTransition(targetIndex = 5, weight = 0.01f, transitionBars = stormLeadInBars)  // -> cloud bursts
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 2: chorus — the lift. Hook transposes with IV -> bVII -> i; comp and
            //    lead come forward; brighter and bigger but still dry.
            Section(
                name = "chorus",
                barsMin = 3, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = liftBars),  // -> jam
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.3f, complexity = 1.15f, space = 0.85f, mood = 1.2f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.40f),                  // chank drives
                    5 to TrackSectionOverride(density = 0.26f, volume = 0.56f),  // twang answers more
                    6 to TrackSectionOverride(density = 0.30f),                  // organ swells in
                ),
            ),
            // 3: jam — the band stretches out over the seesaw; bass and twang trade leads.
            Section(
                name = "jam",
                barsMin = 8, barsMax = 12,
                transitions = listOf(
                    SectionTransition(targetIndex = 4, weight = 1.0f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.95f, complexity = 1.35f, space = 1.55f, mood = 1.1f
                ),
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(delaySend = 0.6f),
                    1 to TrackSectionOverride(delaySend = 0.2f),
                    3 to TrackSectionOverride(delaySend = 0.4f),
                    4 to TrackSectionOverride(delaySend = 0.4f),
                    // The twang is HALF THE JAM — lead eligibility is role-only, so muted it
                    // still took the lead and played nothing. Chorus level to start.
                    // EAR-TUNE(user owns after ear test)
                    5 to TrackSectionOverride(density = 0.26f),
                    6 to TrackSectionOverride(volume = .8f, delaySend = .8f, reverbSend = .6f),
                    7 to TrackSectionOverride(volume = .8f, delaySend = .8f, reverbSend = .6f),
                ),
                customProgression = jamProgression,
                chordsPerBar = 1,
                soloMode = SoloMode.Jam(probability = 0.8f, lickInfluence = 0.3f),
            ),
            // 4: breakdown — drums + bass only, the hook naked again, one long anticipation
            //    build back into the chorus. Sometimes the build never comes and the sky
            //    opens instead.
            Section(
                name = "breakdown",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 5, weight = 1.0f, transitionBars = stormLeadInBars)  // -> cloud bursts
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.6f, space = 1.4f, mood = 0.9f,
                ),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.0f),
                    5 to TrackSectionOverride(density = 0.0f),
                    6 to TrackSectionOverride(density = 0.12f),  // organ whisper holds the bed
                    7 to TrackSectionOverride(density = 0.08f),
                ),
            ),
            // 5: cloud bursts — THE ROAR. The breakdown (or a thinning jam) opens into a real
            //    storm instead of building: the entry strike lands and its roll swallows the
            //    room. Rumble fills this section; the rain arrives in 6. The kit is OUT, but
            //    the rest of the band plays on UNDER the roar — buried, not absent.
            //
            //    THE BAND COMES APART: three voices breathe on co-prime periods (2, 3, 5), so
            //    their swells only re-converge every 30 bars, far longer than this section
            //    lives. The ensemble audibly decoheres instead of just getting quieter.
            Section(
                name = "downpour",
                // Matched to the exit's 4-bar pre-roll, so the crossfade toward downpour
                // runs the whole section from zero rather than starting part-way in.
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    // One way out, and the blend IS the transition: no effect fires here.
                    // Macros and weather crossfade toward downpour across all 4 bars.
                    SectionTransition(targetIndex = 6, weight = 1.0f, transitionBars = 4),
                ),
                // Clap, clap, roll — however the storm is reached. The first lands overhead on
                // the flip, the second answers from further off; the later strike re-arms the
                // one rumble tail, so the pair hands over to a single roll rather than two.
                entryEffects = listOf(
                    StrikeEffect(intensity = 0.9f, distance = 0.1f),
                    // EAR-TUNE(user owns after ear test)
                    StrikeEffect(intensity = 0.75f, distance = 0.40f, delayMs = 420),
                ),
                // Far and quiet: the storm already moving off as the rain takes over.
                exitEffects = listOf(
                    StrikeEffect(intensity = 0.50f, distance = 0.80f),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    // The floor of the whole song and the START of the ramp, not a level the
                    // section sits at: energy and mood collapse while complexity goes UP —
                    // the band is scattered and disoriented rather than merely quiet.
                    energy = 0.12f, complexity = 1.6f, space = 1.9f, mood = 0.45f,
                ),
                // Aether's glacial arc, near verbatim: no groove tension, just a slow drift of
                // timbre/morph/harmonics so the weather billows. The free-running counter lands
                // mid-phase here — fine, this profile has no build to protect, only motion.
                tensionOverride = TensionProfile(
                    innerBars = 8,
                    outerBars = 0,
                    outerDepth = 0f,
                    volume = 0.35f,  // slow breathing, not a crescendo
                    tonal = TonalTension(),
                    timing = 0.02f,
                    evolution = EvolutionTension(
                        timbreLow = 0.15f, timbreHigh = 0.45f, timbreProbability = 0.5f,
                        morphLow = 0.10f, morphHigh = 0.50f, morphProbability = 0.4f,
                        harmonicsLow = 0.15f, harmonicsHigh = 0.45f, harmonicsProbability = 0.3f,
                        attackPoint = 0.25f, releaseSpeed = 0.8f,
                    ),
                    spurtChance = 0f,
                ),
                // Rumble-dominant and close: the roar, not the rainfall. Rain is nearly off so
                // section 6 has somewhere to swell up from, and the rumble decays across the
                // section as rain climbs — the roll hands over instead of sitting on top of it.
                weather = SectionWeather(rain = 0.08f, rumble = 0.40f, strikeChance = 0.20f, distance = 0.25f),
                customProgression = verseProgression,  // hang on the i — the weather is harmonically still
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(density = 0.0f),  // kit out
                    1 to TrackSectionOverride(density = 0.0f),
                    2 to TrackSectionOverride(density = 0.0f),
                    // The hook is buried but never gone — a low ceiling and a deep floor,
                    // so it surfaces between rolls instead of holding a line through them.
                    3 to TrackSectionOverride(
                        volume = 0.45f, density = 0.9f,
                        breatheBars = 2, breatheFloor = 0.04f, breatheTimbreSpan = 0.40f,
                    ),
                    4 to TrackSectionOverride(volume = 0.24f, reverbSend = 0.55f, delaySend = 0.28f),  // chank -> wash, under
                    5 to TrackSectionOverride(volume = 0.34f, reverbSend = 0.50f, delaySend = 0.42f),  // the lone slide voice
                    // The bed goes long-form: sustained holds, breathing on 3 against the hook's 2.
                    6 to TrackSectionOverride(
                        volume = 0.44f, density = 0.30f, reverbSend = 0.50f,
                        holdProbability = 0.90f, holdLengthMin = 8, holdLengthMax = 16,
                        breatheBars = 3, breatheFloor = 0.10f, breatheTimbreSpan = 0.25f,
                    ),
                    // Particles sit back now that the weather carries the top end. The 5-bar
                    // breathe is the slowest of the three, so it's the voice that most
                    // obviously stops agreeing with the others.
                    7 to TrackSectionOverride(
                        volume = 0.22f, density = 0.20f, morph = 0.85f,
                        reverbSend = 0.50f, delaySend = 0.35f,
                        breatheBars = 5, breatheFloor = 0.15f,
                    ),
                ),
            ),
            // 6: cloudbreak — RAIN AT FULL, THE BAND REASSEMBLES. Really two ramp ENDPOINTS
            //    wearing one name: its macros and weather are what cloud bursts crossfades
            //    TOWARD, and its own exits carry the same values on toward verse/chorus, where
            //    weather is absent. Nothing here is a level the section merely sits at.
            //
            //    COMING BACK TOGETHER: breathe snaps to unity on the flip and every voice still
            //    breathing shares one 3-bar period, so the band re-aligns in one move. The kit
            //    returns quietly — the first half of a comeback the exit completes.
            Section(
                name = "cloud break",
                barsMin = 4, barsMax = 4,  // keep min <= max: a backwards range silently pins to barsMin
                transitions = listOf(
                    // Both exits ramp the full section: the rain drains to nothing and the
                    // macros climb home, so "back to normal" arrives rather than cuts.
                    SectionTransition(targetIndex = 1, weight = 1f, transitionBars = 2),  // -> verse
                ),
                // One nearer crack as the rain arrives at full, then the last roll leaves
                // with the weather on the way back to the band.
                // EAR-TUNE(user owns after ear test)
                entryEffects = listOf(
                    StrikeEffect(intensity = 0.80f, distance = 0.20f),
                ),
                exitEffects = listOf(
                    StrikeEffect(intensity = 0.50f, distance = 0.80f),
                ),
                recencyDecay = 0.5f,
                // The far end of cloud burst' collapse — still short of the verse baseline so
                // the exit ramp has the last stretch left to travel.
                macroOverrides = MacroOverrides(
                    energy = 0.70f, complexity = 0.85f, space = 1.5f, mood = 0.95f,
                ),
                // Rain carries the section; rumble sits at a floor under it. The per-bar strike
                // roll is gated on nothing already ringing and tails run seconds, so a 0.50
                // chance buys a strike every second or third bar, not one per bar.
                weather = SectionWeather(rain = 0.85f, rumble = 0.22f, strikeChance = 0.50f, distance = 0.50f),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    // The kit walks back in under the rain — quiet, but the pulse is back.
                    0 to TrackSectionOverride(volume = 0.62f, density = 0.28f),
                    1 to TrackSectionOverride(volume = 0.48f, density = 0.16f),
                    2 to TrackSectionOverride(density = 0.0f),  // hats stay out; the rain owns that band
                    // The hook comes back up on the bed's period — same breath, shallower
                    // floor: present again, still weathered.
                    3 to TrackSectionOverride(
                        volume = 0.78f, density = 0.9f,
                        breatheBars = 3, breatheFloor = 0.35f, breatheTimbreSpan = 0.18f,
                    ),
                    4 to TrackSectionOverride(volume = 0.34f, reverbSend = 0.45f, delaySend = 0.24f),
                    5 to TrackSectionOverride(volume = 0.50f, reverbSend = 0.45f, delaySend = 0.38f),
                    6 to TrackSectionOverride(
                        volume = 0.58f, density = 0.30f, reverbSend = 0.45f,
                        holdProbability = 0.85f, holdLengthMin = 6, holdLengthMax = 12,
                        breatheBars = 3, breatheFloor = 0.35f,
                    ),
                    7 to TrackSectionOverride(
                        volume = 0.24f, density = 0.18f, morph = 0.85f,
                        reverbSend = 0.45f, delaySend = 0.30f,
                    ),
                ),
            ),
            // 7: outro — full-band lift, ride it home. Terminal.
            Section(
                name = "outro",
                barsMin = 4, barsMax = 4,
                macroOverrides = MacroOverrides(
                    energy = 1.35f, complexity = 0.9f, space = 0.8f, mood = 1.15f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = 1,
                weather = SectionWeather(rain = 0.09f, rumble = 0.10f, strikeChance = 0f, distance = 1f, rainLevel = 0.9f),
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 87f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                lengthSeconds = 180..200,
                sections = sectionList,
            ),
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.D,
            scaleType = ScaleType.DORIAN,
            // The jam's cast. ROLE gates lead eligibility, so only Bassist and Twang can
            // lead and the jam is those two trading; 4 and 6 are melodic engines but Chordal
            // roles, so the Bed is pulled in, never leads. Drummer alwaysActive = dry pocket.
            band = Band(
                members = listOf(
                    BandMember("Drummer", listOf(0, 1, 2, 7), alwaysActive = true,
                               loudness = 0.70f, creativity = 0.20f),
                    BandMember("Bassist", listOf(3), loudness = 0.85f, creativity = 0.40f),
                    BandMember("Twang",   listOf(5), loudness = 0.65f, creativity = 0.60f),
                    BandMember("Bed",     listOf(4, 6), loudness = 0.45f, creativity = 0.35f),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM   BASS   TWANG  BED
                    "Drummer" to row(0.00f, 0.45f, 0.55f, 0.00f),
                    "Bassist" to row(0.00f, 0.00f, 0.85f, 0.15f),
                    "Twang"   to row(0.00f, 0.75f, 0.00f, 0.25f),
                    "Bed"     to row(0.00f, 0.50f, 0.50f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    //            DRUM   BASS   TWANG  BED
                    "Drummer" to row(0.00f, 0.20f, 0.30f, 0.25f),
                    "Bassist" to row(0.00f, 0.00f, 0.35f, 0.40f),
                    "Twang"   to row(0.00f, 0.30f, 0.00f, 0.45f),
                    "Bed"     to row(0.00f, 0.25f, 0.30f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 3,
                barsPerLeadMin = 2, barsPerLeadMax = 4,
            ),
            seed = 0,
            // --- macro defaults: relaxed pocket, warm, dry-forward ---
            energy = 0.58f,
            complexity = 0.72f,
            space = 0.34f,
            mood = 0.62f,
            deep = 0.30f,
            // --- THE HOOK (track 3 bass plays it as LickMode.Fill) ---
            // DORIAN degrees: 0=D(root), 1=E(2), 2=F(b3), 3=G(4), 4=A(5), 5=B(6), 6=C(b7), 7=D(oct).
            // Copyright-safe rewrite: keeps the swampy D-Dorian pocket (scale, low register,
            // rest density, b7/6 color) but inverts the recognizable signature — one anchored
            // root leaning DOWNWARD, an octave POP, a DESCENDING turn, all the opposite of the
            // faithful figure. That original is preserved in RustedCoastOgVibe (WIP, -Pcatalog).
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.98f),   // D  root — single anchor
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 6, duration = 0.75f, velocity = 0.90f),  // C  b7 — dotted downward lean
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.86f),   // A  5
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 0, duration = 1.0f, velocity = 1.00f),   // D  root — home by leap DOWN, held
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.80f),   // B  6 — bright lift
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.92f),   // D  octave POP
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.84f),   // C  b7
                    LickStep(scaleDegree = 4, duration = 0.75f, velocity = 0.88f),  // A  5 — dotted
                    LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),   // (pocket breath)
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.78f),   // G  4  ┐
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.74f),   // F  b3 │ DESCENDING turn into the loop
                    LickStep(scaleDegree = 1, duration = 0.25f, velocity = 0.70f),  // E  2  ┘
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                ),
                loopLength = 8,  // 2 bars; steps sum to 8.0 exactly
            ),
            lickMutation = 0.22f,  // high for copyright distance — more run-to-run drift off the figure
            lickOctave = -1,       // auto = midpoint of the bass range (lands around D2)
            genre = GenreProfile(
                swingAmount = 0.09f,          // lazy-tough pocket — behind the beat, not shuffled
                ghostProbability = 0.22f,     // pocket ghosts in the kit
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.MODAL,  // vamp-centric, no functional resolution
                chordsPerBar = 1,
                customProgression = verseProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.10f,  // planted — the hook supplies the motion
            tracks = listOf(
                // Track 0 — Kick (BD): fat, laid back, locked.
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.88f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.44f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 1 — Snare (SD): the big dry crack on 2 & 4, fills at phrase ends.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.68f,
                    reverbSend = 0.14f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.08f,
                        density = 0.34f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,
                    )
                },
                // Track 2 — Hat (HH): relaxed 8ths riding the pocket.
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.52f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.52f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 3 — BASS (PD warm-round): THE HOOK. LickMode.Fill owns the whole
                // 2-bar phrase; PLUCK gives the fingered attack; ROOT_ONLY so the chorus
                // lift transposes the hook with the chord roots (the payoff moment).
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD,
                    volume = 0.88f,
                    noteRangeLow = 26,        // D1
                    noteRangeHigh = 50,       // D3 — auto lick octave centers ~D2
                    reverbSend = 0.06f,       // nearly dry — forward in the mix
                    reverbBrightness = 0.30f,
                    glideRate = 0.06f,        // tiny finger-slur between close notes
                    lpgMode = LpgMode.PLUCK,  // fingered articulation, note-by-note
                    lpgDecay = 0.52f,
                    lpgColour = 0.45f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(
                            lpgMode = LpgMode.SUSTAINED,  // rounder, dubbier at low energy
                            reverbSend = 0.16f,
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.ROOT_ONLY,
                            lickMode = LickMode.Fill,  // the full 2-bar hook as one phrase
                        ),
                        pan = 0.00f,
                        density = 0.95f,  // the hook fires on (nearly) every step
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,  // locked; variation = lickMutation only
                    )
                },
                // Track 4 — Jangle chank (CHD native chords): dry rhythm bed on the off-hand.
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.42f,
                    noteRangeLow = 55,        // G3
                    noteRangeHigh = 76,       // E5
                    reverbSend = 0.10f,
                    delaySend = 0.06f,
                    reverbBrightness = 0.55f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.42f,
                ).let { jangle ->
                    TrackVoice(
                        engineEdm = jangle,
                        engineSpace = jangle.copy(lpgMode = LpgMode.SUSTAINED, reverbSend = 0.22f),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.ROCK_DOWNBEATS,
                                arpMode = ArpMode.AUTO,          // CHD = native block chords
                                arpSpeed = 0.15f,
                                arpDirection = ArpDirection.UP,
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.18f,
                                    ghostProbability = 0.22f,     // lazy chank ghosts
                                    octaveJumpProbability = 0.10f,
                                    extensionProbability = 0.18f, // occasional add9 jangle color
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,
                                    skipProbability = 0.25f,
                                ),
                            ),
                        ),
                        pan = 0.24f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 5 — Twang lead (FM): slide-y answers between the hook's phrases.
                OrpheusEngine(
                    engineId = OrpheusEngineId.FM,
                    volume = 0.50f,
                    noteRangeLow = 57,        // A3
                    noteRangeHigh = 79,       // G5
                    reverbSend = 0.16f,
                    delaySend = 0.20f,        // slapback twang
                    reverbBrightness = 0.55f,
                    glideRate = 0.35f,        // the slide — notes bend into each other
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.55f,
                ).let { twang ->
                    TrackVoice(
                        engineEdm = twang,
                        engineSpace = twang.copy(lpgMode = LpgMode.SUSTAINED, reverbSend = 0.30f),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = -0.20f,
                        density = 0.16f,  // sparse — answers, never crowds the hook
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,  // waits for the hook, then answers
                    )
                },
                // Track 6 — Organ bed (DX3): low-mixed sustained root bed, swells forward
                // in the chorus.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.36f,
                    harmonics = 0.031f,       // DX3 idx 1 "Hammond" (auto-pinned)
                    holdProbability = 0.72f,  // sustained bed
                    holdLengthMin = 2,
                    holdLengthMax = 6,
                    noteRangeLow = 48,        // C3
                    noteRangeHigh = 69,       // A4
                    reverbSend = 0.26f,
                    delaySend = 0.06f,
                    reverbBrightness = 0.50f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        // At low energy the drawbar bed dissolves into a string ensemble — the
                        // sibling Aether Natalis pad role, glacial chorus LFO and all. Tone
                        // rides the MELODIC macro map; unpinned harmonics/timbre/morph never
                        // reach the voice. Mostly heard in breakdown and the storm sections.
                        engineSpace = organ.copy(
                            engineId = OrpheusEngineId.ENS,
                            harmonics = 0.5f,  // clear the inherited DX3 patch index — meaningless on ENS
                            modLfoRate = 0.02f, modLfoDepth = 0.4f, modLfoShape = 0.1f,
                            modLfoCoupling = 0.3f,
                            reverbSend = 0.34f,
                        ),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.PAD,
                                arpMode = ArpMode.NEVER,  // root-tone bed — organ pedal, no ripple
                                sectionInversion = SectionInversion.ROOT_POSITION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.10f,
                                    ghostProbability = 0.05f,
                                    octaveJumpProbability = 0.05f,
                                    extensionProbability = 0.10f,
                                ),
                            ),
                        ),
                        pan = -0.24f,
                        density = 0.22f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 7 — Shaker / texture (NSE / PAR on space): quiet 8th-note motion up top.
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.26f,
                    noteRangeLow = 60,
                    noteRangeHigh = 76,
                    reverbSend = 0.22f,
                    reverbBrightness = 0.62f,
                ).let { shaker ->
                    TrackVoice(
                        engineEdm = shaker,
                        engineSpace = shaker.copy(engineId = OrpheusEngineId.PAR, reverbSend = 0.32f),
                        role = TrackRole.Percussive,
                        pan = -0.30f,
                        density = 0.22f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
            ),
            stepCount = 32,  // 2 bars / 8 beats — the whole hook as one LickMode.Fill phrase
            tension = TensionProfile(
                innerBars = 8,   // >= 7 keeps spurt + octave climax enabled
                outerBars = 32,
                outerDepth = 0.50f,
                volume = 0.28f,
                tonal = TonalTension(
                    octaveShift = true,        // hook jumps the octave at the peak
                    chromaticPassing = 0.12f,  // chromatic approach notes — walking-bass DNA
                ),
                timing = 0.18f,
                evolution = EvolutionTension(
                    timbreLow = 0.30f, timbreHigh = 0.55f, timbreProbability = 0.6f,
                    morphLow = 0.35f, morphHigh = 0.52f, morphProbability = 0.5f,
                    attackPoint = 0.6f, releaseSpeed = 0.4f,
                ),
                spurtChance = 0.08f,
            ),
            effects = VibeEffects(
                delayTimeA = 0.16f,       // tight slapback for the twang
                delayTimeB = 0.33f,
                delayFeedback = 0.20f,
                delayDamping = 0.50f,
                reverbSize = 0.42f,       // roomy but dry-forward — garage floor
                reverbDamping = 0.50f,
                reverbBrightness = 0.50f,
                deepFloor = 0.24f,
            ),
            anomalies = listOf(
                StormAnomaly(probability = 0.04f, durationBarsMin = 1, durationBarsMax = 2, intensity = 0.7f, distance = 0.4f),
            ),
        )
    }
}
