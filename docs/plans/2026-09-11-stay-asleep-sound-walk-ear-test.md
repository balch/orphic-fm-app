# Stay Asleep sound walk: ear test

Branch `claude/bubblegum-vibe-sounds-274623`, not pushed. The user runs this test; nothing here has been verified by listening.

Checks come in three kinds:
- **Verify:** should already work, so a miss is a bug.
- **Judge:** a call only the listener can make.
- **Expect:** known behaviour, flagged so it isn't mistaken for a fault.

## Before you play

Run these from the branch's worktree. Build the native library first, otherwise the desktop app loads the previous build.

```bash
./gradlew :apps:djapp:desktopApp:buildDesktopNative
./gradlew :apps:djapp:desktopApp:run -Pcatalog=wip 2>&1 | tee build/djapp-run.log
```

Pick Stay Asleep from the WIP catalog. To confirm the lines were generated, run `grep -a "Speech:" build/djapp-run.log`.

## wakeup
Rain .15, rainLevel .45. Track 6 is out.
- Verify: light rain is audible from the first bars.
- Verify: track 6 is silent, with no siren yet.
- Judge: the rain sits under the intro without masking it.

## street
Footsteps .50 WALK, echo .10. Track 6 is out.
- Verify: footsteps walk on the beat, one per beat (every 4th step boundary).
- Verify: the rain fades out over wakeup's last loop, so it is gone as street starts, with no click.
- Verify: track 6 stays silent.
- Judge: the footsteps sit clear of the kick and the bass.

## alley
Rain .25, rainLevel .50. Footsteps .55 WALK, echo .80. Track 6 is out.
- Verify: the rain fades back in over street's last loop, so it is there as alley starts.
- Verify: the footsteps echo, clearly wetter than in street.
- Verify: track 6 stays silent.
- Judge: the echo stays out of the lick's way.

## chase
80 BPM; one loop is 8 beats. Footsteps .60 RUN, echo .20. Siren: morph 1.0, volume .45, reverb .35. Phrase 0 plays on even loops and phrase 1 on odd loops.
- Verify: the siren wails and sits high (track 6 note range 72-84, C5 to C6).
- Verify: footsteps run, two per beat (every 2nd step boundary, unswung).
- Verify: the lines alternate. "I'm here to chew bubblegum, and.." plays on one loop, then "I'm all out of bubblegum" on the next.
- Judge: each line ends as the next downbeat kick hits, every time.
- Judge: the punchline sits in the gap after the figure.
- Judge: the voice level is right in the room.
- Expect: the setup starts around beat 5.2 and brushes the last 0.3 beat of track 4's reply, by design.
- Expect: a line can end about 20 ms off the kick. Just after the tempo drift picks a new target, it can be up to about 70 ms off. The start is fixed when the loop is planned.
- Expect: during a solo, a punchline can land without its forced kick.

## escape
Train .70. Track 6 is out.
- Verify: the train fades in over chase's last loop, arriving close and slow.
- Verify: a whistle blows on entry.
- Verify: the chuffs speed up, darken and fade by the section's end (1.5 to 6 chuffs/s, low-pass 6000 to 1200 Hz).
- Verify: the running footsteps fade out over chase's last loop, with no clicks.
- Verify: the siren is gone.
- Judge: the train's level and length feel right.

## home
Rain .15, rainLevel .40. Siren: morph .80, volume .12, reverb .80.
- Verify: light rain fades back in over escape's last loop.
- Verify: a quiet, far-off siren sits in the distance.
- Verify: the train has faded out cleanly.
- Judge: the far siren reads as distance, not as a mistake.

## Song-wide
Turn the Complexity knob while the song plays. The lick has `complexityVariation` 0 to 1 and `carryGrowth = true`.
- Verify: at Complexity 0, the figure plays exactly as written.
- Verify: raising Complexity adds notes over a few loops.
- Verify: the added notes stay in key with the figure, with no stray C.
- Verify: the added notes survive the change into the next section.
- Verify: the added notes clear on their own after a couple of minutes (the deja-vu reset).
- Verify: the log shows `Speech: <vibe name> loaded 2 phrase clips`.
- Verify: when the master tape-stops, rain, siren, footsteps and any voice stop with it.
- Verify: every bed crossfades over the last loop of the section it is leaving, with no clicks.
- Judge: the grown notes sound like the lick, not like noise on top of it.
- Expect: the crossfade lands over the last loop of the section being left, not at the boundary itself. All six edges now use `transitionBars = 2`, which also stretches each handoff by that loop. At 1 there is no pre-roll at all.
- Expect: track 7's street bed never fires in wakeup, street, alley or chase at default knobs. This predates this work.
- Expect: switching vibes in the middle of a line can click, because a reload stops the voice without a fade.
- Expect: the lines are desktop only. Android, iOS and web play the song without them.

## Regression
Every new feature is off unless a vibe asks for it.
- Verify: DogHouse sounds exactly as before.
- Verify: Fire Sky .5f sounds exactly as before.

## Knobs (EAR-TUNE)

### `StayAsleepVibe.kt`, section scaffold

| Section | weather | street | track 6 siren | speech |
|---|---|---|---|---|
| wakeup | rain .15, rainLevel .45 | | out | |
| street | | footsteps .50 WALK, echo .10 | out | |
| alley | rain .25, rainLevel .50 | footsteps .55 WALK, echo .80 | out | |
| chase | | footsteps .60 RUN, echo .20 | morph 1.0, volume .45, reverb .35 | phrase 0 every 2 loops (phase 0), phrase 1 every 2 loops (phase 1), both end on the loop end |
| escape | | train .70 | out | |
| home | rain .15, rainLevel .40 | | morph .80, volume .12, reverb .80 | |

### `liborpheus_dsp/src/orpheus_unit_pulsar.cpp`, sends

Footstep sends are scaled by the section's echo.

| Constant | Value |
|---|---|
| kStreetFootReverbSend | 0.60 |
| kStreetFootDelaySend | 0.25 |
| kStreetTrainReverbSend | 0.35 |
| kStreetTrainDelaySend | 0.10 |
| kSpeechReverbSend | 0.25 |
| kSpeechDelaySend | 0.15 |

### `liborpheus_dsp/src/pulsar_street.h`, footsteps

| Constant | Value | Meaning |
|---|---|---|
| kStepThudStartHz | 140 | Hz, thud pitch at the hit |
| kStepThudEndHz | 85 | Hz, thud pitch it falls to |
| kStepThudSeconds | 0.045 | s, thud decay |
| kStepThudGain | 0.55 | thud level |
| kStepScuffHz | 1200 | Hz, scuff band |
| kStepScuffQ | 0.8 | scuff band Q |
| kStepScuffSeconds | 0.025 | s, scuff decay |
| kStepScuffGain | 0.35 | scuff level |
| kStepPan | 0.20 | left/right alternation |
| kStepLevelJitter | 0.15 | per-step level variation |
| kStepToneJitter | 0.05 | per-step pitch variation |
| kRunBrightness | 1.6 | RUN scuff brightness multiplier |
| kRunLength | 0.7 | RUN envelope length multiplier |
| kRunGain | 1.25 | RUN level multiplier |

### `liborpheus_dsp/src/pulsar_street.h`, steam train

| Constant | Value | Meaning |
|---|---|---|
| kChuffRateStart / kChuffRateEnd | 1.5 / 6.0 | chuffs/s at entry / at the end |
| kChuffSeconds | 0.12 | s, one chuff's decay |
| kChuffHzStart / kChuffHzEnd | 900 / 500 | Hz, chuff band |
| kChuffQ | 1.2 | chuff band Q |
| kChuffAccent / kChuffPlain | 1.0 / 0.6 | accented / plain chuff level |
| kChuffGain | 0.5 | chuff layer level |
| kTrainLpStartHz / kTrainLpEndHz | 6000 / 1200 | Hz, low-pass |
| kWhistleHzA / kWhistleHzB | 550 / 660 | Hz, whistle tones |
| kWhistleSeconds | 1.2 | s, whistle length |
| kWhistleAttackSeconds / kWhistleReleaseSeconds | 0.06 / 0.30 | s |
| kWhistleGain | 0.18 | whistle level |
