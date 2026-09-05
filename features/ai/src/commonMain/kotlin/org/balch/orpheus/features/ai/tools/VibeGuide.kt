package org.balch.orpheus.features.ai.tools

/**
 * JSON example strings embedded in the agent-facing guide prose ([STATIC_GUIDE] and
 * `TRACK_OVERRIDES_NOTE` in VibeSchemaTool.kt). Extracted so [VibeGuideExamplesTest] can decode
 * each one with the real apply-path Json (`vibeApplyJson`) and assert it is actually interpolated
 * into the shipped text — prose examples can no longer silently drift from the schema.
 */
internal object VibeGuideExamples {
    /** Bare-degree shorthand for `GenreProfile.customProgression` — a I-IV-VI-VII riff, no glide. */
    const val CUSTOM_PROGRESSION_SHORTHAND: String = "[0, 3, 5, 6]"

    /** Object form of the same field, needed when a chord step carries per-chord glide. */
    const val CUSTOM_PROGRESSION_WITH_GLIDE: String =
        """[{"degree": 0}, {"degree": 3, "glideRate": 0.4}]"""

    /** `Section.trackOverrides` shape: override two fields on track 4 for one section. */
    const val TRACK_OVERRIDES_EXAMPLE: String =
        """{ "4": { "chordFollow": "FIXED", "density": 0.3 } }"""

    /** `Section.trackOverrides` shape used to pin only chordFollow (the section-7 pegging recipe). */
    const val TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE: String =
        """{ "4": { "chordFollow": "FIXED" } }"""

    /** Polymorphic `compingStyle` object form (NOT a bare enum string). */
    const val COMPING_STYLE_EXAMPLE: String =
        """{ "type": "org.balch.orpheus.features.pulsar.models.CompingStyle.FUNK_STABS" }"""

    /**
     * A complete working `band` — the one any section with a `soloMode` must have. Values mirror
     * `BandPresets.quartet(kit = [0,1,2,7], bass = [3], lead = [5], colour = [4,6])`, the dev-side
     * preset, so the agent and a Kotlin author get the same cast; [VibeGuideExamplesTest] asserts
     * the two stay equal.
     */
    const val MINIMUM_BAND: String = """{
  "members": [
    { "name": "Drummer", "tracks": [0, 1, 2, 7], "alwaysActive": true, "loudness": 0.7, "creativity": 0.3 },
    { "name": "Bassist", "tracks": [3], "loudness": 0.8, "creativity": 0.5 },
    { "name": "Lead", "tracks": [5], "loudness": 0.65, "creativity": 0.6 },
    { "name": "Colour", "tracks": [4, 6], "loudness": 0.4, "creativity": 0.7 }
  ],
  "handoffMatrix": [0.0, 0.3, 0.45, 0.1,  0.2, 0.0, 0.5, 0.15,  0.15, 0.4, 0.0, 0.25,  0.1, 0.3, 0.45, 0.0],
  "pullInMatrix":  [0.0, 0.3, 0.25, 0.1,  0.25, 0.0, 0.4, 0.15,  0.2, 0.4, 0.0, 0.2,  0.1, 0.25, 0.3, 0.0],
  "barsPerLeadMin": 4, "barsPerLeadMax": 8
}"""
}

/**
 * Hand-curated composition guide handed to the in-app agent before it builds a vibe — the musical
 * semantics of the dev vibe-creator skill, reshaped for an author emitting JSON (all Kotlin/DI/Gradle
 * scaffolding removed). Pairs with the dynamic catalog (vibeFingerprint) appended by buildGuide.
 *
 * Source of truth for the prose: .claude/skills/vibe-creator/SKILL.md. When that skill's musical
 * guidance changes, update this constant too (they serve different audiences and neither the skill
 * markdown nor the model KDoc is reachable at app runtime).
 *
 * `internal val`, not `const val`: the JSON examples below are interpolated in from
 * [VibeGuideExamples], and Kotlin const vals cannot hold string templates.
 */
internal val STATIC_GUIDE: String = """
# Building a Pulsar vibe — composition guide

You edit an existing vibe's JSON into a new one. First understand what each control does, then pick
the closest existing vibe from the catalog below and re-tune every parameter with intent. Do not
reskin one template by changing only bpm and root — that is what makes vibes feel samey.

## 1. The frame
- bpm: tempo. rootNote, scaleType: the key and mode (set the mode to the feel — PHRYGIAN/MINOR dark,
  MAJOR/LYDIAN bright, DORIAN/MIXOLYDIAN bluesy-modal, the BLUES scales for blues/rock).
- envelopeType: AD (plucky/percussive) vs AR/ASR (sustained/pad-like).
- energy / complexity / space / mood / deep (0..1): the starting position of the four live macros
  plus 'deep'. energy = busier+harder, complexity = more rhythmic variation, space = wetter/roomier,
  mood = brighter vs darker. These are the *initial* macro values the user then performs live.

## 2. Groove (GenreProfile)
swingAmount (0 = straight, ~0.3+ = heavy shuffle), ghostProbability (quiet in-between hits),
rhythmDensity, progressionStyle (chord-movement flavour: POP/BLUES/JAZZ/MODAL/...), chordsPerBar,
and customProgression (explicit scale-degree list, degrees 0..6). Recall the reference song's
tempo/key/progression yourself and set these to match.

## 3. Tracks, roles, engine families
Exactly 8 tracks. Each track has a role — Percussive, Melodic, or Chordal — and an engine id that
must stay in its role family:
- drums: BD / SD / HH / NSE / PAR
- bass: WSH / VCF / PD / VA / DX
- keys: DX2
- lead: DX3 / WSH / FM / WTB
- pad: ENS / STR / CHD / GRN / ADD
- texture: MOD / PAR / SPK / SWM / NES / TRN
Keep each track's engine within its family when you swap sounds.

## 4. The DX / DX2 / DX3 patch-selector gotcha
For the DX engines, 'harmonics' is NOT a tone knob: it selects one of 32 FM patch banks (quantized,
auto-pinned). Changing it changes the *patch*, not the brightness. Set it deliberately to choose the
voice, and leave it alone if you only want a timbre tweak.

## 5. Macros + macroMap
The four live knobs (energy/complexity/space/mood) reshape per-track parameters through each track's
macroMap. Four presets cover most cases:
- RHYTHM: energy gates volume + density; complexity drives swing and variation. Good for drums and
  bass tracks that should punch harder as energy rises.
- MELODIC: mood sweeps harmonics and timbre through a musical tonal range; energy lifts density and
  volume. Use for lead and keys tracks where the mood knob should change the voice color.
- EFFECT: energy and space together modulate the FX voice; volume is intentionally attenuated so the
  effect sits under the mix rather than over it. Good for texture and FX tracks.
- WILD: all four macros modulate aggressively across their full ranges. Reserve for tracks that
  should behave unpredictably when the user pushes the knobs.

When none of the presets fit, supply a custom macroMap with seven min/max pairs (one per dimension):
energyVolume, energyDensity, complexitySwing, complexityVariation, spaceDecay, moodHarmonics,
moodTimbre. Each dimension maps one macro to one parameter at render time. Interpolation is linear:
value = min + macro × (max − min). A min > max gives an inverted response. Use min == max to lock a
parameter while still letting tension-evolution drift it. Setting moodTimbre to (0, 0) opts the track
entirely out of tension-evolution. For DX/DX2/DX3 tracks, harmonics is auto-pinned, so moodHarmonics
is effectively locked by the engine regardless of the macroMap range. complexitySwing is the one
exception to "per track": swing shifts the single shared step clock, so only track 0's value is read
and the other seven are ignored — set the groove you want on track 0.

## 6. Tension arc (TensionProfile)
innerBars sets the primary build-and-release cycle length (4 = tight phrase, 8 = longer, 16 = epic).
outerBars adds a secondary envelope (0 = disabled; 16-32 for long macro arcs). outerDepth (0..1)
controls how much the outer cycle modulates the inner.

Important: innerBars < 7 disables the octave climax and spurt behaviours — the tension sawtooth
still runs but its peak-time events (octave shift, chromatic passing, spurts) are gated off. Keep
innerBars >= 8 if you want those moments. volume (0..1) = how much tension affects track gains;
timing (0..1) = how much it tightens or loosens step timing. tonal.octaveShift and tonal.keyShift
let tension briefly push the melody up an octave or shift key at the peak.

tonal.halfLick is an enum, NOT a boolean. "OFF" = the lick plays its full length. "JAM" = loop only
the lick's first bar so the opening figure repeats while its tone evolves, then re-lock to bar 1 on
release — this is the normal choice for a build or lead-in. "JAM_INVERTED" = same truncation, but on
release the riff resumes on bar 2 and stays a bar out of phase with the harmony until the next
section boundary, so the following section opens with the riff's answer phrase instead of its hook.
"JAM_LAST_BAR" = jam the riff's LAST bar (its answer phrase) instead of its first, then re-lock to
bar 1 on release; it reads as a turnaround, and pairs well with JAM for a lead-in/lead-out sandwich
around a solo. Note JAM and JAM_INVERTED both jam bar 1 — only JAM_LAST_BAR jams a different bar.
JAM_INVERTED is a strong, deliberate flavor; prefer JAM unless the brief calls for a turned-around
riff. All of them only affect Fill licks — Squash licks are already one bar.

An arrangement may declare at most 12 sections, each with at most 8 outgoing transitions.

## 7. Sections (Arrangement)
A section graph lets the vibe tell a story: intro → verse → chorus → solo → breakdown → outro. Each
section has barsMin/Max (how long it lives before picking a transition), macroOverrides (multipliers
on the four live macros — 1.0 = unchanged, 1.4 = 40% boost), and optional trackOverrides (apply a
change to one specific track for this section only, auto-restored on exit).

Transitions carry an optional transitionBars field: when non-zero, the section crossfades its macro
overrides toward the next section over that many bars before the cut, creating a pre-roll ramp
instead of a hard switch. Use this on chorus→breakdown (energy needs a few bars to come down) and
on verse→chorus (build-up anticipation). Name the bar count after its musical role, not the number.

soloMode on a section activates the band's soloist: Jam(probability) for free improvisation,
LickBuilder(probability, mutationRate) for melodic construction, LongFill for extended fills.

A soloMode DOES NOTHING WITHOUT A BAND. The engine starts a section solo only when the vibe's
top-level `band` field is set; with no band the solo never starts and the section plays as an
ordinary one. The vibe is rejected outright if any section sets soloMode while `band` is null, so
copy this working four-piece and re-point the track lists at your own layout — kit tracks in
"Drummer", the bass in "Bassist", the melodic lead in "Lead", pads/comping in "Colour":

"band": ${VibeGuideExamples.MINIMUM_BAND}

The matrices are row-major NxN over the members, in the order they are listed: handoffMatrix[i][j]
is how likely member i is to pass the lead to member j, pullInMatrix[i][j] how likely i is to pull
j in as a duet partner. Keep the diagonal at 0 (nobody hands to themselves). Three casts cover
nearly everything: the four-piece above (one star lead); a two-front-line version where the last
two members trade at ~0.6 with each other and ~0.3 with the bass (the "trading leads" shape); and
a sparse ambient version — one alwaysActive bed member plus two voices trading at ~0.85.

Three traps make a band look present but do nothing:
- ONLY A Melodic-ROLE TRACK CAN LEAD A Jam. A Jam renders an improvised melodic line, so the lead
  member must own at least one track whose role is Melodic. A melodic-sounding ENGINE is
  irrelevant — an organ on a Chordal track cannot lead a jam. Check the role, not the engine id.
  (LongFill and LickBuilder are not filtered this way; only Jam.)
- TWO MEMBERS IS NOT A BAND. The engine refuses to hand the lead to an alwaysActive member, so an
  anchor plus one voice deadlocks. Always give a band at least two non-alwaysActive members.
- DO NOT "density": 0 A WOULD-BE SOLOIST. In a section that declares a soloMode, zeroing a melodic
  track mutes it for the whole section and the solo system does not lift it, so that member's solo
  is silent whenever it wins the lead. Thin it to 0.1-0.3 instead of taking it out.
Also give every track an owner: a track in no member gets the full support duck during a solo.

To peg a lead voice on the tonic while other tracks follow the progression — and as the standard
fix when a lick lurches on leaping progressions — use trackOverrides keyed by the track index as a
string: "trackOverrides": ${VibeGuideExamples.TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE}. All
TrackSectionOverride fields are optional; only set what you need to change (density, volume, morph,
reverbSend, delaySend, envelopeProfile, compingStyle, sectionInversion, arpMode, chordFollow,
holdProbability, holdLengthMin, holdLengthMax). Settings restore automatically when the section
ends.

Two of those have rules worth knowing. "density": 0 takes a track OUT for the section (a clean
mute that works on any track); a POSITIVE density rebuilds the track's pattern at that density,
thinning fills and ghosts — but only on tracks whose pattern is GENERATED, so on a Chordal track
or one playing a lick figure a positive density does nothing while 0 still mutes it. To simply
duck a track rather than drop it, set "volume". "morph": pins the voice's morph for the section
so the Space macro cannot overwrite it — on the drum engines (BD/SD/HH) morph is DECAY, so this
is how one section gets a long-ringing kick against a tight snare.

A simple 5-section shape: intro (sparse, low energy) → groove (full band, macro baseline) → peak
(energy × 1.3, complexity × 1.3) → breakdown (energy × 0.4, space × 1.5) → outro (sparse, fade).

## 8. Effects (VibeEffects)
Two delay taps (delayTimeA/B as a fraction of one bar: 0.25 = 16th, 0.375 = dotted-8th, 0.5 = half
bar) plus delayFeedback (0.2 = subtle, 0.5 = moderate, 0.7+ = building) and delayDamping (how dark
each repeat gets — higher = darker). Reverb: reverbSize (0.3 = room, 0.6 = hall, 0.9 = cathedral),
reverbDamping (low-pass on the tail), reverbBrightness (0.3 = warm/dark, 0.8 = shimmery). deepFloor
sets the minimum DEEP multiplier even when the SPACE macro is at zero — use 0.2–0.4 to keep some
effects presence even at the driest setting.

Per-track: each OrpheusEngine has delaySend + reverbSend (0..1). Leads get moderate sends, pads
generous, drums usually dry. Match the reverb character to the mood: dark vibes use low
reverbBrightness; bright/ambient vibes push it high.

## 9. Anomalies (rare dramatic events)
`anomalies` is a list of rare events the Anomaly Engine may fire; each entry is an object tagged by a
`"type"` discriminator. Omit the field (or `[]`) for none. At most one entry of each type. Every
anomaly requires an arrangement — the engine only arms anomalies while a section graph is active.

VOID — `{"type":"void", ...}`: a rare, dramatic breath: the whole mix eases down to near-silence,
holds a suppressed floor for a bar or two — optionally with a single "ghost bar" of the full
arrangement flickering through — then swells back up, resolving by the section boundary. Reverb and
delay tails ring out into the quiet. It requires an arrangement (the void only arms while a section
graph is active). Durations are in musical bars.
- probability (0..1): chance the void auto-fires at each section entry. Keep it low (0.02-0.06) so it
  stays a surprise; the ship default is 0.04. Set 0 and the anomaly is effectively dormant.
- floorLevel (0..1): mix gain at the bottom of the dip. 0.05 = near-silent; 0.15-0.3 = a gentler duck.
- rampDownBars / rampUpBars: musical bars to ease down to the floor and to swell back up.
- floorBarsMin / floorBarsMax: the near-quiet hold length, drawn per occurrence.
- ghostIntensity (0..1): 0 = a clean silent void; >0 punches one bar of the full arrangement through
  the middle of the floor at that gain (1.0 = full-volume flash, 0.3 = a distant echo).
Best on ambient / spacey / cinematic vibes where a rare void adds drama; skip it on relentless dance
grooves.

LICK — `{"type":"lick", "lick": {…}, "chance": 0.02}`: a rare one-statement swap-in of an alternate
riff over whatever lick is playing, reverting after. Requires a lick source (`lick` or `lickRotation`).
`chance` (0..1) is the per-~2-bar-statement swap probability; keep it low. The anomaly lick shares the
lick bank, so `lickRotation.pool` size + 1 must be ≤ 8 (MAX_LICK_POOL). See section 10's riff recipe.

STANDING WAH (not an anomaly) — `lickWah` on the vibe plus `wahLick: true` on a Melodic track:
an always-on tempo-synced bandpass on that track's audio. Any melodic track may opt in, the bass
line channel included. The six voice fields are rateDivision (4 = quarter-note rock of the pedal,
1 = one sweep per bar, 0.5 = one per two bars, 0.25 = one per four), depth, resonanceQ, centerHz,
sweepOctaves, wet.
A track may also set `wahParams` to voice its own pedal instead of inheriting `lickWah`, which is
how two players wah at once without sounding like one pedal: e.g. a bass on rateDivision 0.5,
centerHz 380, wet 0.55 under a lead on rateDivision 4, centerHz 750, wet 0.9. Match centerHz to the
instrument's register — a bass wants 300-450, a lead 700-900 — and keep a bass's wet near 0.5, since
a full-wet bandpass strips the low end the track exists to provide. `wahParams` without
`wahLick: true` is rejected: the params would never run.

WAH — `{"type":"wah", ...}`: a rare few bars where the lead voices swing under a sweeping
tempo-synced wah while the drums, chords, and bass line stay dry. It is a per-track insert, not a
whole-mix effect. A track is eligible only when its role is Melodic, its `lickSource` is LEAD (the
default), and it is not track index 3 (always the bass). Percussive and Chordal tracks are never
filtered, so a vibe with no melodic lead never fires this at all. Give the vibe at least one
melodic non-bass track before declaring it.
- probability (0..1): chance the wah auto-fires at each section entry. Keep it low (0.02-0.06) so it
  stays a surprise; the ship default is 0.03. Set 0 and it only fires from the manual trigger.
- durationBarsMin / durationBarsMax: the armed length in musical bars, drawn per occurrence.
- voice: the sweep itself. rateDivision (4 = quarter-note rock of the pedal, 8 = eighths), depth,
  resonanceQ (higher = sharper vowel), centerHz, sweepOctaves, wet. Same six fields as `lickWah`.
If an eligible lead already sets `wahLick`, the anomaly takes that track's existing wah over for the
armed duration instead of stacking a second filter on it, so make `voice` clearly different from
`lickWah` (a faster rateDivision, a tighter resonanceQ) or the moment will not read as a change.
Best on vibes built around a real lead voice: rock, psych, funk, blues. Skip it on pad-only or
drum-driven vibes, where nothing is eligible.

## 10. Translation recipes — feel -> settings
The highest-value part: translate a described feel into concrete parameter choices.

### Feel and groove
- Straight / mechanical / industrial: swingAmount = 0.0, low timing tension, barStrategy = REPEAT
  on drums, lickMutation <= 0.4. Everything locks to the grid; nothing wanders.
- Shuffled / bluesy: swingAmount = 0.1–0.3, rhythmDensity = BACKBEAT, progressionStyle = BLUES.
  Ghost notes (ghostProbability 0.2–0.3) fill the pocket.
- Loose / human / swung: swingAmount = 0.05–0.15, ghostProbability = 0.2–0.35, humanization dialed
  up on comping tracks. Nothing is perfectly on the grid.
- Hypnotic / loop-based: progressionAnchor = EVERY_8, progressionDriftRange < 0.2, barStrategy =
  REPEAT on bass. The loop repeats before the Markov engine wanders too far.
- Sliding / pedal-steel / glide feel: glideRate 0.35–0.55 on bass and lead melodic tracks.
  Portamento is per-track — set it on every track that should slide.
- Distorted / aggressive: harder engines (WSH, VCF), higher energy, denser rhythm.

### Darkness / mood
- Dark / brooding / heavy: mood <= 0.3, scaleType = MINOR or PHRYGIAN, progressionStyle = DARK,
  reverbBrightness < 0.4, low rootNote register.
- Bright / uplifting: mood >= 0.6, MAJOR or MIXOLYDIAN scale, POP or ASCENDING progression,
  higher reverbBrightness, fuller pads with generous reverb send.
- Exotic / suspended: HIRAJOSHI, IN_SEN, or WHOLE_TONE scale with MODAL or DRONE progression.

### Texture / production
- Wet / spacious / reverb-heavy: space >= 0.6, deep >= 0.6, reverbSize >= 0.6, generous per-track
  reverbSend, high deepFloor so effects don't disappear at low SPACE settings.
- Dry / in-your-face: space <= 0.3, reverbSize <= 0.35, sparse delaySend + reverbSend.
- Distorted / gritty: WSH or VCF engine on bass and lead, higher harmonics and timbre values.
- Clean / polished: VA, PD, or CHD engines; moderate harmonics, lower timbre.
- Rare void / breakdown that drops to near-silence and swells back: add a `{"type":"void", ...}`
  entry to `anomalies` with probability 0.04, floorLevel ~0.05, ghostIntensity ~0.5 for a ghost of
  the arrangement flickering through (needs an arrangement). See section 9.

### Energy / drums
- 4-on-the-floor kick: rhythmDensity = FOUR_ON_FLOOR, kick density ~0.5, barStrategy = REPEAT.
- Backbeat (rock/hip-hop): rhythmDensity = BACKBEAT, snare on 2 and 4.
- Dense 16ths (DnB/techno): rhythmDensity = DENSE_16TH, high-hat density 0.6–0.8.
- Minimal / ambient: rhythmDensity = SPARSE, drum density < 0.2, or replace drum tracks with
  modal/particle hits using MOD or PAR engines at very low density.

### Chord harmony
- Clear progression: use customProgression with 0-indexed scale degrees 0–6, either the bare-degree
  shorthand ${VibeGuideExamples.CUSTOM_PROGRESSION_SHORTHAND} (no glide — a I–IV–VI–VII riff) or the
  object form ${VibeGuideExamples.CUSTOM_PROGRESSION_WITH_GLIDE} when a step needs per-chord glide.
  Size 1–12.
- Static / dronal: progressionStyle = DRONE or customProgression = [0] (single tonic).
- Per-chord glide: set glideRate on the chord-step object itself, inside customProgression — not on
  the track — so playback slides into that chord; glideRate 0.35–0.55 produces a smooth portamento
  into the step.
- Jazz substitutions: when no progressionStyle preset covers the movement, supply
  chordTransitionMatrix as a flat list of 49 relative weights, 7x7 row-major (row = current chord
  degree, column = next).
- chordsPerBar = 1 → slow (one chord per bar); 2 = standard; 4 = busy.

### Lick / riff
- Repetitive 2-note riff (garage rock, industrial): 2 steps with long durations, lickMutation <= 0.3,
  lickMode = Fill. The riff locks in and barely drifts.
- Walking bass line: longer lick (6–8 steps) with varied scale degrees, loopLength matches the phrase,
  moderate lickMutation (0.3–0.5) for a living feel.
- Static drone with embellishment: single-note lick with long duration, low-velocity accents, and
  chordFollow = FIXED so the pitch does not chase chord changes.
- Negative scaleDegree = rest in that step's slot — use this for stop-time: hit, hit, rest, walk-up.
- Rotating riff (per-section variety, avoids monotony): set `lickRotation.pool` to 2–4 licks and the engine
  swaps between them at section boundaries (needs an arrangement). For a rare surprise, add a
  `{"type":"lick", "lick": {…}, "chance": 0.02}` entry to `anomalies` (≈ 1-in-50 per ~2-bar statement) —
  that lick cuts in occasionally, then reverts. Pool size + the lick anomaly must be ≤ 8 (MAX_LICK_POOL).
  `lickRotation` overrides the static `lick` while active.

## 11. Invariants & failure modes
- There are exactly 8 tracks. Do not add or remove tracks.
- Progression / customProgression degrees are 0..6 (seven scale degrees).
- Band matrices are square; chordTransitionMatrix is 7x7.
- Any section with a soloMode requires a top-level `band` — see section 7 for a working one to
  copy. "section(s) [...] declare a soloMode but Vibe.band is null" means you skipped it.
- A `band` must list at least one member (rejected otherwise), and at least two of them should NOT
  be alwaysActive or the handoff has nowhere to go.
- Bass should use chordFollow = ROOT_ONLY to stay in key (don't let it chase chord tones out of key).
- Use FIXED chordFollow sparingly on melodic/chordal tracks — it pins a track off the chord progression; most tracks want FOLLOW.
- For DX/DX2/DX3, set 'harmonics' deliberately (it picks a patch bank, see section 4).
"""
