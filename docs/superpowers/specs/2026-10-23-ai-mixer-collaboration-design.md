# AI + Mixer collaboration (vibe composer piece 5)

Date: 2026-10-23. Status: landed on local main 2026-10-24 (desktop and Android; the iOS AI app does not build the AI graph yet). A dated snapshot; the code is the source of truth. Parent: `2026-10-22-vibe-composer-design.md`, piece 5 ("Collaboration loop").

## Intent

The user wants to mix a vibe *with* the agent: talk to it in a chat while both of them move the Mixer's controls in real time, each seeing what the other does. Today that does not work:

- AI and Mixer are separate sheets and only one opens at a time, so the agent says "open the Mixer tab, tweak, and tell me when you're back" and the user switches back and forth.
- The chat clears its feed on every send and wraps every message as `"Make a Pulsar vibe: …"`, so it is a one-shot vibe maker, not a conversation.
- Text is too small to read on the desktop and on the Galaxy Z Fold 8 inner screen. Both render the dock on a 1280dp canvas shrunk to fit (Fold 0.652x, desktop windows 896-1280dp wide down to 0.7x), and the AI panel uses 13sp and 11sp text, the Mixer down to 9sp.
- The agent cannot see what the user changed; the Changes column is only a diff against the file.

The desktop (primary) and the unfolded Fold in landscape show the same screen, so this is one large-screen design. The look the user asked for is a Claude Code style chat: turns scroll above, the input pinned at the bottom.

## Goals

1. **One AI entry point with a remembered Mixer toggle.** The dock's separate **Mixer** button is removed. The **AI** button opens the AI sheet. A toggle in the sheet's header brings the full Mixer up beside the chat (Mixer mode) or puts it away (chat-only mode). The AI button reopens whichever mode was used last, remembered across launches. The small **Mix** panel (BAND / TRACKS) is unchanged.
2. **A real chat.** The feed keeps every turn of the session (until **+ New**). Messages go to the agent as written; the agent decides whether a message asks for a new vibe, a tweak, or mixing. Layout top to bottom: header, ideas strip, conversation, input.
   - Your messages as bubbles; agent replies as rendered Markdown (bold, lists, line breaks; today `**` shows raw).
   - Tool steps as one-line rows with a short readable summary ("mix_set BASS −3.0 dB"), not just the tool name.
   - Your own mixer moves as one-line rows in a distinct colour ("you: PAD +2.0 dB"), so you can see what the agent will read.
   - Input pinned at the bottom, grows to a few lines; Enter sends, Shift+Enter adds a line on desktop.
   - The config strip (model, MCP toggle, cast mode, key) moves behind a settings button in the header. The song reference chip stays by the input.
3. **Ideas strip.** Two or three short idea cards above the conversation, each with a **Try** button that sends the idea's text as your next message. Collapsible. Refreshed after each agent turn, and after you pause for a few seconds having moved something in the Mixer. A refresh never posts in the conversation. In Mixer mode ideas may be about the mix; in chat-only mode they stay to things that need no Mixer (song, sections, voices, melody).
4. **The agent sees every move.** Every Mixer edit is tagged with who made it (user, agent, MCP). The user's moves since the agent's last turn are given to the agent at the start of its next turn, in order, coalesced per control (one drag is one entry: control, before, after). Covers levels, pans, sends, macros, mute, solo, section jump, loop, scope (whole vibe / section), and the TRACKS view's gain edits.
5. **The agent moves controls only while the Mixer is on screen.** Mix-changing tools (`mix_set`, `mix_mute`, `mix_solo`, `mix_reset`, `section_jump`, `section_loop`, macro changes) refuse when the Mixer is not visible. The agent then asks in the chat with an **Open Mixer** button; tapping it switches the sheet to Mixer mode. Reading tools (`mix_read`, `audio_analyze`, captures) and vibe tools (cast, apply) are not gated.
6. **You always win.**
   - Never lock the user out, and never ask the user to keep their hands off.
   - If the user touches a control during an agent turn, any agent change to that same control later in the turn is skipped; the tool result says why, and a one-line row in the chat says so ("you moved BASS while I was working, so I left it at your −4 dB").
   - Each strip gets a **pin**. A pinned strip is off-limits to the agent (all of its controls); the tool refuses with a reason, and the agent has to ask. Pins last until unpinned or another vibe loads.
   - When the agent changes a strip, the strip flashes, and strips the agent has touched this turn keep a faint glow until the turn ends.
7. **Legible everywhere.** Text sizes are chosen by how they read *after* the canvas shrink, not by their canvas sp. This covers the chat and the Mixer's labels and numbers alike. Targets, as the text should read at native density (1x): chat body about 15sp, secondary rows (tool steps, moves, idea text) about 13sp, and nothing in the Mixer below about 11sp. On the Fold that means chat body around 23sp on the canvas.
8. **Room for both.** In Mixer mode on the 1280dp canvas: chat column about 440dp on the right, Mixer on the left.
   - The Changes column (260dp) goes away. Capture and Reset to file move to the header, the whole vibe / section scope control moves next to the section chips, and the per-control diff stays reachable (e.g. from the Capture button).
   - STORM / STREET / SPEECH (meter only, 3 x 64dp) fold into one AMBIENT strip.
   - The eight track strips keep about 77dp each (72dp is today's minimum). They show fewer words to fit legible text (e.g. "−3.0" instead of "−3.0 dB", pan and sends as small knobs with a large readout while touched).
   - The chat column can collapse to a thin rail, giving the Mixer the full width.
9. **Ask before wiping the mix.** A re-apply or vibe load still clears every live mix edit and pin (today's behaviour, kept). When the user has live edits, the agent must warn and ask first. My call on the mechanism: not a prompt rule alone. `pulsar_apply_vibe`, `vibe_load` and a full `mix_reset` refuse while user edits are live unless the user has confirmed. The agent posts a card in the chat ("This clears your mix: N changes") with **Clear and apply** and **Keep my mix** buttons, and the confirmation covers that one call. A capture is still taken before a confirmed wipe.
10. **Prompt rules for collaboration.** The DJ agent prompt drops "open the Mixer tab, tweak, and tell me when you're back" and "when the user says they are back". It gains: you will be told the user's moves each turn; mix only while the Mixer is open, otherwise ask; respect pins and skipped controls; keep replies short while mixing.

## Non-goals

- The agent posting in the conversation on its own. Only the ideas strip refreshes unprompted.
- Locking controls while the agent works.
- The Orpheus synth app's AI UI (`ChatDialogContent` in `features/ai-orpheus`); it shares no UI with `DjAiPanel`.
- Showing MCP tool calls in the in-app chat feed. (MCP edits are tagged and logged so the in-app agent sees them; displaying them is left to the builder.)
- Voice input, new mix tools, changes to the C++ engine.

## Anti-goals (failure even if it works)

- Text that still reads tiny on the Fold or the desktop.
- A fader moving where the user cannot see it.
- The agent and the user fighting over a fader.
- A chat full of noise: a row per fader tick, or unprompted agent posts.
- Losing the user's mix edits to anything the agent does.

## Constraints from the code (2026-10-23)

- **One source of truth:** `MixBenchController` (AppScope, `features/pulsar/.../bench/`). The user (`AiMixerViewModel.edit`), the TRACKS view (`MixBenchTrackGainSink`), the agent tools (`MixTools.kt`) and MCP all call it, with no origin tag. The move log and the conflict rule need the tag there, or at each call site.
- A vibe load clears live edits (`clearForNewVibe`) and edits are refused while a load is in flight.
- **Sheets:** `AiTab` and `MixerTab` are both `opensAsSheet` routes and a single `activeSheet` (local `rememberSaveable` in `DjAppScreen`) chooses one. Nothing outside the screen can open a sheet today, so Open Mixer and the Mixer-visible gate need a small shared state (e.g. an AppScope "AI sheet mode / Mixer visible" holder) that the screen and the tools both read.
- **Agent:** `OrpheusAgent`, `OrpheusAgentConfig` (system prompt) and `TurnBudget` live in `features:ai`, shared with the Orpheus synth app. DJ-only rules must not change Orpheus behaviour. History already persists across turns within a Koog run; extra per-turn context (the move log) can ride on the prompt text or on `onAssistantMessage`'s return. Budget: 3 applies and 12 mix edits per turn; MCP calls are exempt through `TurnBudget.Exempt`.
- **Density:** the scale is folded into `LocalDensity.density` and not exposed; `fontScale` is deliberately untouched and must stay so (it would compound the user's accessibility setting). Legible sizing needs the scale exposed (e.g. a CompositionLocal set where the density is overridden on Android and desktop).
- The phone and portrait path uses `AdaptiveSheet` as a bottom sheet; the Mixer has a `CompactMixer` row list below 600dp wide.

## Decided by the user

- Layout A: ideas stacked above the conversation, in a chat column on the right of a full-size Mixer.
- Same screen on desktop and the open Fold.
- The agent sees every move; it does not reply to each one.
- After a pause in mixing, refresh the ideas only; never post in the conversation unprompted.
- The agent changes controls only with the Mixer open; otherwise it asks, with an Open Mixer button.
- Never lock the user out; skip agent changes to anything moved mid-turn; per-strip pins the agent cannot touch.
- Remove the Mixer dock button; the AI sheet toggles Mixer mode and remembers the last mode.
- Legibility covers the Mixer's labels and numbers too.
- A re-apply or vibe load still wipes live mix edits (and pins), but the agent must warn the user and ask before any re-apply that would clear their edits.

## My calls (the builder may revisit)

- Chat column about 440dp; Changes column removed; three meter-only strips merged into AMBIENT.
- Legibility targets above (15 / 13 / 11sp as read at 1x).
- Ideas come from a separate short model call (no tools, not part of the agent's history) given the vibe summary, a mix snapshot, recent moves and the last few chat lines; at most one refresh in flight, none during an agent turn, pause about 4s.
- Pins clear on vibe load, like live edits.
- MCP calls are exempt from the Mixer-visible gate (Claude Code may mix while the user looks elsewhere), the same way they are exempt from the budget.
- Portrait and phone: the same chat in the bottom sheet; the toggle stacks a `CompactMixer` above the chat rather than side by side.
- The remembered mode is stored in `AppPreferences`.
- **Which moves count:** Mixer and TRACKS view edits, plus macro knob moves made anywhere (Pulsar panel, MIDI, gestures), which `SynthController` already tags with `ControlEventOrigin`. The mid-turn skip covers macros too, and the agent's macro changes are gated like other mix changes.
- **Pins and MCP:** pins bind MCP too (you always win against Claude Code as well). The mid-turn skip has no turn to apply to over MCP, so it does not. MCP edits flash like the agent's.
- **What a pin protects:** direct writes to the strip (level, pan, sends, mute). `mix_reset` skips pinned strips. A solo of another track is allowed (it is a temporary listen, not an edit). Overwriting an unpinned user edit is allowed; that is what pins are for, so there is no turn undo.
- **Other tools:** `port_set` on mix-related ports (track levels, pans, sends, macros) is gated and pin-checked like `mix_set`; `transport` is not gated.
- **Cancel and sending mid-turn:** Cancel stops the running turn and keeps the conversation, like Esc in Claude Code (this needs `OrpheusAgent` to interrupt a turn without restarting the run; Orpheus behaviour must not change). A model switch starts a new conversation and the feed shows a divider. You can type and send during a turn, and Try works too: the message queues and goes when the turn ends. The chat lasts for the app session, not across relaunches.
  - *Changed 2026-10-24 (build):* Cancel restarts the agent run and the next turn opens with a short recap of the conversation so far, instead of interrupting a turn in place. Koog's run owns the history, and the recap plus the per-turn context carry what matters; a true interrupt is left for later. The final review judged this acceptable for now.

## Left to the builder

- How the origin tag is carried (a parameter on controller calls, an edit wrapper, or a coroutine-context element like `ToolProgress`).
- How the move log is formatted for the agent, and its size cap for long sessions.
- How Open Mixer is drawn (a tool such as `ask_open_mixer(reason)` that adds a card, or a structured reply), and the exact refusal text of the gate, pin and skip.
- The ideas call's model choice, prompt and output schema.
- The exact strip redesign within the legibility floors.
- Markdown rendering approach.
- Test shape: unit tests for log coalescing, the gate, pins and the mid-turn skip; render tests of Mixer mode and chat-only mode at the Fold canvas (1280 x 1153dp at 0.652) and a 1000dp desktop window.

## Done when

On the desktop and on the Fold inner screen, the user opens AI, toggles the Mixer on, and mixes a vibe the agent has just made in conversation with the agent:

- every word on screen reads comfortably at arm's length;
- the agent's replies refer to the moves the user made since their last message;
- the agent's changes appear live with a flash, and never land on a control the user just moved or pinned;
- ideas refresh after a pause without anything new appearing in the conversation;
- closing and reopening AI comes back in the last mode;
- with the Mixer off, asking for a mix change gets an Open Mixer button instead of an unseen change;
- asking for a song change after mixing gets a "this clears your mix" card before anything is wiped.

## Rulings made during the build

- Only Level, Pan, Send, Mute, Solo and macro knob moves count as the user's live edits for the wipe card. A section jump, the loop and the edit scope never ask.
- A user mix move refreshes the ideas only while the AI sheet is open (either mode); the turn-end refresh always runs.
- Reset to file is a full reset (user, 2026-10-24): levels, pans, sends, section edits, mutes, solos and the four macro knobs go back to the vibe, and the changes count goes to zero. Pinned strips keep their own. The knob writes a reset makes are its own, never logged as the user's.
- A confirmed `mix_reset` takes no capture first: on an AI vibe a capture would write the edits back and undo the reset.
- Saved AI vibes (added on the same branch) list at the bottom of the playlist sheet, the Pulsar VIBE menu and the top bar's Vibe picker, one row per name, newest first. They are pickable only and never join the rotation; delete moves the files into `ai-vibes/deleted/`.

## Known gaps

- iPad: the "Orphic DJ AI" iOS target boots the Core graph (`DjAppGraphIos` in `:apps:djapp:shared`), so it has no AI tab, Mixer agent or saved vibes. The saved-vibe folder is `jvmAndroidMain` only.
- The TRACKS view's reset still clears only levels, mutes and solos, ignores pins, and clears all of `userChanged`.
- `userChanged` is a touched set, not a diff: a control moved and moved back still asks before a wipe.
- Pin icon is about 6.5dp physical on the Fold inner screen.
- The 48dp rail and the 440dp chat column are not scaled for phone landscape; the Mixer is squeezed there.
- An in-flight ideas call is not cancelled when a turn starts.
