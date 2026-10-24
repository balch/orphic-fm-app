# Running Out: ear test

Running Out is a WIP vibe: the lead licks are placeholders and the bass lines are drafts. The user runs this test; nothing here has been verified by listening.

Checks come in three kinds:
- **Verify:** should already work, so a miss is a bug.
- **Judge:** a call only the listener can make.
- **Expect:** known behaviour, flagged so it isn't mistaken for a fault.

## Before you play

Run these from the branch's worktree. Build the native library first, otherwise the desktop app loads the previous build.

```bash
./gradlew :apps:djapp:desktopApp:buildDesktopNative
nohup ./gradlew :apps:djapp:desktopApp:run -Pcatalog=wip > build/djapp-run.log 2>&1 < /dev/null & disown
```

Pick Running Out from the WIP catalog (Club / ambient / time group, after Deep Space). It plays once, about 7:15 (section lengths are drawn per run, so this varies), at 64 bpm in C# dorian. The headless measure puts the whole run at -15.8 LUFS.

Track map: 0 kick, 1 snare / splash, 2 clock, 3 bass, 4 lead / calling whale, 5 organ, 6 ping, 7 answering whale.

## The clock (all sections)
Track 2 ticks on pinned steps in every section: quarters, then eighths in the pound, quarters as it sinks, halves under the whales, quarters back up, eighths, eighths with a 16th pickup at each bar's end in the last jam, one tick a bar at the end.
- Verify: the tick never stops until the very end.
- Judge: it reads as time running out, not as a hi-hat groove.
- Judge: it is loud enough to follow under the pound (measured -18.6 LU against the mix, -13.9 in the opening pings).

## pings (~30 s)
Only the ping (track 6, MOD, one note: B5) and the clock.
- Verify: one repeated note, wet, with long delay tails.
- Judge: dark enough. Not too bright was the brief.
- Expect: quiet, about -24 LUFS. The arc starts in the dark on purpose.

## organ swell (~30 s)
The organ bed (C#4-C#5, an octave above the bass) comes in and ramps up with energy; kick on 1, the bass enters under it.
- Verify: the organ chords swell up to a loud ringing peak by the section's end.
- Judge: slow enough at the start.

## slow pound (~45 s)
Half-time kit pinned by `hits` (kick on 1 and the "and" of 2, snare on 3). Lead plays slot 0. Bass plays the build line.
- Verify: the beat pounds slowly, with space between hits.
- Judge: the bass build line climbs two bars to B2 and walks back down two bars. Does it carry the build-up / build-down feel?
- Expect: slot 0's lead lick is a placeholder (`TODO(lick)`).

## bass wander (~45 s)
A Jam. The bass swaps to the sparse skeleton of the build line and the Jam fills its rests.
- Verify: the bass noodles around the arch rather than repeating the line.
- Verify: the bass holds the solo for the whole section, every run. The lead is always-active, so it can't take the jam.

## sinking (~30 s)
Energy and kick fall away, a low rumble enters, the clock slows to quarters.
- Judge: it feels like going under.

## lone whale (~45 s)
No kick, bass or answering whale. Track 4 switches to its SPACE voice (VOWEL_FOF moan, slot 1). The snare becomes a long splash wash over light rain.
- Verify: one whale only. Track 7 is silent.
- Verify: the calling whale slides slowly between notes.
- Judge: the splashes read as water, not as a snare.

## pod (~45 s)
Track 7 (SPK "oo" with a grain cloud) answers the call in the gaps (call on beats 1-4 and 9-12, answer on 5-8 and 13-16). The bass doubles the answer two octaves down.
- Verify: call and response alternate, not on top of each other.
- Judge: the grain cloud makes it sound like many whales.
- Judge: listen for low rumble under the answering whale. The measure flags 66% of track 7's energy below 40 Hz.

## surfacing (~30 s)
A kick on each downbeat returns, the clock rewinds to quarters, slot 3 lick.
- Judge: the climb back up feels earned.

## pound returns (~60 s)
The slow pound again with slot 3 (a development of slot 0).
- Expect: slot 3 is a placeholder (`TODO(lick)`).

## deep jam (~45 s)
The second Jam, clock racing.
- Judge: the racing clock lands as urgency, not clutter.

## last pings (~30 s, then silence)
Back to the ping, organ low, one tick a bar, fading to zero.
- Verify: it ends in silence and does not restart (played once).

## What Pulsar can't reproduce
- The ping is a synth bell, not a piano through a rotating speaker.
- The whales are a formant voice and a speech engine with slides. There is no slide guitar through a wah or reversed pedal.
- The delay is the clean Pulsar delay, with no tape wobble or saturation.
- The clock is a pinned hi-hat tick, not a sampled clock.
- The splashes are a long-decay snare wash plus weather rain, not recorded water.
- No wind.
- The lead licks are placeholders, and the two bass lines are drafts for the user to rewrite.
