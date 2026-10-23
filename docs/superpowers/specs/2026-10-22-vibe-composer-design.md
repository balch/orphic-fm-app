# Vibe composer: distinct AI vibes, a wordless vocal lead, and mixing with the user

> **Status:** piece 1 (shared guide, casting, tool and prompt reshaping) built on branch claude/orphic-mcp-audio,
> 2026-10-23; piece 4 (double voice) built on claude/nifty-wescoff-953423; pieces 2, 3 and 5 not built here.
> This is a dated snapshot of the design and its decisions; the code is the source of truth. Later changes
> add a "Superseded by" line rather than editing the text.

Date: 2026-10-22. Builds on Orphic MCP phases 1-5 (spec `2026-10-21-orphic-mcp-design.md`) and phase 4's mixing guide (branch `claude/orphic-mcp-audio`).

## Intent

AI-made vibes all sound alike, and the mix has always been broken. The user wants the in-app agent (and Claude Code over MCP, through the same tools) to make vibes that are each their own thing, can carry a story through a wordless sung melody, and end with a real mix made together with the user. The north star is "the ultimate jam band", moving across rock, electro and ambient, with randomness welcome.

The test case: "Please create a vibe inspired by [a tab of The Toyes' original 'Smoke Two Joints']. I want it to sound like the original but have its own distinct uniqueness: recognisable, not a total copy. Make it stand out by capturing the lyric melody closely with some kind of funky lead that does not sound like voices."

## Why the vibes sound the same (measured, 2026-10-22)

From the 19 readable AI vibes in `~/.config/orpheus-dj/ai-vibes/` (they pre-date the newest guide sections, so they show the old agent):

- **Template copying.** 13 of 19 best-match two shipped vibes (CompLab 7, SixtiesRebel 6); mean best-template match 58%, up to 92%. Tracks 0-3 are BD, SD, HH and a melodic bass in 18-19 of 19. The read tool literally instructs "Never write a vibe from scratch, edit a template", and the agent prompt says build in a single turn and stop after one apply.
- **One drone recipe.** CHD in 16/19 and ENS in 14/19; 14 of 29 pad rows leave harmonics/timbre/morph at exactly 0.5; the EDM and SPACE voices are the same engine on 114 of 152 tracks, so the energy crossfade changes nothing; envelopeType AD in 17/19. Hand-made vibes use STR (14/30), GRN (8/30) and BLEND envelopes (18/30) far more.
- **Untouched expressive controls.** The agent writes about 8 engine fields. It never sets vibrato, pitch/morph/harmonics rises, grain fields, mod LFO, hold or LPG, because the guide never explains them.
- **Generic structure.** MAJOR 11/19 (shipped hand-made: 3/30), POP progression 13/19, innerBars 8 in 19/19, halfLick off 19/19, anomalies in 1/19.

## Goals

1. **No more copying a vibe.** The agent starts from a blank 8-track skeleton and casts every track deliberately. Shipped vibes become a reference catalog to borrow ideas from (e.g. "how does Orphic Lore build its singer?"), never a starting point to edit.
2. **Dice-rolled casting plus music theory.** Where the user's request leaves a choice open, the agent rolls for it from an expanded voice palette, including engines no vibe uses today (VOWEL_FOF, TRIPLE_SINE/TRIANGLE/SQUARE, TOY, QUESTION_MARK, MST) and the under-used STR, GRN, ADD, WTB and TRN. Music-theory rules keep the result sounding good:
   - each voice keeps its own register; the vocal lead sits above the drones and clear of the bass;
   - drones and pads stay consonant with the progression;
   - the EDM (high energy) and SPACE (low energy) voices of a track are paired on purpose, so turning energy changes the colour;
   - weird voices are welcome where they work with energy.
   The roll is stated, not hidden, so a liked vibe can be remade.
3. **Standard drone beds with singing melody lines on top.** Drones are a known, solid foundation; the variety comes from the voices and melodies laid over them.
4. **An optional wordless vocal lead that tells a story.** Not every vibe has one; when it does:
   - it sings melody lines the way lyrics do: phrases with breaths, a verse melody, a chorus hook that returns, maybe a bridge; no strict rules;
   - a different melody per section is possible (the engine already pins a melody pool slot per section with `Section.lickIndex`, used by only 3 vibes and unknown to the agent);
   - any voice can sing: the speech singer (Orphic Lore's Sirens recipe: harmonics picks the speech model, timbre the formant, morph the vowel), a vowel voice, or a funky non-vocal lead (the Toyes test asks for this one).
5. **The guide teaches what lyric, lead, riff and jam melodies are, and how to make each.** The agent learns three kinds of melody line, how to build each one as `Lick` data, and how to run jams:
   - **Lyric line:** sings like words. One phrase per lyric line (or per cue line from a song reference), with syllable-like rhythm, a breath (rest) at the end of each line, a contour that rises and falls with the sentence, call and answer between lines, and a chorus hook that repeats with small variations. Low mutation so the "words" stay put.
   - **Lead line:** an instrumental voice that plays off the song: motifs stated and developed, longer phrases, bends and slides (glide, pitch rise), room for Jam or LickBuilder improvisation in solo sections.
   - **Riff:** a short, repeating hook locked to the groove, often one or two bars, rhythm first, carried by bass or a mid-register voice, little variation.
   - **Jam:** improvised sections, the heart of the jam-band north star. The band trades the lead, players get pulled in for duets, and solos build and resolve. The guide teaches:
     - when to open a jam section and how long it should run, and how it returns to the song (a lyric line or riff coming back is the landing);
     - the solo modes: Jam (in-scale improvisation snapped to chord tones, with phrase memory passed to the next soloist), LickBuilder (motif construction with mutation) and LongFill;
     - the `band`: members, `alwaysActive`, loudness and creativity, and the `handoffMatrix`/`pullInMatrix` shapes (one star lead, trading front lines, sparse ambient);
     - the traps that silence a jam: only a Melodic-role track can lead a Jam, two members is not a band, and a soloist zeroed to `density` 0 in a solo section never sounds;
     - how a jam uses the vibe's own melodies: a lead line as the jam's opening statement, a riff as the bed under it, the lyric hook as the return.
   For each kind, the guide covers the `Lick` fields that make it (steps, `scaleDegree`, durations, negative-degree rests, `loopLength`, `lickMode` Fill or Squash, `lickMutation`), which channel and track carries it, how sections pick it (`lickRotation.pool` plus `Section.lickIndex`), and register and chord rules so it sits with the drones and the bass. Worked JSON examples come from real shipped licks (e.g. Orphic Lore's Sirens line).
6. **A song reference tool.** The user pastes what they have (a tab page's HTML like Ultimate Guitar's, or plain tab or chord text). The tool extracts:
   - chords, from chord names or fret shapes, giving the progression and key;
   - the song's map (intro/verse/chorus/solo/outro and repeats), giving sections;
   - lyric cue lines, each one a phrase slot, so the melody gets the right number of lines and breaths;
   - single-note tab lines (solos, riffs) as exact melodies.
   Sung pitches are not in tabs; they come from the model's memory, shaped by the phrase slots and keyed to the real chords.
7. **A detuned double voice (new DSP).** A per-track option that renders the track's engine twice, slightly detuned, with an optional stereo spread. The user's ear says the Sirens singer sounds better with two detuned voices. It works on any engine, so it can also fatten a lead.
8. **Mixing with the user.** After building, the agent does one or two passes of its own (play, `mix_read`, `audio_analyze`, fix the obvious with `mix_set`), then opens the Mixer tab and works with the user: the user moves faders and knobs, the agent reads the changes and suggests the next move, and `mix_capture` writes the result back into the AI vibe. The user judges the sound; numbers only guide.
9. **One set of instructions for both agents.** The in-app agent calls the tools directly; Claude Code calls the same tools over MCP. They follow one composition guide, not two hand-synced copies (today `STATIC_GUIDE` in `features/ai` mirrors `.claude/skills/vibe-creator/SKILL.md` by hand). The orphic-mcp and vibe-creator skills point at the shared guide for the JSON workflow.

## Non-goals

- Fetching pages from the web inside the app, or pulling note data from services whose terms forbid it (e.g. Songsterr). The user pastes; the tool parses.
- Lyrics or words in any voice.
- Changing the Orpheus synth's own composer (`start_composition`).
- iOS-specific work beyond what the shared code gives.

## Anti-goals

- A vibe that is a reskin of a shipped vibe.
- Every AI vibe getting a vocal lead because the capability exists.
- A melody that is a note-for-note copy of a real song. Recognisable, not a total copy; names stay original (the vibe-creator naming rule).
- Randomness that ignores the chords, the registers or what the user asked for.
- Two guides drifting apart again.

## Decided by the user

- Remove "copy vibe": a blank skeleton plus dice-rolled casting, combined with music-theory knowledge of what sounds good.
- Standard drone tracks, overlaid with singing melody lines by weird voices that work well with energy.
- The vocal capability is optional; a melody per section is wanted, with no strict rules.
- The user would paste a tab page when asked for a reference.
- A new DSP: two detuned voices.
- The agent works with the user in the Mixer tab after one or two passes of its own.
- The in-app tools and the skill should work almost the same (MCP versus direct), with combined instructions; some in-app tools go, starting with `pulsar_get_vibe` as the template source.

## Decided by the user after the builder check (2026-10-22)

- **Mixing together reads on return.** The user tweaks in the Mixer tab, closes it and says so in chat; the agent then reads the mix with `mix_read` and suggests the next move. No live event plumbing, so Claude Code over MCP works the same way.
- **The skeleton is A/B tested.** Casting has two modes the user will compare by ear:
  - **Bed fixed:** a drone bed from a small vetted library is the one fixed foundation; drums and bass are cast like everything else, so an ambient vibe can have no kit.
  - **Everything rolled:** no fixed slots; the theory rules alone keep it working.
  Both ship behind a setting until the user picks one (or keeps both).
- **Melodies take turns.** The lyric-melody lead and any riff or solo line share the LEAD channel and take turns by section, pinned with `lickIndex`. No third melody channel.

## Calls made after the builder check (mine; the user can override)

- **Paste delivery:** the app keeps a pasted reference (paste or attach in the AI chat) and the song reference tool reads it by id, so the model never repeats a long page as a tool argument. Over MCP, Claude Code passes the text or a file path.
- **The agent loop is bounded in code,** not only by prompt text: per-turn budgets for applies and mix edits, tested, because the current prompt rule exists to stop endless loops and the app runs models down to Haiku-class.
- **Engine changes for melodies are opt-in fields** (a per-section melody for the bass-channel singer, chord-aware mutation), so shipped vibes sound exactly as they do now and their fixtures do not move.
- **Parser tests use a synthetic page** in the same format. The user's real paste stays local-only, since origin/main is public and tab text is copyrighted.
- **Sameness is reported, not gated:** a similarity check (best-template match against shipped vibes and between trial vibes, like the 2026-10-22 survey) reports numbers; the user's ear decides.
- **The Kotlin workflow keeps going but follows the shared composition rules.** `vibe-creator` still writes `*Vibe.kt` for shipped vibes; its composition guidance (no copying, casting, theory, vocal, mixing) comes from the shared guide instead of its own copy.

## Constraints and facts

- **Tools today** (in-app agent, FeatureScope `ToolProvider`s, also served over MCP in the AI edition): `pulsar_vibe_guide`, `pulsar_vibe_schema`, `pulsar_get_vibe`, `pulsar_apply_vibe`, `start_composition`, `vibe_list`, `vibe_load`, `transport`, `mix_read`, `mix_set`, `mix_mute`, `mix_solo`, `mix_reset`, `mix_capture`, `section_jump`, `section_loop`, `ports_list`, `port_get`, `port_set`, `audio_analyze`, `audio_capture`. `features:ai` is shared with the Orpheus synth app, which has none of the mix tools, so shared guide text must be conditional where it names them.
- **The agent prompt** (`OrpheusAgentConfig`): "Build in a single turn", "STOP AFTER A SUCCESSFUL APPLY ... EXACTLY ONCE". The collaboration loop needs this replaced.
- **Melody mechanics** (from the engine survey):
  - `lickRotation` plus `Section.lickIndex` pins a pool slot per section; without a pin, rotation re-rolls randomly at each section change; the pool is capped at 8 slots (`MAX_LICK_POOL`).
  - Only the LEAD channel rotates. `bassLine` never rotates, so a singer reading the bass channel (Orphic Lore's Sirens) sings one melody all song.
  - `TrackSectionOverride` has no per-section lick, `lickDegreeOffset`, note range, `lickSource` or engine.
  - `lickMutation` swaps a note for a random scale degree 0-6, ignoring chords, register and contour, and can turn a rest into a note. Jam is the most musical random source (in scale, snapped to chord tones, with phrase memory).
  - Breaths: negative-degree rests, `loopLength` tails, `hitProbability`, `breatheBars`/`breatheFloor`.
  - Glide only slides between adjacent gated notes; a rest kills it.
  - Energy above 0.6 plays `engineEdm`, below 0.4 `engineSpace`, a probabilistic pick between.
- **Expressive controls** that work on any engine: `vibratoDepth`, `pitchRise`, `harmonicsRise`, `morphRise`, `grainFx` (the Sirens' choir). Grains squeak above about F5.
- **Mixing:** phase 4's guide section 11 (fields, targets, the mix pass), the Mixer tab (phase 5) and the stem/analysis tools (phase 2) exist. A vibe load clears live mix edits, so capture comes before any re-apply.
- **The tab page format** (from the user's paste): Ultimate Guitar embeds the tab text, HTML-escaped, in the page's `js-store` data under `wiki_tab.content`, with `[tab]` blocks, section labels, quoted lyric cues and fret-number lines.

## Pieces (each its own plan, order is the builder's)

1. **Shared guide and tool reshaping.** One composition guide source for both agents and both skills; `pulsar_get_vibe` demoted to reference-only reading (or removed); the single-turn and stop-after-apply prompt rules replaced; blank-skeleton casting with theory rules and the expanded palette in the guide.
2. **Song reference tool.** Paste in, structured reference out (chords and key, sections, phrase slots, single-note melodies).
3. **Vocal lead capability and melody teaching.** Guide teaching (what lyric, lead, riff and jam lines are and how to build and run each, singer recipes, phrasing, per-section melodies with `lickIndex`), plus whatever engine work the per-section melody needs, e.g. letting the bass-channel singer change melody per section, and a chord-aware mutation so randomness stays musical.
4. **Detuned double voice.** A per-track engine double with detune and spread, in the C++ DSP, exposed on the track model and documented in the guide.
5. **Collaboration loop.** The agent's own passes, opening the Mixer tab for the user, reading the user's changes, capturing.

**Done when:** the Toyes test prompt, with the pasted tab, gives a vibe that the user finds recognisable but its own, with the lyric melody carried by a funky non-vocal lead, mixed together in the Mixer tab; and two different prompts give vibes that do not share a template.

## Left to the builder

- The guide's file format and location, and how both agents load it.
- The exact casting algorithm, the palette's weights, and how the roll is recorded in the vibe.
- The song reference tool's output schema and its parser details.
- The double voice's parameter names, ranges and CPU budget.
- How the agent opens the Mixer tab.
- Which order the pieces land in.

## Appendix: piece 1 rulings

Decisions taken while building piece 1, one line each.

- The guide is one Compose resource, `features/ai/src/commonMain/composeResources/files/vibe_guide.md`, with `{{X}}` placeholders (examples, the turn budget caps) filled at load; both skills point at it.
- Rolled tone is audible: each track's macroMap is centred on the roll (macros still move it), pins only where a voice must hold, and the pairing check compares the values render actually plays (the C++ lerp, playability floors included).
- Drone beds keep their source vibes' macroMaps, with energy density held at 1, and start at volume 0.45 or under, below the bass.
- Registers use full note ranges, sustained voices overlapping by at most 2 semitones; style tags are sorted so their order never changes a roll.
- The bass stays on track 3 in both casting modes, because the engine treats track 3 as the bass channel (spec drift accepted).
- Tracks 5-6 are cast as held Chordal parts so they sound at the vibe's macros; track 7 speaks only at the energy extremes and its summary says so. A mirror of the C++ gates tests audibility for every cast.
- The cast tool validates style tags, mode and pins; the skeleton is sent without defaults; a cast vibe records its seed, mode, tags and pins in a field the engine ignores.
- The agent loop resets Koog's iteration count each turn; a per-turn budget (3 applies, 12 mix edits) refuses with `budgetSpent: true`, and every retry rule defers to it; MCP calls are exempt through a coroutine-context marker.
- Changes to the playing vibe take a numbered tweak path (`pulsar_get_vibe('current')`, edit, apply), never a new cast.
- The guide states engine facts as the code does: rhythmDensity is a number, hold works only on tracks 5-7 (engineEdm values), below energy 0.4 every track plays its SPACE voice, and on VOWEL_FOF harmonics picks the vowel and morph the voice type.

## Appendix: piece 1 known gaps

- Tracks 5-6 are always Chordal in a cast, so no cast rolls a sparse lick-less texture line there; to be judged by ear.
- An SPK lead pin is refused in BED_FIXED mode; the cast-mode toggle row is tested in state only, not rendered.
- The guide grew to about 840 lines (the Reference part sits in the same file), so small models read all of it.
- Not yet tried through the in-app agent with a real model; verified over MCP and by tests.

