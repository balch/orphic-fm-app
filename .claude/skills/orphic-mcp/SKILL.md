---
name: orphic-mcp
description: Use when the user wants Claude to mix a vibe live in the running Orphic DJ app (AI edition), or mentions the orphic MCP server, mix_read, mix_set, mix_capture, audio_analyze, audio_capture, stems, or connecting Claude Code to the DJ app, or wants to measure or record what the mix sounds like, on the desktop app or on an Android phone (adb forward), or wants Claude to make a new vibe in the running app (pulsar_vibe_guide, pulsar_cast_vibe, pulsar_apply_vibe). Covers launching the app, connecting over MCP, the tools, making a vibe, the mixing workflow, raw audio and troubleshooting.
---

# Orphic MCP

The AI edition of the desktop DJ app runs a loopback MCP server. It serves the same tools the in-app agent uses, so Claude Code can read and change the live mix while the user listens.

Every vibe workflow starts with `pulsar_vibe_guide` (the shared composition guide, the same text the in-app agent reads) and, for a new vibe, `pulsar_cast_vibe`. See "Making a vibe".

## Launch

```
nohup ./gradlew :apps:djapp:desktopApp:run -Pedition=ai < /dev/null > /tmp/orphic.log 2>&1 &
disown
```

- Detach it as shown, or the app dies with the shell.
- Add `-Pbench=true` for the Mix Bench web page. It is optional.
- Only the AI edition has the server. The core edition does not.
- Wait for `Orphic MCP: http://127.0.0.1:<port>/mcp` in the log.

## Connect

- The app writes `~/.config/orpheus-dj/mcp.json` (owner-only) with `{url, token, port}`. The token persists across launches. The port is 47801, or the next free one up to 47810. Two running apps share the file, and the newest wins.
- Register once (the user runs this, since it edits their Claude config):
  `claude mcp add --transport http orphic <url> --header "Authorization: Bearer <token>"`
- The URL changes only if the port falls back. Re-run the command then.
- Raw HTTP: `POST <url>` with `Authorization: Bearer <token>`, `Content-Type: application/json` and `Accept: application/json, text/event-stream`. `initialize` returns an `Mcp-Session-Id` header. Send it on later calls, after `notifications/initialized`.

## Tools

- `mix_read`: vibe, playing section, per-track level/pan/sends/mute/solo, macros, and loudness meters.
- `mix_set`: `field` is level_db (heard dB in the playing section, -30 to 3.5), pan, reverb, delay, macro, or the Pulsar delay's own settings delay_time_a, delay_time_b (0-1) and delay_feedback (0-0.95, no track). `scope` is vibe or section; a section delay edit captures to that section's `delay`.
- `mix_mute`, `mix_solo`: toggle one track (0-7).
- `mix_reset`: the whole mix back to the vibe as written: levels, pans, sends, section layers, mutes, solos and the four macro knobs. Pinned strips keep their edits, mutes and solos.
- `section_jump`, `section_loop`: move to a section at the next bar line, or hold the current one.
- `mix_capture`: save the mix edits as a capture file (see below).
- `ports_list`, `port_get`, `port_set`: raw engine control ports, keyed `uri:symbol`.
- `vibe_list`, `vibe_load`, `transport`: pick a vibe, and play, pause or read state.
- `pulsar_vibe_guide`: the composition guide, plus a catalog of shipped vibes for ideas only.
- `pulsar_cast_vibe`: rolls an 8-track skeleton. Args `mode` (BED_FIXED or ALL_ROLLED; omit for the user's saved mode), `seed` (omit for a random roll), `styleTags` (rock, electro, ambient, reggae, psych, funk) and `pins` (`{track, role, engine}`). Returns `seed`, `summary` (one line per track) and `vibeJson`.
- `pulsar_apply_vibe`, `pulsar_get_vibe`, `pulsar_vibe_schema`, the other `pulsar_*` tools, `start_composition`: the rest of the vibe-authoring tools.
- `agent_errors`: the in-app agent's last 100 failures, newest last: AGENT (LLM/API error or a crash in the run), TOOL (a tool call that threw or had bad arguments) and VIBE (`pulsar_apply_vibe` rejections). `clear: true` empties it after reading. MCP only; the in-app agent never sees it.
- `audio_analyze`: record the next 0.5-15 s (default 5) and measure each stem: level, third-octave spectrum, envelope, onsets, low end and stereo. `stems` and `kinds` narrow it.
- `audio_capture`: record the next 0.5-30 s (default 10) to one WAV per stem plus `stems.json`, for offline analysis. `stems` narrows it.
- The 13 stems:
  - `track0`-`track7`: the 8 Pulsar tracks, post fader and pan, before the bus. They are dry, taken before the delay and reverb sends: those returns appear only in `bus` and `out`, so the tracks summed do not match the bus.
  - `storm`, `street`, `speech`: Pulsar's own sources.
  - `bus`: Pulsar's bus after the 35 Hz high-pass and drive, before the limiter.
  - `out`: the app's output before any TTS announcement.

Whole-vibe macro moves change the real saved knob. Section macros do not.

## Android (ai flavor)

The `aiDebug` build of Orphic DJ on a phone or emulator runs the same server. The host reaches it through `adb forward`. Never run adb without `-s <serial>`, since a phone may sit next to an emulator. The helper `scripts/android.sh` wraps the steps.

- Only `aiDebug` works with `run-as`, which needs a debuggable build. `aiDebugRelease` shares the package id but is not debuggable: check with `adb -s <serial> shell dumpsys package <pkg> | grep pkgFlags`.
- Packages: `org.balch.djapp.ai.debug` (aiDebug) and `org.balch.djapp.ai` (release). Release builds have no `run-as`, so copy the token from the AI tab (the `MCP` chip shows the url and a copy-token button).
- The chip copies the token marked sensitive, so the Android 13+ clipboard preview hides it. Release ai builds were verified working under R8.
- Build: `./gradlew :apps:djapp:androidApp:assembleAiDebug`. The apk lands in `apps/djapp/androidApp/build/outputs/apk/ai/debug/` (`*.apk`).
- Install: `adb -s <serial> install -r <apk>`. Never use a Gradle install task, it may hit the wrong device.
- Launch the app. The server starts with the app process, so the AI tab need not be open.
- Connect: `.claude/skills/orphic-mcp/scripts/android.sh --serial <serial> connect`. It reads `files/mcp.json` under `run-as`, forwards host port 47821 to the device port, and prints the host url and the token.
- Host port is 47821 so the desktop app keeps 47801. Pass `--host-port` for another.
- The url inside the device `mcp.json` is the device url. The host can only reach it through the forward, so use `http://127.0.0.1:47821/mcp`.
- The device port comes from `mcp.json`: 47801, or 47802 when two ai builds run at once. Read it before forwarding. `connect` does this for you; pass `--package` to pick a build.
- Register once (the user runs this, never Claude):
  `claude mcp add --transport http orphic-phone http://127.0.0.1:47821/mcp --header "Authorization: Bearer <token>"`
- `adb forward` dies with the adb server or a replug. Re-run `connect`. The token persists, so the registration stays valid.
- Android freezes a backgrounded app that is paused (the cached-app freezer). MCP requests then hang until the client times out.
- A backgrounded app that is still playing keeps answering.
- So keep the app in the foreground, or playing, while Claude drives it.
- Other commands: `token` (print `mcp.json`), `list` (capture folders on the device), `pull <dest>` (both folders to `<dest>/mix-captures` and `<dest>/stem-captures`), `clean-stems`.
- Captures are app files on the phone, in `files/mix-captures/` and `files/stem-captures/<vibe>-<timestamp>/` (`<stem>.wav` plus `stems.json`). The user cannot see them, so `pull` them to read the WAVs or hand a mix capture to `apply-mix-capture`.
- Stem captures take about 5 MB per second. Run `clean-stems` when done.
- The script uses `adb exec-out`, not `shell`, because `shell` rewrites bytes and would corrupt WAVs.
- A 401 reply names where the token file lives.

## Making a vibe

The same loop the in-app agent follows (its "Creating Pulsar vibes" prompt in `OrpheusAgentConfig.kt`), driven over MCP:

1. **New vibe:** `pulsar_vibe_guide`, then `pulsar_cast_vibe`. Pass `styleTags` from the request and `pins` for anything the user named (a part or an engine on a track). Never copy a shipped vibe: `pulsar_get_vibe` with a shipped name is for borrowing an idea only.
2. **Tweak of the vibe playing now** ("slower", "swap the lead to a bell"): `pulsar_get_vibe` with `'current'`, change only what was asked, and go to step 4. Do not cast.
3. **Compose on the skeleton** per the guide: the frame (`name`, `bpm`, `rootNote`, `scaleType`), the groove, the progression (`genre.customProgression`), the melodies, the arrangement's sections and the effects. Keep the guide's section B rules and the skeleton's `cast` block (it records the roll, so the archived vibe can be cast again). Invent an original name (no real artist, band, song or album).
4. **`pulsar_apply_vibe`** with the complete JSON. It applies and plays at once. On `success: false`, fix the JSON per the message and apply again.
5. **One or two listening passes, no more:** `transport` play, `mix_read` once to start the meters, `audio_analyze` (it records for the seconds you ask, which is the wait), then `mix_read` again for the levels, then small fixes with `mix_set`. A re-apply clears the live mix edits, so call `mix_capture` before any re-apply (for example a `levelDb` change). The guide's section 11 has the targets and the pass in full.
6. **Hand over:** tell the user the vibe's name, the seed and the cast summary (so a vibe they like can be remade), and a sentence on its sound. The user may be mixing at the same time, so see the next section for the rules your edits follow. `mix_capture` keeps the mix, archives the vibe in `~/.config/orpheus-dj/ai-vibes/` and restarts the song.

The in-app agent is capped per turn (3 applies and 12 mix edits; `TurnBudget`). MCP calls are exempt, since no agent turn ever resets them, so the cap is yours to keep: stick to one or two passes and let the user judge.

To turn the result into a shipped `*Vibe.kt`, hand the archived JSON to `.claude/skills/grab-ai-vibes/scripts/import_vibe.sh` and follow the `vibe-creator` skill.

## Mixing together

The user can mix in the app while you drive it, and the in-app agent sees their moves each turn. Over MCP you do not get that turn context, so call `mix_read` to see where the controls are now.

- **Pins:** the user can pin a strip in the Mixer. Edits to a pinned strip are refused with `PINNED`, over MCP too, and the message says the user pinned it. Do not retry: ask the user. `mix_reset` skips pinned strips. Pins last until the user unpins or another vibe loads.
- **Moves made mid-turn:** the in-app agent skips a control the user just moved (`USER_MOVED`). MCP has no turn, so this does not apply: your edit lands and overwrites the user's value. Read before you set, and leave a control alone if the user is on it.
- **The Mixer need not be open** for MCP edits (`MIXER_CLOSED` is for the in-app agent), but the user sees nothing move unless it is. Say what you changed.
- **Wipes:** a re-apply or `vibe_load` clears the live mix edits and pins, and MCP is not asked to confirm. Call `mix_capture` first when the user has moved things, and say so before you load.
- Your edits flash on the strips like the in-app agent's, and the in-app agent sees them marked (Claude Code).

## Mixing workflow

1. `transport` play, then `mix_read`. Note the vibe, the sections and the loudness.
2. `mix_set` one change at a time. Use `scope: section` to fix one section only.
3. Ask the user to listen. They judge the sound, not you.
4. `mix_read` again to confirm the numbers and the meters. To check the sound itself, `audio_analyze` for numbers, `audio_capture` plus Python for anything deeper (`soundfile` or `scipy.io.wavfile` read 32-bit float).
5. When the user likes it, `mix_capture`. It returns the rows and the file path.
6. Hand the file to the `apply-mix-capture` skill. It edits the vibe's Kotlin source.

## Gotchas

- `port_set` is never captured. Use `mix_set` for level, pan, sends, the four macros and the delay's time and feedback (port_set refuses those delay ports). A vibe load can overwrite port writes.
- Meters idle off after about 10 s. The first `mix_read` after that starts them and shows silence. Do your other checks (`audio_analyze` takes its N seconds), then read again.
- The TRACKS view in the app shares this mix. Faders the user moves show up in `mix_read`.
- Capture on a catalog vibe writes a file only. The live mix stays as it is.
- Capture on an AI vibe also writes the rows into the vibe, archives it in `~/.config/orpheus-dj/ai-vibes/`, and restarts the song. Mutes, solos and the loop clear.
- Captures land in `~/.config/orpheus-dj/mix-captures/` (on the phone, `files/mix-captures/`; use `android.sh pull`). `stale` is true, false or null. Do not apply a stale or null one without asking the user.
- Audio tools need Pulsar playing. Stopped, they refuse.
- One recording at a time, across MCP and the in-app agent. A second is refused at once, never queued.
- An audio call blocks for its N seconds. It sends MCP progress while it records, for clients that show it.
- Stems record only Pulsar. `out` is the whole graph, pre-TTS.
- A vibe load or a transport stop during a recording aborts it with `ok: false`. A capture then keeps no files.
- Stem captures live in `~/.config/orpheus-dj/stem-captures/<vibe>-<timestamp>/`: `<stem>.wav` (stereo float32) and `stems.json` (vibe, section, sample rate, frames, per-stem levels).
- Stem captures take about 5 MB per second of audio with all 13 stems (about 150 MB for 30 s), and nothing deletes them. Remove the folders under `~/.config/orpheus-dj/stem-captures/` when you are done with them.
- Dropped frames are cut from the WAVs. With `droppedFrames > 0` the files are shorter than `seconds` and jump at each drop.
- `bandsDb` in `audio_analyze` is each band's absolute power in dBFS (a full-scale sine reads -3 in its band), not a share of the stem.
- In an MCP-only session, the stale baseline for the playing vibe is its source as of the first connect (the first `initialize`, when the server builds its tools), not as of the app's launch. A source edit between launch and that connect does not show as stale.

## Troubleshooting

- 401: the token is missing or wrong. Re-read `mcp.json`.
- Connection refused: the app is not running, or it fell back to another port. Read `mcp.json` for the current one.
- 403 `Invalid Host`: the Host or Origin header is not localhost. Use `127.0.0.1` or `localhost`, not a LAN name or a proxy.
- `ok: false` in a tool result comes back as an MCP error. Read the message, since it names the bad argument.
- A long audio call times out on the client side: raise `MCP_TOOL_TIMEOUT` (milliseconds) in Claude Code's env. The server's own idle timeout is 120 s, above the 30 s capture cap.
- On the phone, connection refused usually means the forward is gone (adb restarted or the cable was replugged). Re-run `android.sh connect`.
- Log noise is normal. The SDK logs DEBUG lines per request.
