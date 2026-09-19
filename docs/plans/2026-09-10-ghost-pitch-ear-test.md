# Ghost Pitch Ear Test

Branch `fix/pulsar-ghost-note-pitch` (3e29ffde7) on `origin/main` (ecbb7a915). Ear tests are user run.
Items are grouped by how to treat them: **Verify** (a fix that should already work; a failure
is a bug), **Judge** (a call only your ears can make), **Expect** (known behaviour, flagged so
it is not mistaken for a fault).

**What changed.** Complexity switches empty steps on as quiet ghost notes, per loop-cycle in
`mutate_patterns` and at render in `bar_strategy_mutate`. Every generator clears an empty step
to MIDI note 0, so each ghost played a C folded up to the track's note-range floor or the
engine's `note_min` (C3 on OSC), whatever the key. Ghosts now repeat the nearest written note
before them (`ghost_pitch_source`, wrapping), and a track with nothing written grows none.

**A/B.** A is `origin/main`, B is this branch. Before each launch run
`./gradlew :apps:djapp:desktopApp:buildDesktopNative`: the DJ app packages its dylib one build
late, so skipping it auditions stale DSP. Launch `./gradlew :apps:djapp:desktopApp:run` under
`nohup`.

## DogHouse, A/B (benchmark; this is where it changes)

E Phrygian, 85 BPM. Tracks 5 (strings), 6 (grain) and 7 (modal wild card) are generative and
follow the chords. Their ghosts were pinned to the C at the bottom of each range (C3, C3, C2),
and with glide 0.3-0.4 the voice slid down to it and back. C is the b6 of E Phrygian, which is
why it never sounded wrong. Ghosts accumulate until each deja-vu reset (every 15 bars at the
chorus's Complexity 0.52), so listen late in the chorus and solo. Track 7 (WILD budget) grows
the most.

- Verify: the kit, bass and keys match the A build. The ROOT_ONLY bass plays the chord root
  either way, the keys restore their written comp every bar, and drum ghosts keep their pitch.
- Judge: late in the chorus and solo the texture layer no longer dips to a low C between
  phrases; a ghost re-strikes the note before it, quietly. Audible, and better?
- Judge: without those dips, does the texture feel too still? The lever is
  `complexityVariation` on tracks 5-7, not the pitch rule.
- Verify: no new clicks, stuck notes or dropouts on tracks 5-7.

## Fire Sky (null test)

G blues, 32 steps. All three riff licks fill their 8-beat `loopLength` exactly and hold tails
are gated, so tracks 4 (riff) and 6 (fourths double) have no empty step for a ghost. The organ
(track 5) restores its comp every bar. A difference here means a ghost reached a step this
trace missed.

- Verify: the riff and the double match the A build through intro, build and verse.
- Verify: the organ comp and its turnaround fills are unchanged.
- Verify: Fire Sky .5f plays the same licks and matches its A build too.
- Expect: chorus, lead-in and solo push Complexity past 0.85 (base 0.78 x 1.2 / x 1.4), where a
  track's step count can creep past 32. A ghost on an added step now repeats the riff's last
  note instead of a C. Rare, and the next deja-vu reset restores 32 steps.

## Bell Tolls, A/B (where the lick fix is audible)

A minor pentatonic, 78 BPM. The horn on track 4 (DX2, DX3 on space) plays a 4-beat Fill lick in
an 8-beat `loopLength`, so steps 16-31 are written space and collect the ghosts. In groove and
solo the horn follows the chords, so a ghost was pinned to a C near the bottom of its range.
In the chorus (track 4 FIXED) and in dub and outro (all tracks FIXED) nothing pinned it and it
rendered at MIDI 0 (DX2 `note_min` is 0, about 8 Hz). A ghost now repeats the lick's closing A.
At base Complexity 0.45 a new one arrives roughly every 35 seconds, about three before each
deja-vu reset, so give each section a couple of minutes.

- Verify: in the chorus, dub and outro, quiet horn notes now sound in the gap after the phrase,
  on the closing A. On the A build those steps were silent or a faint low thump.
- Verify: in the groove and solo, a ghost in the gap sounds A, not a low C.
- Judge: the lick was written as a stab then space. Do the echoes read as dub echo, or crowd
  the space? If they crowd it, lower track 4's `complexityVariation`; the pitch rule is not the
  lever.
- Verify: no new clicks, stuck notes or dropouts on the horn.

## Everywhere

- Expect: ghost frequency is unchanged; only pitch changed. The rate is still
  `complexityVariation x 0.08` per empty step per cycle.
- Expect: a track with nothing written (for example a section at density 0) no longer collects
  quiet ghosts. It stays silent.
- Expect: ROOT_ONLY lick tracks (the Rust Belt and Black Cat bass hooks) are unaffected; the
  render swaps every pitch for the chord root.
- Expect: ghost growth is still wiped by any lick re-render (vibe load, pool slot change,
  deja-vu reset). Carrying it across is the Kicking Bubblegum lane.
