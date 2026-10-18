# Record panel eye and ear test

Branch `claude/priceless-hugle-651b9e`. Run the desktop app from this worktree:
`./gradlew :apps:orpheus:desktopApp:run`. If REC is missing from the header, re-pick a factory
panel set (custom sets saved before this branch don't list new panels).

## Verify (should already work; a failure is a bug)

1. REC panel shows in the header and expands to a big record button on the left and TAKES on the right.
2. Press record: the timer counts and the level meter moves. The take being written does not appear in the list.
3. Press stop: the new take appears at the top, selected. `~/.config/orpheus/recordings/<take-id>/` holds `audio.wav`, `preset.json`, `telemetry.json`.
4. The listen bar plays the take, pause works, and clicking the progress line seeks. `afplay audio.wav` also plays it.
5. Presets panel: `rec-<take-id>` reloads the same sound.
6. Sweep a knob (quad pitch) during a take: `telemetry.json` shows `control` events for it, and `gateOn` events with pitches.
7. Delete asks "delete? yes / no". No keeps the folder; yes removes it.
8. Start recording while a take is playing: playback stops.
9. Record, stop, record again within one second: the second take gets a `-2` suffix instead of failing.
10. Type in the Speech panel at the moment you press stop: the take is kept (end snapshot may fall back to the start one).
11. Load a preset mid-take: the take is kept; `start` and `end` snapshots differ.
12. Tell Claude "grab my last take": the `orpheus-recording-to-vibe` skill explains the take in plain words and proposes an `OrpheusEngine(...)` block without editing a vibe.

## Judge (your call)

13. The panel layout reads like mockup A and fits next to the other panels.
14. The recorded WAV sounds like what you heard: no clicks, dropouts or level jumps.
15. Claude's explanation of the take matches what you played.

## Expect (known behavior, not a fault)

16. If the audio engine is not running, the timer stays at 00:00.0 (no frames arrive).
17. The panel exists only in the desktop app.
18. The recording is the full output, Pulsar and effects included.
19. Marbles-clocked or pattern-driven voices have an incomplete note timeline; the skill warns about it.
