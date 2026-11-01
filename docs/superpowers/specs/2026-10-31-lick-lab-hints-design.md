# Lick Lab hints, boost and track waves (2026-10-31)

A dated snapshot. It adds to `2026-10-30-lick-lab-design.md`, and the code is the authority once built.

Status: landed on main 2026-10-31. Dated snapshot; the code is the source of truth.

## Why

The user jams in Lick Lab over a playing vibe. Today the keys light only the chord that is sounding, so the
user cannot tell where the harmony goes next and a lead line arrives late at each change. The user also
wants to see each track move while jamming, and to hear their own part over the band when they want to.
Long term this becomes a game with accuracy and style scores, so the hint must be a fair target: always
right, never a guess.

## Goals

1. **Next-chord hint on the keys.** In the last beat before each chord change:
   - The keys of the next chord's notes (root, third, fifth, in the vibe's scale) glow teal, rising from
     faint to full across that beat.
   - One preferred key gets a **single zip**: one light runs one lap around the key's border over that
     beat and lands at the top exactly on the change, where the key gives one soft gold flash.
     Brightness as in mockup v2 of the single zip: about 80% peak, a 2.5 px line with a soft glow.
   - On the change the glow hands over to the normal "current chord" highlight.
2. **HINT switch, three ways: ROOT · NEAR · MOVE**, default NEAR. The user heard these as difficulty
   levels; for now it is a plain switch.
   - ROOT: the next chord's root nearest the user's hand.
   - NEAR: the next chord's tone nearest the user's hand (smallest move into the new chord).
   - MOVE: the next chord's tone that is not in the current chord, nearest the user's hand; falls back to
     NEAR when the chords share every tone.
   - "The user's hand" is the held key, else the last played key, else degree 0 of the slot.
3. **The hint is always right.** The engine reports the next chord before it lands, and it is the chord
   that then plays, including:
   - a progression that mutates or re-anchors at its wrap (seeded; the engine works the next chord out on
     a copy of its chord state, so the real roll is untouched), and
   - the first chord of the next section (known during the section pre-roll).
   Reporting must not change how any vibe sounds: no extra random draws on the real state.
4. **BOOST toggle.** In the PLAY row. While on, the armed track plays louder (+4 dB as a starting value).
   It never enters the vibe's mix, History, Capture, presets or anything saved. Off, disarm, hide,
   vibe change and leaving the mixer all drop it.
5. **Aligned track columns with waves.** PLAY chips, a wave row, and CALL chips share one column per
   track, so a track sits in the same column in each row. The wave is the mixer strip's own live wave
   (`MixerWave` fed by the mixer meters). The armed track's column is lit. Non-track controls sit in a
   last column: BOOST beside PLAY, "none" beside CALL. The armed track's CALL chip is greyed out.
6. **Lick Lab button moves** from the title bar to under the "Loop section" chip.

## Non-goals

- No scores, game modes or difficulty levels yet; those are the game phase.
- Live notes still do not follow chords (the open I2 call from the Lick Lab review stands).
- No Orphoscope strip in Lick Lab. The user saw it and said it was too much.
- The hint never plays a note and never changes what is recorded or exported.

## Anti-goals

- A hint that is wrong at a section change or after a mutation (it would teach the user wrong notes and
  would make a later score unfair).
- Any audible change to a vibe that is not armed or boosted.
- A boost that leaks into a saved mix.

## Constraints

- Shipped-vibe sound stays bit-identical; the C++ suite proves it (no new draws on live state).
- The whole hint, including the zip, follows the clock the keys already use (`LickClock`), so it lands on
  the change; the bar-line poll lag noted in Fable's M7 applies.
- Public main rules: no real song, band or artist names.
- Desktop JVM AI edition is the target; the Lick Lab stays wide-layout only.

## Calls made after the builder check (Claude's, the user can overrule)

- **Exactness vs. live inputs.** The wrap roll reads live complexity and mood, which ramps can move between
  the peek and the change. While a track is armed in Lick Lab, the engine freezes the next chord at peek
  time and the real change plays that frozen chord, so the hint is exact. With nothing armed the chord path
  is untouched and bit-identical. Cost: while jamming, a wrap that a ramp would have changed in that last
  beat plays the peeked chord instead.
- **Late knowledge.** The hint shows only when the next chord is known at least a beat ahead; otherwise
  (a cut with no pre-roll) it shows nothing for that change.
- **Short chords.** The hint window is the last beat or the whole chord, whichever is shorter.
- **Tones.** The next chord lights the same tone set the current-chord highlight uses today (the triad on
  the chord's scale degree, `CHORD_STEPS`), so the hand-over is seamless.
- **Nearest.** Measured in semitones over the keys the strip shows, from the hand's MIDI note (chromatic
  notes included); a tie goes up.
- **BOOST** is a gain on the armed track's whole output, sends included, applied live and never written
  to mix state. Meters show it. Off on disarm; re-arming starts with it off.
- **Caller.** Arming the caller track clears the caller, as the code already does.

## Left to the builder

- How the engine exposes the next chord and the time left (new atomics or the viz ring), and how the
  Kotlin side reads them.
- Exact BOOST mechanism (a live gain port on the track is the likely shape), and the dB once the user hears it.
- Wave sizes in the column grid, and how the grid behaves on narrower wide-layout windows.
- What the hint shows when the slot has no chord (no progression): nothing.

## Rulings made during the build

1. The engine publishes the next chord from a dry run of its own chord advance (`VIZ_PULSAR_NEXT_CHORD`, and the beats
   left on `VIZ_PULSAR_CHORD_BEATS_LEFT`). It publishes -1 when the next chord can't be known: a pending jump or a wrap.
2. The engine only holds the chord roll while a track is armed, and only when the landing chord hangs on it (a
   one-chord progression). Unarmed playback is bit-identical to before.
3. The view model holds one change beat per chord change and re-anchors only on a jump over a quarter beat.
4. BOOST is +4 dB through `track_N_live_boost` (clamped 1..4, excluded from presets). The user later asked that it
   follow a switch to another track and survive a restart of the same vibe; it still drops on disarm, hide and a
   different vibe.
5. The zip, tuned by eye with the user: one lap on frame time over half the chord (at most 2 beats, at most 1 on
   short chords), eased, short and dim, riding the teal border. Small nudges to the change estimate never restart it.

## Known gaps

- The zip is smooth in renders; in motion the user rated it workable but "not as smooth as expected". The panel's
  frame rate during a lap has not been measured.
- Accuracy and style scores and difficulty levels (ROOT, NEAR and MOVE read like levels) are for the game phase.
