# SixOp FM Patch Banks — DX, DX2, DX3

The `Engine.DX`, `Engine.DX2`, `Engine.DX3` entries are **not three flavors of the same FM engine**. They share the same 6-operator FM voice (DX7-style), but each one loads a different 32-patch sysex bank from MI's `eurorack/plaits/resources/fm_patches.py`. **The `harmonics` knob is a patch selector**, not a tone control. `timbre` and `morph` modify the loaded patch.

Get this wrong and you'll be writing a "brass lead" comment over a koto.

> **Naming note:** this doc writes `Engine.DX` / `Engine.BD` / `Engine.VCF` etc. as shorthand. The actual type is **`OrpheusEngineId`** (imported from `org.balch.orpheus.core.audio`), and its constants are the short codes: `OrpheusEngineId.DX`, `OrpheusEngineId.DX2`, `OrpheusEngineId.DX3`, `OrpheusEngineId.BD`, `OrpheusEngineId.WSH`, `OrpheusEngineId.STR`, … In vibe code write `engineId = OrpheusEngineId.X` — there is no bare `Engine` type. Read every `Engine.X` below as `OrpheusEngineId.X`.

## Patch name ≠ final voice

The bank tables below list each patch's **original DX7 name** — what Yamaha (or the patch author) called it on the cartridge in 1985. **That name describes the patch's intended voice at its design register, with default envelope and no effects.** In Pulsar a patch is played in whatever register the track's `noteRangeLow/High` allows, with the vibe's envelope mode (AD vs TIDES), the track's own `holdProbability` and `glideRate`, and per-track reverb/delay sends layered on top. Any of those can transform the apparent voice substantially.

Two real cases from this codebase:

- **VoltageStrutVibe track 4** loads DX2 patch idx 16 = "Xylophone" — but at C4-G6 with `reverbSend=0.25`, `delaySend=0.30`, and `BarStrategy.MUTATE`, the mallet attack reads as **glassy/bell-tone synth stabs**, not a literal xylophone. An audit by patch name flagged it as wrong; an audit by ear confirmed it was right.
- **ArmyStompVibe track 7** also tried DX2 idx 16 = "Xylophone" but at C3-G4 with `BLUES_SHUFFLE` comping it sounded too high and too literally mallet-y. Switched to DX2 idx 17 = "Marimba" with note range dropped to F2-C4 — same FM-mallet ring, but the patch's natural register and the lower note bounds combined to read as **woody chord stabs**, which fit the march/ska-breakdown character.

The takeaway when picking patches:

1. The name is a **starting point**, not the final answer. It tells you the patch's "default" voice at its design register.
2. Pulsar's note range, envelope, and effect sends shift the apparent voice. A patch transposed up or down, with reverb pushed, with delays trailing, can read as something the original name didn't suggest.
3. **Audit tip**: If a comment says "X" but the patch name suggests "Y", listen before declaring it wrong. Sometimes the comment is right and the patch name is just a label from 40 years ago.
4. **Iteration tip**: When tuning a track, two parameters do most of the work — `harmonics` (patch select) and `noteRangeLow/High` (register). Try the same patch at different ranges before reaching for a different patch.

## How the knobs actually work on SixOp engines

Source: `eurorack/plaits/dsp/engine2/six_op_engine.cc:103-141`.

```cpp
int patch_index = patch_index_quantizer_.Process(parameters.harmonics * 1.02f);
p->brightness       = parameters.timbre;   // FM modulator amount / brightness
p->envelope_control = parameters.morph;    // DX7-style envelope rate/level scaling
```

| Knob | Effect on a SixOp engine |
|---|---|
| `harmonics` | **Patch selector** — quantized to 32 zones, picks 1 of 32 patches in the bank. |
| `timbre` | Brightness — scales modulator output / FM index. ~0.5 = patch defaults. Higher = brighter / more aggressive harmonics. |
| `morph` | Envelope-control axis. Speeds up / slows down the patch's envelope shape (think DX7 EG rate scaling). ~0.5 = neutral. |

### The harmonics → patch math

The quantizer math is `patch = round(harmonics * 1.02 * 32) = round(harmonics * 32.64)`, clamped to `[0, 31]`. **The bucket centerpoint for patch index N is `N / 32.64`.** That is the value that lands cleanly inside bucket N — it has the largest margin to either neighbor. To target patch N, set:

```
harmonics = N / 32.64
```

Centerpoint values pre-computed (3-decimal-rounded, all safe-inside-bucket):

| Idx | Harm | Idx | Harm | Idx | Harm | Idx | Harm |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 0.000 | 8 | 0.245 | 16 | 0.490 | 24 | 0.735 |
| 1 | 0.031 | 9 | 0.276 | 17 | 0.521 | 25 | 0.766 |
| 2 | 0.061 | 10 | 0.306 | 18 | 0.551 | 26 | 0.797 |
| 3 | 0.092 | 11 | 0.337 | 19 | 0.582 | 27 | 0.827 |
| 4 | 0.123 | 12 | 0.368 | 20 | 0.613 | 28 | 0.858 |
| 5 | 0.153 | 13 | 0.398 | 21 | 0.643 | 29 | 0.888 |
| 6 | 0.184 | 14 | 0.429 | 22 | 0.674 | 30 | 0.919 |
| 7 | 0.214 | 15 | 0.460 | 23 | 0.705 | 31 | 0.950 |

Bucket N spans `harmonics ∈ [(N − 0.5) / 32.64, (N + 0.5) / 32.64)` — about a 0.0306-wide window per patch. The hysteresis quantizer adds 0.005f stickiness so float jitter won't flip patches near boundaries, but **don't write boundary values**: a number like `(N + 0.5) / 32.64` is the *right edge* of bucket N and rounds into N+1 with normal float arithmetic. Always pick the centerpoint above.

## Bank → Engine mapping

Confirmed at `eurorack/plaits/dsp/voice.cc:130-133`:

```cpp
if (engine_index >= 2 && engine_index <= 4) {
    data = fm_patches_table[engine_index - 2];
}
```

| Engine | id | Bank | Character |
|---|---:|---:|---|
| `Engine.DX`  | 2 | 0 | Basses + analog/digital synths |
| `Engine.DX2` | 3 | 1 | Keys (E.piano, piano, clav) + plucked + chroma percussion + drums |
| `Engine.DX3` | 4 | 2 | Organs + pipes + pads + strings + **brass** |

## Bank 0 — `Engine.DX` (basses + analog synths)

Source: `fm_patches.py:51-91`. Caps reflect uppercase notes from the original.

| Idx | Harm | Patch | Source ROM |
|---:|---:|---|---|
| 0 | 0.000 | Solid bass | MISC/0 |
| 1 | 0.031 | Mooger Low | MISC/21 |
| 2 | 0.061 | LeaderTape | MISC/2 |
| 3 | 0.092 | Morhol TB1 | Guit_Clav5/19 |
| 4 | 0.123 | Bass 3 | ROM1B/30 |
| 5 | 0.153 | Bill bass | KV04B/23 |
| 6 | 0.184 | Bass 1 | ROM1A/14 |
| 7 | 0.214 | Elec Bass | Guit_Clav5/2 |
| 8 | 0.245 | S.Bas 27.7 | MISC/1 |
| 9 | 0.276 | Resonances | Guit_Clav2/30 |
| 10 | 0.306 | Syn-bass 2 | ROM2B/15 |
| 11 | 0.337 | Prc synth1 | ROM3A/15 |
| 12 | 0.368 | Croma 2 | Guit_Clav4/11 |
| 13 | 0.398 | Analog 4 (squarewavy brass) | MISC/3 |
| 14 | 0.429 | Analog A | KV04A/0 |
| 15 | 0.460 | Analog 6 (sawy) | MISC/4 |
| 16 | 0.490 | CS-80 | Guit_Clav4/18 |
| 17 | 0.521 | Insert 1 (BRASSY) | Guit_Clav4/22 |
| 18 | 0.551 | Spiral | Guit_Clav2/31 |
| 19 | 0.582 | Dx-Trott bass | Guit_Clav4/9 |
| 20 | 0.613 | GasHaus | MISC/5 |
| 21 | 0.643 | Ring ding | Guit_Clav3/31 |
| 22 | 0.674 | Papagayo | Guit_Clav4/29 |
| 23 | 0.705 | Wineglass | KV04B/14 |
| 24 | 0.735 | Amytal (throaty pad) | Guit_Clav2/17 |
| 25 | 0.766 | Fairlight | Guit_Clav4/2 |
| 26 | 0.797 | PPG Vol 1 | PPGVOCAL/0 |
| 27 | 0.827 | PPG Vol 2 | PPGVOCAL/1 |
| 28 | 0.858 | *Fairl. 3 | PPGVOCAL/26 |
| 29 | 0.888 | *Vocoder 2 | PPGVOCAL/19 |
| 30 | 0.919 | * Sequence | PPGVOCAL/21 |
| 31 | 0.950 | Bounce 4 | MISC/13 |

**DX is the right pick for**: bass tracks needing FM grit, retro-digital lead synths, vocoder/PPG-style metallic synths.

## Bank 1 — `Engine.DX2` (keys, plucked, chroma percussion, drums)

Source: `fm_patches.py:92-133`.

| Idx | Harm | Patch | Source ROM |
|---:|---:|---|---|
| 0 | 0.000 | E piano 1 | ROM1A/10 |
| 1 | 0.031 | Fender 1 | Guit_Clav3/22 |
| 2 | 0.061 | WintrRhodes | MISC/6 |
| 3 | 0.092 | RS-EP C | KV04B/18 |
| 4 | 0.123 | Mark III | MISC/7 |
| 5 | 0.153 | Clav E pno | ROM4B/0 |
| 6 | 0.184 | Syn Clav | Guit_Clav1/13 |
| 7 | 0.214 | Clavinet | KV04B/20 |
| 8 | 0.245 | Piano 5 | ROM1B/1 |
| 9 | 0.276 | Grd Piano | Guit_Clav5/3 |
| 10 | 0.306 | Steinway | Guit_Clav1/21 |
| 11 | 0.337 | Guit acous | Guit_Clav5/16 |
| 12 | 0.368 | Sitar | ROM1B/21 |
| 13 | 0.398 | Koto | ROM1A/22 |
| 14 | 0.429 | Harpsich | ROM3A/1 |
| 15 | 0.460 | Clav 3 | ROM1B/11 |
| 16 | 0.490 | Xylophone | ROM2A/23 |
| 17 | 0.521 | Marimba | ROM3A/6 |
| 18 | 0.551 | Vibe 1 | ROM1A/20 |
| 19 | 0.582 | Glockenspl | ROM2A/21 |
| 20 | 0.613 | Bell C | KV04B/15 |
| 21 | 0.643 | Bells | ROM4A/20 |
| 22 | 0.674 | Tub Bells | ROM1A/25 |
| 23 | 0.705 | Gong 2 | ROM2A/26 |
| 24 | 0.735 | Kettle | SYN9/8 |
| 25 | 0.766 | Mid drum 3 | SYN9/27 |
| 26 | 0.797 | Ori Drum | SYN9/10 |
| 27 | 0.827 | Wood 6 | SYN9/3 |
| 28 | 0.858 | Latin Drum | SYN9/17 |
| 29 | 0.888 | Cimbal | SYN9/24 |
| 30 | 0.919 | SYNDM 25.8 | MISC/8 |
| 31 | 0.950 | B Drm-Snar | ROM2B/21 |

**DX2 is the right pick for**: chordal/keyboard tracks (E.piano, clav, piano), chromatic percussion (xylophone, vibes, bells), tuned drums for ethnic/world feels (kettle, latin drum). **Not** for generic "FM lead".

## Bank 2 — `Engine.DX3` (organs, pipes, pads, strings, brass)

Source: `fm_patches.py:134-169`.

| Idx | Harm | Patch | Source ROM |
|---:|---:|---|---|
| 0 | 0.000 | Click 124 | Guit_Clav3/1 |
| 1 | 0.031 | Hammond | MISC/22 |
| 2 | 0.061 | E organ 3 | ROM1B/13 |
| 3 | 0.092 | 60s organ | ROM3B/14 |
| 4 | 0.123 | Optic 28 | Guit_Clav3/19 |
| 5 | 0.153 | Pipes 1 | ROM1A/17 |
| 6 | 0.184 | Pipes 3 | ROM1B/17 |
| 7 | 0.214 | Pipes 2 | ROM3B/15 |
| 8 | 0.245 | JX-33-P | MISC/9 |
| 9 | 0.276 | Soundtrack | Guit_Clav4/20 |
| 10 | 0.306 | Ice pad 2 | MISC/11 |
| 11 | 0.337 | M1 PADS | MISC/12 |
| 12 | 0.368 | CARLOS 2 | MISC/14 |
| 13 | 0.398 | Soft touch | MISC/16 |
| 14 | 0.429 | *Planets | PPGVOCAL/30 |
| 15 | 0.460 | Cirrus | MISC/17 |
| 16 | 0.490 | ENTRIX | MISC/18 |
| 17 | 0.521 | Mal Poly | Guit_Clav4/27 |
| 18 | 0.551 | Textures 6 | MISC/20 |
| 19 | 0.582 | Etherial5a | MISC/10 |
| 20 | 0.613 | Airy | MISC/15 |
| 21 | 0.643 | Boron A | MISC/19 |
| 22 | 0.674 | Vangelis 1 | Guit_Clav4/5 |
| 23 | 0.705 | Strings C | KV04B/5 |
| 24 | 0.735 | Strings 3 | ROM1A/5 |
| 25 | 0.766 | Strings 2 | ROM1A/4 |
| 26 | 0.797 | Strings 7 | ROM2A/9 |
| 27 | 0.827 | Full strin | Guit_Clav1/7 |
| 28 | 0.858 | Syn orch | Guit_Clav1/2 |
| 29 | 0.888 | **Brass 1** | ROM1A/0 |
| 30 | 0.919 | **Brass 6 BC** | ROM2A/13 |
| 31 | 0.950 | **Br trumpet** | ROM3A/5 |

**DX3 is the right pick for**: tonewheel organs, church-pipe leads, ambient/cinematic pads, string ensembles, brass-section stabs and trumpet leads. *This* is the FM lead bank for melodic instrument voicings.

## Translation recipes

| You want | Engine | Harmonics | Notes |
|---|---|---:|---|
| FM bass (gritty, classic) | `Engine.DX` | 0.000–0.276 | Idx 0–9, especially 0 (Solid bass), 4 (Bass 3), 8 (S.Bas 27.7). |
| FM bass (sub-y / squarewavy) | `Engine.DX` | 0.398 | Idx 13 — "Analog 4 squarewavy brass". |
| Vocoder/PPG metallic | `Engine.DX` | 0.797–0.919 | Idx 26–30. |
| Wurly / Rhodes / E.piano | `Engine.DX2` | 0.000–0.214 | Idx 0–7; 0.000 = E.piano 1, 0.061 = WintrRhodes. |
| Acoustic piano | `Engine.DX2` | 0.245–0.306 | Idx 8–10 (Piano 5, Grd Piano, Steinway). |
| Sitar / koto / harpsi (exotic plucked) | `Engine.DX2` | 0.368–0.429 | Idx 12–14. |
| Mallets (xylo / marimba / vibes) | `Engine.DX2` | 0.490–0.551 | Idx 16–18. |
| Bells / glockenspiel / tubular | `Engine.DX2` | 0.582–0.674 | Idx 19–22. |
| Tonewheel organ / Hammond | `Engine.DX3` | 0.031 | Idx 1. |
| Church pipes / pipe lead | `Engine.DX3` | 0.153–0.214 | Idx 5–7. |
| Cinematic pad (ambient, planet-y) | `Engine.DX3` | 0.306–0.643 | Idx 10–21. Wide range — pick by ear. |
| String section | `Engine.DX3` | 0.705–0.858 | Idx 23–28. |
| **Brass section** | `Engine.DX3` | **0.888–0.919** | Idx 29–30 (Brass 1, Brass 6 BC). |
| **Solo trumpet** | `Engine.DX3` | **0.950** | Idx 31 (Br trumpet). The "trumpets sound" voice. |

## What `timbre` and `morph` do on top of the patch

After the patch is loaded:

- `timbre` (0–1): scales modulator output. 0.5 = patch defaults. Push to 0.6–0.7 for more bite/brilliance, drop to 0.3–0.4 to soften. On brass, slightly above 0.5 (0.55–0.65) gives the patch more horn-like edge without honking.
- `morph` (0–1): scales envelope rate. 0.5 = neutral. Lower values stretch the envelope (slower attack/decay), higher values compress it (snappier). On pads, lower morph (0.3–0.4) helps; on percussive patches that are already fast, leave at 0.5.

These are useful for fine-tuning, but they cannot turn an E.piano into a brass section. The patch bank does the heavy lifting — pick the right engine first.

## Verification

If you want to double-check the bank-to-engine assignment:

```bash
grep -n "fm_patches_table" /Users/balch/Source/eurorack/plaits/dsp/voice.cc
```

If MI ever shuffles the bank order in a future Plaits release, this table is the only thing that needs to change.

## Worked example

ArmyStompVibe's squash lead, before:

```kotlin
engineId = OrpheusEngineId.VCF,
// ...no harmonics/timbre/morph set
```

After (correct brass selection):

```kotlin
engineId = OrpheusEngineId.DX3,
harmonics = 0.950f,    // patch 31 = "Br trumpet" — fits the "trumpets sound" theme (auto-pinned)
// timbre / morph: leave unset; macroMap.moodTimbre + spaceDecay drive them.
// To lock them, add pinTimbre = true / pinMorph = true.
```

A common antipattern is leaving `harmonics` unset (default 0.5) when using `DX/DX2/DX3` — that lands on patch 16 (random middle of the bank), which is rarely what you want. **Always set `harmonics` explicitly on SixOp engines** using a centerpoint from the table above. The DX-family engines auto-pin harmonics regardless of `pinHarmonics`, so the value you write is what plays.

### Optional: bounded patch walk via `harmonicsModulation`

If you want texture-evolution that walks across nearby patches (a controlled version of what was happening pre-pin), opt in with `harmonicsModulation`:

```kotlin
OrpheusEngine(
    engineId = OrpheusEngineId.DX3,
    harmonics = 0.582f,            // base patch (idx 19 "Etherial5a")
    harmonicsModulation = 0.05f,   // ±0.05 LFO swing → walks idx 17..21
    modLfoDepth = 0.85f,           // controls swing amplitude (LFO depth)
    modLfoRate = 0.04f,            // glacial drift
)
```

The LFO is bipolar `[-1, +1]` already scaled by `modLfoDepth × texture_curve`, then multiplied by `harmonicsModulation` and added to the pinned base. Default `0.0f` = fully pinned (no walk). A bucket spans ~0.0306 in harmonics-space, so:

| `harmonicsModulation` | LFO walks across | Typical use |
|---|---|---|
| `0.03f` | ±1 patch | Subtle breathing within the neighborhood |
| `0.05f` | ±2 patches | Audible walk within a tonal family |
| `0.10f` | ±3 patches | Wide walk across related characters |
| `0.20f+` | ±6 patches | Drifty / unpredictable — likely too wild |

Only effective when harmonics is pinned (DX-family auto-pin or explicit `pinHarmonics = true`). On non-pinned tracks, harmonics already gets LFO modulation through the standard macroMap path — this field is the escape hatch for the otherwise-locked DX path.
