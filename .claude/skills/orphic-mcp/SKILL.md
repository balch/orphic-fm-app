---
name: orphic-mcp
description: Use when the user wants Claude to mix a vibe live in the running Orphic DJ app (AI edition), or mentions the orphic MCP server, mix_read, mix_set, mix_capture, or connecting Claude Code to the DJ app. Covers launching the app, connecting over MCP, the tools, the mixing workflow and troubleshooting.
---

# Orphic MCP

The AI edition of the desktop DJ app runs a loopback MCP server. It serves the same tools the in-app agent uses, so Claude Code can read and change the live mix while the user listens.

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
- `mix_set`: `field` is level_db (heard dB in the playing section, -30 to 3.5), pan, reverb, delay or macro. `scope` is vibe or section.
- `mix_mute`, `mix_solo`: toggle one track (0-7).
- `mix_reset`: levels, pans, sends and section layers back to the vibe. Keeps mutes, solos and whole-vibe macro moves, and a capture still writes those macros.
- `section_jump`, `section_loop`: move to a section at the next bar line, or hold the current one.
- `mix_capture`: save the mix edits as a capture file (see below).
- `ports_list`, `port_get`, `port_set`: raw engine control ports, keyed `uri:symbol`.
- `vibe_list`, `vibe_load`, `transport`: pick a vibe, and play, pause or read state.
- `pulsar_*`, `start_composition`: the vibe-authoring tools.

Whole-vibe macro moves change the real saved knob. Section macros do not.

## Mixing workflow

1. `transport` play, then `mix_read`. Note the vibe, the sections and the loudness.
2. `mix_set` one change at a time. Use `scope: section` to fix one section only.
3. Ask the user to listen. They judge the sound, not you.
4. `mix_read` again to confirm the numbers and the meters.
5. When the user likes it, `mix_capture`. It returns the rows and the file path.
6. Hand the file to the `apply-mix-capture` skill. It edits the vibe's Kotlin source.

## Gotchas

- `port_set` is never captured. Use `mix_set` for level, pan, sends and the four macros. A vibe load can overwrite port writes.
- Meters idle off after about 10 s. The first `mix_read` after that starts them and shows silence. Read again after about 3 s.
- The TRACKS view in the app shares this mix. Faders the user moves show up in `mix_read`.
- Capture on a catalog vibe writes a file only. The live mix stays as it is.
- Capture on an AI vibe also writes the rows into the vibe, archives it in `~/.config/orpheus-dj/ai-vibes/`, and restarts the song. Mutes, solos and the loop clear.
- Captures land in `~/.config/orpheus-dj/mix-captures/`. `stale` is true, false or null. Do not apply a stale or null one without asking the user.
- In an MCP-only session, the stale baseline for the playing vibe is its source as of the first connect (the first `initialize`, when the server builds its tools), not as of the app's launch. A source edit between launch and that connect does not show as stale.

## Troubleshooting

- 401: the token is missing or wrong. Re-read `mcp.json`.
- Connection refused: the app is not running, or it fell back to another port. Read `mcp.json` for the current one.
- 403 `Invalid Host`: the Host or Origin header is not localhost. Use `127.0.0.1` or `localhost`, not a LAN name or a proxy.
- `ok: false` in a tool result comes back as an MCP error. Read the message, since it names the bad argument.
- Log noise is normal. The SDK logs DEBUG lines per request.
