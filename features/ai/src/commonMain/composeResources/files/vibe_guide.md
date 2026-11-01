# Building a Pulsar vibe — composition guide

Every new vibe starts from a cast. pulsar_cast_vibe rolls an 8-track skeleton (voices, roles,
registers, EDM/SPACE pairs, starting levels) that already obeys the theory rules in section B, and
you compose on it: the frame, groove, progression, melodies, sections and effects. Never copy a
shipped vibe or edit one into a new vibe. The catalog at the end is for ideas only ("how does that
one build its singer?"). To tweak the vibe playing now, read it with pulsar_get_vibe('current').
Sections A-C cover casting, the theory rules, drone beds and expressive voices; 1-12 cover the
controls, and 13 the rest of them. The Reference part at the end holds deep dives (OSC, MST and
grainFx, doubled parts) for when a vibe needs one. Set every value with intent: a vibe that changes
only bpm and root sounds like the last one.

## A. Casting and the voice palette
pulsar_cast_vibe decides, for each of the 8 tracks: its part, the EDM and SPACE engines with their
harmonics/timbre/morph, the role (with chordFollow, and comping style, humanization and fills on a
comp track), the note range, envelopeProfile, macroMap, pins, pan, density, volume, sends and
barStrategy. The frame is a placeholder ("Untitled cast", 100 bpm, A MINOR, envelopeType BLEND,
progressionStyle MODAL), and there is no arrangement, lick, bassLine, band or effects block: those
are yours to write.

Track slots (the engine ties some behaviour to the index):
- 0: a kick about 70% of the time, else a pitched perc hit or a pad. 1: snare, perc or texture.
  2: hat, perc or texture. A Percussive track on 0, 1 or 2 plays the kick, snare or hat pattern.
- 3: always the bass. 4: a lead, or a comp (chord stabs).
- 5 and 6: pad, comp or texture. A texture there is held as a Chordal PAD, so the mid-energy notch
  (section 11) leaves it heard.
- 7: texture or perc, the FX slot. The engine gates its notes off at energy 0.4-0.6 whatever its
  part, so it speaks below energy 0.4, or at energy over 0.6 with complexity over 0.7 (section 11).

Two modes. Pass mode to choose; omit it to use the user's saved choice (BED_FIXED by default).
- BED_FIXED: one of the 8 drone beds in section C is the fixed foundation, on track 5 (a two-track
  bed also takes 6; pins can push it to 4, 1, 2 or 0). Every other track, drums and bass included,
  is rolled, and extra pads roll less often. The safe choice: a known-good bed, variety on top.
- ALL_ROLLED: all 8 tracks are rolled and only the theory rules hold them together. Wilder: no
  bed anchors it, so more pads, textures and odd engines come up. Use it when the user asks for
  something strange, or after a BED_FIXED cast felt tame.

The skeleton JSON leaves out every field at its default value; add any field pulsar_vibe_schema
lists. The seed: omit it for a random roll. The result carries the seed, and the same seed with the same
mode, styleTags and pins rebuilds the same skeleton. Always tell the user the seed and the summary,
so a vibe they like can be remade. The skeleton's `cast` block records that roll (seed, mode,
styleTags, pins). Keep it unchanged in the JSON you apply, so the archived vibe can be cast again;
the engine ignores it.

Reading the summary (one line per track):
  track 4: LEAD 'vowel singer' (pinned), EDM VOWEL_FOF h0.32 t0.5 m0.44 / SPACE CSAW, Melodic FOLLOW, MELODIC, notes 68-79, vol 0.55, pan 0.0
  track 5: BED 'cirrus', EDM DX3 h0.49 t0.25 m0.15 / SPACE ENS, Chordal PAD, DRONE, notes 48-62, vol 0.4, pan -0.35
In order: the part and voice ("BED 'name'" on a bed track, "(pinned)" when you pinned it); the EDM
engine with harmonics/timbre/morph as it plays at mid macros; the SPACE engine (with its own knobs
when it is the same engine, shifted); the role (Melodic with its chordFollow, Chordal with its
comping style, "arp" when it arpeggiates); envelopeProfile; the note range (none on drums); the
starting volume and pan. A track that is quiet at the vibe's macros ends with a note saying when it
speaks (track 7 always does). Volumes, sends and pans are starting points for the mix pass, not a mix.

The tone: the cast writes each unpinned knob as it plays at mid macros and centres the macroMap
window on it, so MOOD swings harmonics and timbre and SPACE swings morph, 0.15 either side. An
unpinned knob plays from the macroMap, not from its JSON value: to change its tone, move its window
(moodHarmonics, moodTimbre, spaceDecay) or pin it (pinHarmonics, pinTimbre, pinMorph). Knobs are
pinned where a voice must hold: DX patches (always), chord intervals, the SPK singer's model,
formant and vowel, and any knob the palette fixes to one value (the timbre VOWEL_FOF, CSAW, TOY and
QUESTION_MARK ignore, CHD's morph), in both slots.

Keep, re-roll or pin:
- Keep the cast and compose on it. Swap an engine only inside its part's family (section 3), and
  keep the rules in section B.
- Re-roll: call again with a new seed, or none. Keep the tracks you liked by pinning them, e.g.
  "pins": [{"track": 4, "engine": "VOWEL_FOF"}, {"track": 5, "role": "pad"}]. A pin holds the part
  and/or the EDM engine; its knobs, range and SPACE partner re-roll. To keep a track exactly, copy
  its JSON from the earlier skeleton into the new one.
- Pin whatever the user names: "a vowel lead" is {"track": 4, "engine": "VOWEL_FOF"}, "an organ
  pad" is {"track": 6, "role": "pad", "engine": "TRIPLE_SINE"}. Pin roles are the parts: kick,
  snare, hat, perc, bass, lead, comp, pad, texture. The engine must be in that part's palette; a
  refused pin names the valid set.
- styleTags: rock, electro, ambient, reggae, psych, funk. A tag weights the parts (ambient: fewer
  drums, more pads and textures; reggae: more comping, on REGGAE_SKANK or SKA_UPSTROKES; funk:
  FUNK_STABS or GOSPEL_STABS) and triples the odds of the voices tagged with that style. Tags lean
  the roll; pins force it. An unknown tag is refused.

The palette is the family list in section 3, with each voice's label: the cast picks from it and
keeps each voice's harmonics/timbre/morph inside a sane range.

The singers (VOWEL_FOF, and SPK as a lead) come with a sung waver: vibratoDepth 25 at 5.5 Hz,
pitchRise 25 over 2 s, and a vowel that opens through each note over 1.5 s: morphRise 0.08 on SPK
and harmonicsRise 0.15 on VOWEL_FOF, each on the knob that is that engine's vowel (section 3).

The cast writes no lick and no bassLine; for melody lines, see section 10's Lick / riff recipes.

## B. Music theory guardrails
The cast obeys all of these. CastRules checks the first six on every cast (the pads' energyDensity
and the comping numbers are set, not checked). Nothing re-checks them after the cast, so keep them
when you edit.
- One register per sustained voice. The registers, in MIDI notes (60 is middle C): bass 28-47, low
  48-59, mid 58-69, high 68-79, top 78-91. Pads, drones, textures and the lead each own one: no two
  of them share more than 2 semitones of noteRangeLow..noteRangeHigh, on either slot. Why: two held
  voices in one octave mask each other and beat, and a lead inside the pad's octave never reads.
  Keep the lead above the drones and clear of the bass. Comp stabs and perc hits are short and may
  overlap.
- The bass holds the root: the bass track is Melodic with chordFollow ROOT_ONLY, and it is the only
  voice below 48. Why: the root anchors each chord, and 40-120 Hz belongs to the kick and the bass;
  a bass chasing thirds and fifths blurs the harmony.
- Every other pitched voice has its own range: noteRangeLow 48 or above and a noteRangeHigh, on both
  engineEdm and engineSpace. Why: a voice with no range plays the genre range, which reaches into
  the bass, and a PAR or noise voice down there turns to rumble.
- Drones and pads stay consonant. A pad is Chordal with comping style PAD, chordFollow FOLLOW,
  restrikeOnChordChange true and arpMode NEVER, on a sustained envelopeProfile (DRONE or EFFECT),
  with energyDensity (1, 1) in its macroMap so no held chord drops out. A Melodic drone on DRONE
  also follows. Why: a drone that follows the progression and re-strikes on each change always
  sounds a chord tone; a FIXED drone holds one pitch against moving chords and lands on a #11 or a
  flat ninth. The chord engines are voiced open, with no third to clash: CHD on its fifth chord
  (harmonics 0.1337), the triple-oscillator engines on fifths and octaves, all pinned so MOOD
  cannot sweep them.
- One arpeggiator at most. At most one Chordal track has an arpMode other than NEVER, and never a pad
  or a chord engine (CHD, TRIPLE_SINE, TRIPLE_TRIANGLE, TRIPLE_SQUARE). About half the casts give
  one stab track arpMode ALWAYS at arpSpeed 0.10-0.15. Why: two arpeggiators play the same
  synchronised root-to-third figure, the cheapest sequencer sound there is, and an arpeggiated chord
  engine smears. AUTO arpeggiates every engine but CHD (section 10), so write NEVER.
- EDM and SPACE are a pair, never twins. Energy above 0.6 plays engineEdm and below 0.4 engineSpace,
  on every track, soloists included. Between 0.4 and 0.6 a pair on two engines re-rolls its slot at
  each loop, leaning to EDM as energy rises; a soloist stays on EDM there, and a same-engine pair
  keeps its last slot. The two must differ: another engine of the same part, or the same engine
  with one knob pinned at least 0.2 away (the rule's floor is 0.15, judged at mid macros). Why: twin voices turn the energy knob into a volume knob; a real pair changes colour as
  the user rides it.
- Comping breathes. A comp track drops 10-30% of its stabs (dropProbability), fills every 4 or 8
  bars with skipProbability 0.2-0.4, and takes ghostProbability 0.15-0.30 only on the busy styles
  (FUNK_STABS, GOSPEL_STABS); the sparse ones keep 0. Why: an all-zero comp repeats one bar forever,
  and on a sparse style the rests are the part.

## C. Drone beds and expressive voices
A drone bed is the foundation: sustained, consonant, and known to sound good. The variety comes from
the voices and melody lines laid over it, not from a new pad recipe every time.

The 8 beds BED_FIXED places whole, each a Chordal PAD on FOLLOW that re-strikes on every chord
("X below" names the SPACE voice, the low-energy side of section B's switch):
- 'ensemble haze': ENS strings at high energy, STR below, notes 48-67, EFFECT envelope, slow LFO.
- 'organ and mellotron' (two tracks): STR mellotron strings at 48-59 under an ENS organ drone at
  57-69, each tone locked by fixed macro windows.
- 'singing string': a resonant STR (harmonics 0.9) gliding at 0.7, notes 49-67, ENS below.
- 'organ drift': a warm VA organ at 49-61 under a deep slow LFO (rate 0.015, depth 0.7), ENS below.
- 'cirrus': a dark DX3 pad (patch 0.49) whose patch walks under a deep slow LFO
  (harmonicsModulation 0.12, modLfoDepth 0.85), ENS below.
- 'grain and strings': a GRN grain texture at 57-72 with an STR string pad below.
- 'vault and choir' (two tracks): a CHD fifth-chord vault at 48-62 (TRIPLE_SINE below) under an
  ADD choir at 60-84 that plays fewer partials at low energy.
- 'siren strings': STR mellotron strings sung through vibrato (25 cents at 5.5 Hz) and a grainFx
  choir, notes 57-72.
A bed keeps its source voice's macroMap, with energyDensity at (1, 1) so every chord sounds. Its
volume starts at 0.45 or under, about 5 dB under the bass (0.8) and near the cast's pads (0.35): a
held chord at full density measures far louder than a plucked bass at the same volume. Leave its
engines, ranges and maps alone, and set its volume and sends in the mix. In ALL_ROLLED, or for a
bed of your own, build it the same way: Chordal PAD, FOLLOW, restrikeOnChordChange true, arpMode
NEVER, envelopeProfile DRONE, energyDensity (1, 1), volume about 0.4, a range inside 48-69, a slow
mod LFO, reverbSend 0.45-0.7, and a paired SPACE engine (ENS with STR, CHD with TRIPLE_SINE).

Expressive fields. Each OrpheusEngine has its own, so the EDM and SPACE voices can differ (all but
hold, which reads engineEdm only). They are what makes a voice sound alive:
- Mod LFO: modLfoRate, modLfoDepth, modLfoShape, modLfoCoupling, each 0..1 (depth 0, the default,
  is off). It runs only on tracks 5-7 and on any track whose envelopeProfile is DRONE, and wanders
  the unpinned harmonics, timbre and morph. On the SPACE side of the energy switch (section B) it
  is a smooth, evolving LFO at modLfoRate; on the EDM side a beat-synced slope whose speed follows
  bpm and complexity; between them it crossfades. A DRONE track
  moves at every energy; tracks 5-7 otherwise barely move at mid energy. Recipes: a breathing drone,
  rate 0.02-0.05, depth 0.3-0.5, shape 0.1-0.3, coupling 0.2-0.35; a deep wander, depth 0.7-0.85.
  On a pinned DX pad, harmonicsModulation 0.03-0.12 lets the LFO walk the neighbouring patches.
- Vibrato: vibratoDepth (0-100 cents, 0 is off) at vibratoRate (0.5-12 Hz, default 5.5), fading in
  over 0.3 s after each note-on. Recipes: a sung waver, 20-30 cents at 5-6 Hz; a slow bowed swell,
  10-15 cents at 3-4 Hz. Past about 50 cents it reads as a siren.
- Rises, re-armed on every note-on and then held: pitchRise (0-1200 cents) over pitchRiseTime
  (0.1-8 s); harmonicsRise and morphRise (-1..1) over harmonicsRiseTime and morphRiseTime (0.1-8 s).
  They add on top of pins. Recipes: a singer leaning into the note, pitchRise 25 over 2 s; a scoop
  into each note, 100-200 over 0.3-0.5 s; a siren, 1200 over 4-8 s. A tone that blooms through the
  note, harmonicsRise 0.15-0.3 over 1-2 s (not on DX, SPK, CHD, MST or the triple-oscillator
  engines, where harmonics picks the patch, the speech model, the chord, the wavetable bank or the
  interval). A vowel that moves: morphRise 0.08 over 1.5 s on SPK; on VOWEL_FOF, harmonicsRise
  0.1-0.25, since there harmonics is the vowel.
- grainFx: true records the track's own voice and replays it as a grain cloud. grainMode BURST
  (grains while the note holds), SINGLE (one per note) or CONTINUOUS (on the step clock);
  grainScatterMode CHORD (chord tones), SCALE or RAW (an unquantized smear); grainVoicing EVEN,
  CENTERED (clustered on the played note) or RISING; grainScatter 0..1 (1 is two octaves either
  side), grainRate 0..1 (up to 130 grains a second), grainSize 0..1 (0.25 about 28 ms, 0.5 about
  117 ms, 0.75 about 0.48 s, 1 two seconds), grainShape (0 a click, 1 a slow swell), grainBlend (0
  the dry voice, 1 all grains), grainRandomTiming (looser and wider). The choir recipe: BURST, CHORD,
  CENTERED, grainScatter 0.2, grainRate 0.6, grainRandomTiming true, grainShape 1, grainSize 0.75,
  grainBlend 0.45. An upward grain is the voice sped up, so grains squeak above about F5 (MIDI 77):
  keep a grainFx voice's noteRangeHigh at 77 or under. MST grains its own wavetable and ignores
  grainFx.
- Double voice: doubleDetune renders the voice twice, that many cents apart (0..50; 8-15 thickens a
  singer or lead, 20+ is a wide chorus, 0 is off). doubleSpread 0..1 widens the pair around the track's own
  pan, like a balance control on a stereo source; the spread part skips a track's wah. A section can
  change both through TrackSectionOverride.doubleDetune / doubleSpread; changes glide over 50 ms, but
  0 cuts the double instantly, so vary the amount rather than toggling it. It costs a second engine
  on that track, so use it on one or two leads, not the drums. Ignored on MST. Each slot (engineEdm
  and engineSpace) has its own. Orphic Lore's Sirens use 18 and 0.8.
- Hold: holdProbability (0..1) with holdLengthMin and holdLengthMax (16th steps, default 2-8). It
  works only on tracks 5-7, on a generated Melodic track (not Chordal, and not one playing a lick),
  and only engineEdm's values are read: the SPACE voice's hold fields are ignored. It is the chance
  a note becomes a held chain. Above 0.5 its notes also favour chord tones and rarely jump octaves.
  Recipe: a texture that sings long tones, holdProbability 0.6-0.8 with lengths 8-16 (half a bar to
  a bar). Sections can change it through trackOverrides. Such a track has no lick, so at energy
  0.45-0.55 it sits in the mid-energy notch (section 11): a held texture sings at the extremes.
- LPG: lpgMode, lpgDecay and lpgColour (0..1), a vactrol low-pass gate after the engine, bypassed on
  every engine by default. PLUCK blooms on each note-on and decays whatever the gate does (plucks,
  mallets, a plucked bass); SUSTAINED opens while the note holds and rings out on release; the
  PLUCK_REPEAT modes (PLUCK_REPEAT, PLUCK_REPEAT_8TH, PLUCK_REPEAT_8TH_OFF, PLUCK_REPEAT_TRIPLET,
  PLUCK_REPEAT_16TH_OFF) re-pick held notes on a grid; BYPASS skips it. A higher lpgDecay rings
  longer; a higher lpgColour lets more highs through. Recipes: a woody pluck, PLUCK with decay 0.35
  and colour 0.45; a ringing bell, SUSTAINED with decay 0.75-0.85 and colour 0.35-0.55; the same
  bell in a bigger room on the SPACE slot, decay 0.88 and colour 0.40.

Weird voices that work with energy. The EDM/SPACE switch (section B) is where a strange voice
earns its place: put it on the slot whose energy suits it and keep the other slot plainer. A
section's macroOverrides push it clearly to one side.
- A lead that sings when quiet and snarls when pushed: SPACE VOWEL_FOF with vibrato and a
  harmonicsRise vowel sweep, EDM CSAW or WSH.
- A texture that turns strange at the peak: EDM QUESTION_MARK or TOY, SPACE MST mist or SPK whisper.
- A pad that changes material, not just level: EDM ENS strings over SPACE GRN with grainFx; or CHD
  at the peak and a TRIPLE_SINE organ in the breakdown.
- Give each slot its own expressive fields: grainFx and a deep mod LFO on the SPACE slot alone make
  the breakdown bloom.
The two slots can measure up to about 9 dB apart at one volume (section 11), so set both.

## 1. The frame
- bpm: tempo. rootNote, scaleType: the key and mode (set the mode to the feel — PHRYGIAN/MINOR dark,
  MAJOR/LYDIAN bright, DORIAN/MIXOLYDIAN bluesy-modal, the BLUES scales for blues/rock).
- envelopeType: AD (tight, punchy), TIDES (notes sustain while held, pads breathe) or BLEND (energy
  crossfades the two: punchy at high energy, loose at low; the cast's default).
- energy / complexity / space / mood / deep (0..1): the starting position of the four live macros
  plus 'deep'. energy = busier+harder, complexity = more rhythmic variation, space = wetter/roomier,
  mood = brighter vs darker. These are the *initial* macro values the user then performs live.

## 2. Groove (GenreProfile)
swingAmount (0 = straight, ~0.3+ = heavy shuffle), ghostProbability (quiet in-between hits),
rhythmDensity (a number 0..1: 0 sparse, 0.33 four on the floor, 0.67 backbeat, 1.0 dense 16ths;
values in between blend the two neighbouring feels), progressionStyle (chord-movement flavour: POP/BLUES/JAZZ/MODAL/...), chordsPerBar,
and customProgression (explicit scale-degree list, degrees 0..6). Recall the reference song's
tempo/key/progression yourself and set these to match.

## 3. Tracks, roles, engine families
Exactly 8 tracks. Each track has a role (Percussive, Melodic or Chordal) and two engines, engineEdm
and engineSpace. When you swap a sound, keep both inside the track's part family. These are the
cast's families:
- kick: BD kick / MOD modal thump
- snare: SD
- hat: HH
- perc (pitched hits, Melodic, high): PAR particle wind / NSE noise tick / MOD modal bell /
  STR string pluck / TOY toy clank / NES chip blip
- bass (notes 28-47): WSH waveshaped / VCF filter / PD round / VA analog /
  DX FM bass (patch at harmonics 0.551) / TRN terrain
- lead: DX3 FM pipes (harmonics 0.1991) or synth orchestra (0.888) / WSH waveshaped / FM two-op /
  WTB wavetable / VOWEL_FOF vowel singer / SPK sung vowels / CSAW notched saw / QUESTION_MARK /
  ADD additive organ / TRN terrain
- comp (chord stabs and keys): DX2 e-piano (0.153), grand piano (0.276) or marimba (0.521) /
  DX3 organ (0.031) / CHD fifth chord / TRIPLE_SQUARE fifth and octave /
  TRIPLE_TRIANGLE chorused fifth / VA stab / STR plucked chord
- pad: ENS string machine / STR bowed string / GRN grain pad / ADD additive choir / CHD fifth chord /
  TRIPLE_SINE sine organ fifth / TRIPLE_TRIANGLE chorused octave / MST grain mist / WTB wavetable /
  VOWEL_FOF vowel choir / DX3 FM pad (0.490) / VA warm organ
- texture (high, wet; on tracks 5-6 the cast holds these as sustained Chordal pads, on track 7 they speak only at the energy extremes): MOD bell / PAR particle cloud / SWM swarm / NES chip / TRN shimmer /
  SPK whisper / MST grain sparkle / GRN grain shimmer / QUESTION_MARK / TOY / NSE noise air
Where the knobs mean something else:
- TRIPLE_SINE, TRIPLE_TRIANGLE and TRIPLE_SQUARE play a chord from one note: harmonics and morph
  pick the two upper intervals (0.66 a fifth, 0.753 an octave, 0.52 a near-unison chorus). Pin both.
- VOWEL_FOF, CSAW, TOY, QUESTION_MARK and the triple-oscillator engines ignore timbre.
- VOWEL_FOF: harmonics picks the vowel (0 "a", 0.25 "e", 0.5 "i", 0.75 "o", 1 "u", blending
  between) and morph the voice type (0 bass, 0.25 tenor, 0.5 countertenor, 0.75 alto, 1 soprano).
- SPK: harmonics picks the speech model (0.1667 is the clearest sung one), timbre shifts the
  formants (0.2 sounds natural), morph picks the vowel (0 "ah", 0.125 "eh", 0.25 "ih", 0.375 "ee",
  0.5 "ah" as in hut, 0.625 "oo"). Pin all three for a steady singer.
- MST (grain mist): harmonics picks the wavetable bank, timbre the position in it, morph the grain
  length, from a blip to four seconds.
- CHD at harmonics 0.1337 is its fifth chord, which fits every scale.

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

A section can also move on its own: rampTo (another macroOverrides block) glides the section's
macros from macroOverrides to rampTo across its bars, e.g. an intro that swells into the verse.
macroOverrides.volume scales the whole mix for the section, and volumeEnvelope (a list of
{bar, level} points, bars counted from the section start) shapes the mix volume over time, e.g. an
outro that fades to silence: [{bar:0,level:1},{bar:4,level:0.4},{bar:5.5,level:0}]. Both need at
least two sections, and an envelope's last point must not pass the section's barsMin.

soloMode on a section activates the band's soloist: Jam(probability) for free improvisation,
LickBuilder(probability, mutationRate) for melodic construction, LongFill for extended fills.

A soloMode DOES NOTHING WITHOUT A BAND. The engine starts a section solo only when the vibe's
top-level `band` field is set; with no band the solo never starts and the section plays as an
ordinary one. The vibe is rejected outright if any section sets soloMode while `band` is null, so
copy this working four-piece and re-point the track lists at your own layout — kit tracks in
"Drummer", the bass in "Bassist", the melodic lead in "Lead", pads/comping in "Colour":

"band": {{MINIMUM_BAND}}

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
string: "trackOverrides": {{TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE}}. All
TrackSectionOverride fields are optional; only set what you need to change (density, volume,
pan (glides there at 0.15 pan units per second), morph, reverbSend, delaySend, envelopeProfile,
compingStyle, sectionInversion, arpMode, chordFollow, holdProbability, holdLengthMin,
holdLengthMax, doubleDetune, doubleSpread). Settings restore automatically when the section ends.

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
Two delay taps, delayTimeA/B, each 0..1 mapped to 0.01-2.0 seconds (seconds = 0.01 + value × 1.99).
They ignore tempo, so convert a note value yourself: value = (seconds - 0.01) / 1.99, where one beat
is 60 / bpm seconds. At 80 bpm an 8th is 0.375 s (0.18) and a dotted-8th 0.5625 s (0.28). Then
delayFeedback (0.2 = subtle, 0.5 = moderate, 0.7+ = building). delayDamping currently does nothing.
Reverb: reverbSize (0.3 = room, 0.6 = hall, 0.9 = cathedral),
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
- Shuffled / bluesy: swingAmount = 0.1–0.3, rhythmDensity about 0.67, progressionStyle = BLUES.
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
- Distorted / gritty: WSH or VCF on the bass, WSH or CSAW on the lead, with the tone set high
  through the macroMap window (moodHarmonics and moodTimbre around 0.6-0.9) or pinned there. CSAW
  ignores timbre.
- Clean / polished: VA or PD on the bass, CHD or VA on the comp, with the moodHarmonics window mid
  (0.4-0.6) and moodTimbre low (0.2-0.4), or pinned there.
- Rare void / breakdown that drops to near-silence and swells back: add a `{"type":"void", ...}`
  entry to `anomalies` with probability 0.04, floorLevel ~0.05, ghostIntensity ~0.5 for a ghost of
  the arrangement flickering through (needs an arrangement). See section 9.

### Energy / drums
- 4-on-the-floor kick: rhythmDensity about 0.33, kick density ~0.5, barStrategy = REPEAT.
- Backbeat (rock/hip-hop): rhythmDensity about 0.67, snare on 2 and 4.
- Dense 16ths (DnB/techno): rhythmDensity 0.9-1.0, high-hat density 0.6–0.8.
- Minimal / ambient: rhythmDensity 0-0.15, drum density < 0.2, or replace drum tracks with
  modal/particle hits using MOD or PAR engines at very low density.

### Chord harmony
- Clear progression: use customProgression with 0-indexed scale degrees 0–6, either the bare-degree
  shorthand {{CUSTOM_PROGRESSION_SHORTHAND}} (no glide — a I–IV–VI–VII riff) or the
  object form {{CUSTOM_PROGRESSION_WITH_GLIDE}} when a step needs per-chord glide.
  Size 1–12.
- Static / dronal: progressionStyle = DRONE or customProgression = [0] (single tonic).
- Per-chord glide: set glideRate on the chord-step object itself, inside customProgression — not on
  the track — so playback slides into that chord; glideRate 0.35–0.55 produces a smooth portamento
  into the step.
- Jazz substitutions: when no progressionStyle preset covers the movement, supply
  chordTransitionMatrix as a flat list of 49 relative weights, 7x7 row-major (row = current chord
  degree, column = next).
- chordsPerBar = 1 → slow (one chord per bar); 2 = standard; 4 = busy.

### Chordal comping and arpeggiation
- arpMode is the highest-consequence field on a Chordal track, and its default is NOT neutral.
  `AUTO` arpeggiates on EVERY engine except CHD — a Chordal track on PAR, STR, DX3, ENS, VA or WTB
  with AUTO rolls root→third on every stab. Only CHD plays a true block chord under AUTO. For a
  sustained pad, a drone bed, or block stabs, you must write `NEVER` explicitly.
- AT MOST ONE Chordal track may arpeggiate. Two or more AUTO tracks on non-CHD engines produce
  identical, synchronized root→third figures — the single strongest "cheap sequencer" tell in the
  whole schema. Pick the one comping voice (usually the mid-register, moderate-density one) and set
  every other Chordal track to `NEVER`.
- The arpeggiating track wants arpSpeed 0.10–0.15. At 0.2+ the second note blips instead of leaning;
  0.9–1.0 is a strummed chord, not an arp — use that only for a deliberate stab.
- Never ship an all-zero CompingHumanization with fills.everyNBars = 0. That combination repeats a
  byte-identical bar forever, which no player does. Give a comping track dropProbability in the
  0.10–0.30 band and fills every 4–8 bars with skipProbability 0.2–0.4. Zero those only when the
  vibe is deliberately mechanical (industrial, motorik, chiptune).
- ghostProbability fires on the steps you left EMPTY, so how much of it a style can take depends
  on how dense that style is. On a busy pattern (FUNK_STABS, GOSPEL_STABS, DOUBLE_TIME comping)
  0.15–0.30 reads as a player filling the pocket. On a sparse one it writes a different part:
  REGGAE_SKANK is two hits and fourteen rests, and the rests ARE the skank — keep it at 0 there,
  and under 0.1 on PAD and ROCK_DOWNBEATS. It defaults to 0, so omitting it is always safe.
- CompingHumanization fields are independent: naming three of them still inherits the fourth.
  Write out every field you care about rather than assuming the block as a whole is authored.
- octaveJumpProbability and extensionProbability are different in kind from the other two: they
  shift a step ±12 semitones and add 2 or 5 semitones, so they rewrite PITCH rather than rhythm,
  they can both land on one step, and every probability is scaled up by complexity — loudest in the
  busiest late sections. They default to 0 for that reason. Set them on ONE track when a part wants
  harmonic wandering, keep them under ~0.2, and never put them on two chordal tracks that both
  arpeggiate, or the voices scatter against each other.

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
- A lick on a Melodic track 5 or 6 is also what makes it heard at mid energy: a track with lickMode
  Fill or Squash (and textureLine false) is lifted out of the mid-energy notch (section 11). Track 7
  stays note-gated at mid energy even with the lick.

### Mix (see section 11 for the targets)
- Reggae / dub / roots, where the bass is the song: the bass is the loudest track, 0-3 LU above the
  kick. Give its engines volume 0.9-1.0 and pull the other tracks down to make room; keep it dry
  (reverbSend and delaySend under 0.1), at pan 0, with noteRangeLow/High about 28-47. The skank
  (a Chordal track on comping style REGGAE_SKANK, arpMode NEVER) sits off-centre at pan about ±0.3
  with delaySend 0.3-0.5; set delayTimeA/B to the 8th and the dotted-8th at the vibe's bpm (section
  8) with delayFeedback 0.4-0.55. Drums stay mostly dry: kick and hats send nothing, while the snare
  or rim gets the delay throw (delaySend 0.3-0.5, or only in a dub section through trackOverrides).
  Warm reverb: effects.reverbBrightness 0.25-0.4. For dub space, give one texture a section `pan`
  move (e.g. -0.5 in the verse, 0.5 in the dub section) so it drifts across while the bass holds
  the centre.
- Psychedelic / wide: spread the textures and pads to ±0.5-0.7 in balanced pairs (one left, one
  right at the same depth). Give them section `pan` moves so the voices drift: a pad at -0.6 in one
  section and 0.4 in the next crosses in about 7 s. delayFeedback 0.5-0.7 for long trails; space
  0.6-0.75 and deep 0.65-0.8 with deepFloor 0.35-0.5 so the effects persist when the user turns
  SPACE down; reverbSize 0.75-0.9. Keep the low end centred and clean: kick and bass at pan 0 with
  sends under 0.15, and every pad and texture at noteRangeLow 48 or higher so the wash stays out of
  40-120 Hz.

## 11. Mixing
A mixed vibe puts each part where the genre wants it and plays at the shared loudness.

### The fields
- `volume` (each OrpheusEngine, 0..1, max 1.0): the track's level in that slot. engineEdm plays at
  high energy and engineSpace at low energy, and the two can measure up to ~9 dB apart at the same
  volume (engine trims and patches differ). Set both so a track doesn't jump when energy crosses
  between them.
- `pan` (TrackVoice, -1 = hard left, 0 = centre, 1 = hard right; default 0).
- `reverbSend`, `delaySend` (each OrpheusEngine, 0..1): post-fader sends to the shared reverb and
  delay.
- `reverbBrightness`: on each OrpheusEngine (that voice's tail) and in `effects` (the whole reverb).
  Low = warm, high = shimmery.
- `noteRangeLow` / `noteRangeHigh` (each OrpheusEngine, MIDI notes): a Chordal or Melodic voice with
  no range plays from the genre range, which reaches down into the bass. Give every non-bass voice
  its own range.
- `levelDb` (top-level, -12..12 dB, default 0): a whole-vibe trim. It scales every track and the
  storm, street and speech voices, sends included, so it changes loudness, not balance. Set it last.
- Section `trackOverrides` (section 7): `volume` REPLACES the track's base volume for the section,
  so to move a track by N dB scale its base and every section override for it by the same factor.
  `pan` glides the track there at 0.15 pan units per second and back on exit, so moves drift across
  bars. `reverbSend` / `delaySend` change that section's sends, e.g. a dub throw.

### Targets
- Kick and bass within about 3-5 LU of each other, except where the genre says otherwise: a
  reggae / dub or "the bass is the song" vibe runs the bass hotter.
- No track except kick and bass with more than about 40% of its energy in 40-120 Hz. A PAR or noise
  voice left in the genre range turns to rumble; raise its noteRangeLow.
- Kick, bass and the lead centred. Doubles and pads at ±0.3-0.6, with balanced sides (as much
  on the left as on the right).
- Loudness about -16 LUFS ±2. Aim for -16 ±0.5 when you set levelDb.
- Past knee under about 3%. Above that the bus clipper is shaping the mix; pull levels down.
- The mid-energy notch: at energy 0.45-0.55 tracks 5-7 play about 26 dB down; the dip starts at
  0.35 and is gone by 0.65. Two things lift a track out of it: a Chordal role, or a Melodic role that
  plays the vibe's lick (lickMode Fill or Squash, with textureLine false). A soloing band member is
  lifted too. A section `volume` cannot rescue it: it is 0..1 and replaces the base level.
- Track 7, the FX slot, is also note-gated: at energy 0.4-0.6, and over 0.6 unless complexity is
  over 0.7, it plays no notes whatever its role, lick or volume. It speaks below energy 0.4 (an
  intro or breakdown whose macroOverrides take energy there, e.g. energy × 0.6 from 0.5), at a peak
  over 0.6 with complexity over 0.7, or while it solos. textureLine true on a Melodic track 7
  opens the gate but keeps the notch, so it plays about 26 dB down at mid energy.
- The EDM and space slots can differ in loudness at the same volume (see `volume` above).
- Track volume tops out at 1.0. If a track needs more, pull the others down.

### The mix pass
When the live mix tools are available (transport, mix_read, mix_set, audio_analyze, mix_capture),
take one or two short listening passes of your own after pulsar_apply_vibe succeeds, no more, then
hand the mix to the user. Without them, set the fields from the targets above and stop there.
1. The vibe plays once applied; if transport (action=state) says it is paused, call transport with
   action=play. Call mix_read once to start the meters, do the other checks (audio_analyze records
   for the seconds you ask, 5 by default), then call mix_read again: you cannot wait between two
   calls in one round, so the checks in between are the wait.
2. Read lufsShort (the last 3 s) and lufsIntegrated (since metering started), pastKneePct, and per
   channel relLu (LU against the dry mix; the kick-bass gap is the difference of their relLu),
   subPct (under 40 Hz), lowPct (40-120 Hz) and pan. Call audio_analyze for spectra, low end and
   stereo per track. Use section_jump to hear a high-energy and a low-energy section.
3. Fix the obvious, one thing at a time, with mix_set (field level_db, pan, reverb or delay;
   scope=vibe for every section, scope=section for the playing one; pan is always whole-vibe), then
   mix_read again. level_db is the dB the track is heard at in the playing section (0 = as written,
   -30 to 3.5).
4. Loudness, only when it is off: after about 30 s of reads, if lufsIntegrated misses -16 by more
   than 2 LU, the fix is levelDb, which needs a re-apply. A re-apply clears the live mix edits, so
   call mix_capture first: it writes the moves into your vibe's JSON, archives it and restarts the
   song. Then call pulsar_get_vibe with 'current' (it holds the captured moves) and add
   -16 - lufsIntegrated to its levelDb (it may already carry a trim), rounded to 0.1. Boosts land
   short because the bus clipper compresses them, so overshoot a boost by about a quarter (a +4 dB
   need: write +5). Re-apply with pulsar_apply_vibe and read once more. That is the second pass:
   stop there.
5. In the app, each turn allows {{MAX_APPLIES}} applies and, when the mix tools are available,
   {{MAX_MIX_EDITS}} mix edits (mix_set, mix_mute, mix_solo). When a tool says the budget is spent,
   do not retry: reply to the user now.
6. Hand the mix over: reply with the vibe's name, the seed (for a new vibe) and a sentence on its
   sound. When the mix tools are available, invite the user to open the Mixer tab, move faders and
   knobs, and tell you when they are back; then mix_read, say what changed and suggest the next
   move, and call mix_capture when they want the mix kept (it restarts the song). The user judges the sound; the numbers only guide it.

### Gotchas
When the mix tools are available:
- The first mix_read starts the meters and returns silence; read again after other calls have taken
  a few seconds (audio_analyze, a section_jump), not straight after it. A vibe load
  (an apply or a capture), or ~10 s without a mix_read, restarts lufsIntegrated.
- Meter channels 0-7 are tracks 0-7 by index. Their names (KICK, PERC, HIHAT, BASS, KEYS, PAD,
  TEXT, FX) are fixed slot labels, not the vibe's instruments: find each part by its track's engine
  and role in the JSON (audio_analyze's trackLabels lists the engines).
- audio_analyze track stems are dry: after the fader and pan, before the reverb and delay sends.
  Effect returns appear only in the bus and out stems, so judge sends there or by ear.
- mix_set level_db is the heard level in the playing section. Whole-vibe loudness is `levelDb` in the
  JSON.

## 12. Invariants & failure modes
- There are exactly 8 tracks. Do not add or remove tracks.
- Progression / customProgression degrees are 0..6 (seven scale degrees).
- Band matrices are square; chordTransitionMatrix is 7x7.
- Any section with a soloMode requires a top-level `band` — see section 7 for a working one to
  copy. "section(s) [...] declare a soloMode but Vibe.band is null" means you skipped it.
- A `band` must list at least one member (rejected otherwise), and at least two of them should NOT
  be alwaysActive or the handoff has nowhere to go.
- Bass should use chordFollow = ROOT_ONLY to stay in key (don't let it chase chord tones out of key).
- Use FIXED chordFollow sparingly on melodic/chordal tracks — it pins a track off the chord progression; most tracks want FOLLOW.
- No more than one Chordal track may leave arpMode at AUTO on a non-CHD engine (see section 10) —
  the rest must say NEVER, or the vibe stacks identical arpeggios.
- For DX/DX2/DX3, set 'harmonics' deliberately (it picks a patch bank, see section 4).

## 13. More controls
Controls and behaviours the sections above leave out. Section B's rules still hold.

### Frame and groove
- stepCount: 16 (one-bar patterns, the default) or 32 (two-bar phrases).
- rootNote takes enharmonic names: G_SHARP is A flat.
- deep: 0.4-0.7 for a wet vibe, 0.2-0.3 for a dry, punchy one.
- progressionStyle in plain terms: POP (I-IV-V-vi), BLUES (12-bar), JAZZ (ii-V-I), DARK (diminished
  and minor), SAD (descending), ASCENDING (rising), MODAL (no resolution), DRONE (static).
- ghostProbability: 0.05-0.15 subtle, 0.2-0.3 funky, 0.4 and up very busy.
- The genre's noteRangeLow / noteRangeHigh are the fallback range for any voice without its own
  (36 is C2, 48 C3, 60 C4, 72 C5).
- progressionAnchor: how often the progression returns to its starting chord. EVERY_4 or EVERY_8 is
  typical; EVERY_2 is a tight leash, EVERY_16 a whole verse, NONE never resets.
  progressionDriftRange (0..1): how far it may wander between resets, 0.1-0.2 tight and hypnotic,
  0.5 and up loose and jazzy.

### Track fields
- density (0..1): the chance a generated step gets a note.
- envelopeProfile: RHYTHM, MELODIC, EFFECT, WILD or DRONE. Match it to the macroMap preset unless
  you have a reason not to.
- barStrategy: REPEAT (the same pattern every bar), MUTATE (small changes each bar), FILL (fills at
  phrase ends), CALL_RESPONSE (two complementary patterns take turns), INDEPENDENT (a new pattern
  every bar). Keep the drums and bass on REPEAT or MUTATE; INDEPENDENT is for texture layers.
- evolution: optional drift of the pattern, a rhythmic part and a pitch part (Contour for melodic
  tracks, Voicing for chordal ones). evolutionWeight (default -1, automatic) sets how much the
  tension cycle moves the track.
- soloBehavior and duckingProfile: how the track behaves while another band member solos.
- Tracks 3 and 4 get a lift at mid energy instead of the notch. Track 4 is the only slot with no
  ducking.
- A Melodic role with textureLine true keeps a lick line on tracks 5-7 inside the texture fade (it
  skips the lick lift), and on track 7 the FX note gate never drops it. Off by default.

### macroMap details
- energyDensity is the note chance for generated steps only. Lick steps skip that roll, so a lick
  note that should only sometimes play sets hitProbability on its step.
- morph follows spaceDecay (the SPACE knob), not MOOD, and no dimension maps mood to morph. To make
  morph move with mood, pin it and move it another way (the mod LFO or tension).
- A moodTimbre max at 0.001 or below turns tension drift off for harmonics, timbre and morph on the
  whole track. To keep timbre still under tension but let harmonics move, set evolutionWeight
  explicitly instead of -1.
- Pin or window: a pin (pinHarmonics, pinTimbre, pinMorph) locks the value and stops both tension
  drift and the LFO walk; a window with min == max locks the knob but keeps tension drift; a narrow
  window keeps everything moving inside limits.

### Comping fields
- comping style: PAD, FUNK_STABS, ROCK_DOWNBEATS, SKA_UPSTROKES, BLUES_SHUFFLE, JAZZ_COMP,
  REGGAE_SKANK, GOSPEL_STABS, each with its own 16-step accent pattern.
- arpDirection: UP, DOWN, UP_DOWN, RANDOM. sectionInversion: FOLLOW_STYLE, ROOT_POSITION,
  FIRST_INVERSION, SECOND_INVERSION, OPEN_VOICING.
- fills: everyNBars, skipProbability and fillType: ASCENDING_ARP (chord tones walk up),
  DESCENDING_ARP (walk down), TURNAROUND (a 4-beat landing into the next bar), DOUBLE_TIME (a chord
  tone on every step), STAB_FLURRY (an empty half, then rising stabs), DROP_OUT (silence but one
  accented downbeat), NONE.

### Lick steps
- A lick is a list of steps, each a scaleDegree (0 is the root), a duration in beats and a velocity
  (0..1), up to 64 steps. A negative degree is a rest; at high lickMutation a rest has about a
  mutation × 0.3 chance of filling with a note.
- lickMode Fill spreads the lick across its bars; Squash compresses it into one bar.
- loopLength (beats) longer than the steps' total adds a rest at the end: a phrase, then space.
- lickOctave: -1 (automatic, the middle of the track's range) or 0-8 to hold the lick in one octave.
- With lickRotation, keep a static lick as well: without an arrangement the pool falls back to one
  pick at load.

### Voice extras
- A siren's pitchRise wants envelopeProfile DRONE, so the volume swells in with the climb.
- The rises are added after the note-range clamp, so a big pitchRise can climb past noteRangeHigh.
  They restart on PLUCK_REPEAT re-picks, and a note-on glides them back over 80 ms so a legato
  restart never clicks.
- harmonicsModulation above about 0.20 walks a DX pad into unrelated patches.

### Tension and effects extras
- tension.evolution: timbre, morph and harmonics drift ranges across the tension cycle. attackPoint
  (0..1) is where the peak sits; releaseSpeed is how fast it snaps back.
- spurtChance: the chance per bar of a random burst (0 = tension only).
- delayFeedback is capped at 0.95. A voice's own delayFeedback overrides the vibe's for that voice.

### Arrangement extras
- barStep (default 1) snaps a section's random length to multiples: 2 for even-bar phrases, 4 for
  4-bar blocks.
- recencyDecay: the share of its weight a target keeps while it is the section just left. 1.0 is no
  penalty, 0.5 half, 0.0 never doubles back while another edge exists; it recovers over later
  sections. 0.4-0.6 keeps a three-section vibe from locking into one rotation.
- introIndex opens the song (default 0; null is a weighted random start). outroIndex closes it (null
  loops forever).
- lengthSeconds (default 150..240, each bound 15..1800): the song's auto-end window.
- playOnce true plays every section once, in list order from introIndex with outroIndex last, then
  hands off to the next vibe. It needs both indexes, and lengthSeconds no longer ends the song.
- A section can set compingStyle, compingInversion, compingHumanization and chordFollow for every
  chordal and melodic track at once, plus its own customProgression, chordsPerBar and bpmMultiplier
  (0.5 a half-time breakdown, 2.0 a double-time burst).
- trackOverrides also take lpgMode (a guitar that re-picks on PLUCK_REPEAT_TRIPLET only in the jam)
  and morphRise. On SPK at its clearest sung model (harmonics 0.1667, timbre pinned low) morph is
  the vowel and glides only between neighbours, so a section's morph plus morphRise gives that
  section its own vowel shape.
- A section volume lands up to 200 ms after the boundary, so keep the boost moderate.

### DX patches that change by section
harmonicsMacroRange with harmonicsMacroSource (MOOD by default, or ENERGY, COMPLEXITY, SPACE), on
DX, DX2 and DX3 only: that knob walks the pinned patch across base ± range, and at the knob's
midpoint the base patch plays. It adds to harmonicsModulation. A section's macroOverrides scale the
knob, so each section lands on its own patch with no per-section setting: with mood at 0.7, a chorus
at mood × 1.25 walks up a patch or two while the groove holds the base. Pick the base patch at the
centre of a tonal family. 0.03 is about ±1 patch (subtle, the lead keeps its identity); 0.04-0.05
about ±1-2 (audibly different per section, same family); 0.08-0.10 about ±3 (dramatic, and the
chorus may sound like another instrument). On other engines it does nothing.

### When a vibe does not hang together
- Too many tracks compete for the focus: lower the volume and density of the secondary ones.
- INDEPENDENT on too many tracks: nothing locks in.
- progressionDriftRange too high: the chords wander aimlessly.
- Texture tracks too dense: they crowd out the groove.

## Reference: deep dives
Read these only when a vibe needs one: OSC, MST and grainFx in detail, and doubled parts. Section
B's rules still hold.

### OSC, the panel oscillator
OSC is a triangle/square oscillator with self-feedback and a second oscillator for FM, the same
voice as the Orpheus synth panel. It is outside the cast's families: use it to bring a panel sound
into a vibe as a bass, keys or lead.
- harmonics is self-feedback grit, capped at 0.35 (above that, low notes freeze into a thump).
  timbre crossfades triangle (0) to square (1).
- fmRatio (0..16, 0 is FM off): the modulator as a multiple of the carrier. Whole numbers stay in
  tune; fractions (1.5, 2.7) turn clangy and bell-like. fmShape (0..1): sine through triangle to
  square, brighter as it rises. fmFreeHz (0..2000) replaces the ratio with a fixed rate, only to
  copy a panel preset exactly; the tone then changes with every note.
- FM depth is morph. Unpinned it follows SPACE through spaceDecay, so set pinMorph true to make the
  written morph the depth. Accent never reaches morph.
- Notes below 40 fold up an octave, so keep noteRangeLow at 40 or above.

### MST and grainFx in detail
- MST: harmonics picks one of 12 wavetable banks; pin it, or the accent boost and the macros hop
  banks. timbre moves through the bank. morph (grain length) is honoured only with pinMorph true.
- grainRate below about 0.04 is dead: CONTINUOUS is silent and BURST acts like SINGLE. Below about
  0.5 a BURST note one 16th long gets one grain. At 1.0 CONTINUOUS fires on every tick, so
  grainRandomTiming matters only below about 0.97.
- Grains spread across the stereo field only when something is random: grainScatter 0 with even
  timing sits in the centre.
- On a Chordal track MST has no native chord, so arpMode AUTO arpeggiates and SINGLE catches only
  the first arp note. Use BURST, or arpMode NEVER with grainScatterMode CHORD so the grains supply
  the chord.
- CONTINUOUS follows the shared swung 16th clock, rests included, and ignores the track's own note
  gating. On track 7 the texture volume curve, not the note gate, decides when it is heard. A
  section density of 0 still mutes it; a low non-zero density does not thin it.
- grainFx: a blend-1 cloud sits within about 1 dB of the dry voice, but dense clouds (fast rate,
  long grains) meet a limiter and can sit lower. A slow grainShape lets the first grain speak sooner
  (clicky shapes hold up to 80 ms). On a sung voice keep grainScatter small (0.02-0.05): the grains
  are pitched by playback speed, which moves the formants too. An upward grain starts further back
  in the recording, so a long one is an echo of earlier notes (a 2 s grain a fourth up starts about
  670 ms back).

### Doubled parts and bigger bands
- Two drummers: the main kit on tracks 0-2 (BD, SD, HH, Percussive) and a tuned second kit on
  tracks 5 and 6: MOD for ringing pitched metal (Melodic, chordFollow FIXED so it rings in the key
  without chasing the chords) and PAR for a particle scatter, Melodic too, since hold works only on
  a generated Melodic track. Give both barStrategy INDEPENDENT, density 0.10-0.15 and
  holdProbability 0.4-0.6, so the hits spread across bars and the MOD rings past the hit. With no lick line,
  Melodic tracks 5-6 sit in the mid-energy notch, so this kit speaks mostly when energy moves away from the middle.
- A band can have five or more members when a part deserves its own identity (a second drummer, a
  lead apart from the keys). The matrices grow with it: five members means 5x5. alwaysActive can
  sit on more than one member, e.g. both drummers anchoring the pocket.
