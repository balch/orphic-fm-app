# Orpheus Mini-Notation Syntax Guide

This document describes the mini-notation syntax supported by the Orpheus live coding environment. The syntax is a direct port of the TidalCycles mini-notation, adapted for the Orpheus engine.

This guide is intended for both human users and AI agents generating musical patterns.

## Core Concepts

A **Pattern** describes how events (notes, samples, parameters) are distributed over time. Time in Orpheus is cyclic.

### 1. Basic Sequencing
Space-separated elements are played sequentially within one cycle.
```tidal
"bd sd"
```
*Effect:* `bd` plays at 0.0, `sd` plays at 0.5.

### 2. Silence / Rests
Use `~` or `-` to denote a rest.
```tidal
"bd ~ sd"
```
*Effect:* `bd` at 0.0, silence at 0.33, `sd` at 0.66.

### 3. Grouping / Subdivision
Use square brackets `[]` to group elements. The group occupies the time of a single step.
```tidal
"bd [sd sd]"
```
*Effect:* `bd` (50% duration), then two `sd`s (25% duration each).

### 4. Polyphony / Stacking
Use a comma `,` to play multiple patterns simultaneously.
```tidal
"bd sd, hh hh hh"
```
*Effect:* `bd sd` plays in parallel with `hh hh hh`.

### 5. Slowcat / Alternation
Use angle brackets `<>` to pick one element per cycle.
```tidal
"bd <sd cp>"
```
*Cycle 1:* `bd sd`
*Cycle 2:* `bd cp`
*Cycle 3:* `bd sd` ...

### 6. Speed Modifiers
Modify the speed of a step or group.
- `*` (fast): Play n times faster (repeat).
- `/` (slow): Play n times slower.

```tidal
"bd*2 sd"
```
*Effect:* `bd` `bd` `sd` (bd occupies same total time as normal, but repeats). two `bd`s take 50% total.

```tidal
"bd/2"
```
*Effect:* `bd` plays at half speed (takes 2 cycles).

### 7. Replication
Use `!` to repeat a step.
```tidal
"bd!3 sd"
```
*Equivalent to:* `"bd bd bd sd"`

### 8. Euclidean Rhythms
Use `(k, n)` to distribute `k` events evenly over `n` steps.
```tidal
"bd(3,8)"
```
*Effect:* Plays `bd` 3 times within 8 beat slots (Bjorklund algorithm). Pattern: `x . . x . . x .`

### 9. Polymeters
Use braces `{}` to sequence items that don't align to the cycle boundary.
```tidal
"{bd, sn hh}"
```
*Effect:* `bd` pulse aligns with cycle, `sn hh` pulse drifts or aligns differently depending on step definition.
(Note: Current implementation parses `{}` similar to stacks but preserves step integrity).

## Advanced Examples

**Breakbeat Construction:**
```tidal
"[bd sd] [~ sd] [bd [sd bd]] [~ sd]"
```

**Polyrhythmic Hi-hats:**
```tidal
"hh*2, bd(3,8)"
```

**Melodic Sequence (using note numbers):**
```tidal
"<0 2 4> [7 9]"
```

### 10. Elongation / Weighting
Use `@` to give a step more time relative to its siblings.
```tidal
"bd@2 sd"
```
*Effect:* `bd` gets 2/3 of the cycle, `sd` gets 1/3.

```tidal
"bd@3 sd@2 hh"
```
*Effect:* Weights 3:2:1, so `bd` gets 3/6 (50%), `sd` gets 2/6 (33%), `hh` gets 1/6 (17%).

### 11. Ranges
Use `..` to expand a range of numbers into a sequence.
```tidal
"0..3"
```
*Equivalent to:* `"0 1 2 3"`

```tidal
"7..4"
```
*Effect:* Counts down: `7 6 5 4`

**Range in context:**
```tidal
"0..2 5"
```
*Effect:* `0 1 2 5` in sequence.

## AI Agent Usage Instructions
When generating patterns:
1. **Be Concise**: Use `*` and `[]` to create density rather than long strings.
2. **Use Structure**: Combinations of `euclid` (parentheses) and `stacking` (commas) create the most interesting rhythms.
3. **Use Ranges**: For melodic runs, `0..7` is cleaner than listing all values.
4. **Use Elongation**: For swing or emphasis, `bd@2 sd` gives `bd` more presence.
5. **Safety**: Ensure braces `[]` `{}` `<>` are balanced.

## Parser Features
- ✅ Sequences and groups `[]`
- ✅ Stacks (polyphony) `,`
- ✅ Slowcat alternation `<>`
- ✅ Speed modifiers `*` `/`
- ✅ Replication `!`
- ✅ Euclidean rhythms `(k,n)`
- ✅ Polymeters `{}`
- ✅ Ranges `..`
- ✅ Elongation `@`
- ⚠️ Degrade `?` is parsed but not yet functional

---

## Orpheus REPL Commands

The Orpheus REPL supports Tidal-style pattern slots (`d1` through `d8`) with a `$` operator for pattern assignment.

### Pattern Slot Assignment

```tidal
d1 $ note "c3 e3 g3"
d2 $ voices:0 1 2 3
d3 $ slow 2 note "c2 f2 g2"
```

### Pattern Types

#### Note Patterns
Play melodic notes using MIDI note names (c0-b9, with sharps # and flats b):
```tidal
d1 $ note "c3 e3 g3 c4"
d1 $ note "c#3 d#3 f#3"  -- sharps
d1 $ note "db3 eb3 gb3"  -- flats
```

#### Voice Patterns
Trigger voice envelopes (0-7):
```tidal
d1 $ voices:0 1 2 3
d2 $ voices:4 5 6 7
```

#### Sound Patterns (Drums)
Play drum samples:
```tidal
d1 $ s "bd sn hh cp"
d1 $ sound "kick snare hat clap"
```

### Transformations

```tidal
d1 $ fast 2 voices:0 1      -- Double speed
d1 $ slow 4 note "c3 g3"    -- Quarter speed
d1 $ slow 2 $ note "c3 e3"  -- Chained transformation
```

---

## Synth Control Commands

All values are normalized 0.0-1.0 unless otherwise specified.

### Voice Level Controls (0-7)

| Command | Description | Example |
|---------|-------------|---------|
| `hold:<voice> <val>` | Voice sustain/hold level | `d1 $ hold:0 0.8` |
| `tune:<voice> <val>` | Voice pitch (0.5 = unity) | `d1 $ tune:0 0.6` |
| `pan:<voice> <val>` | Voice pan (-1 to 1) | `d1 $ pan:0 -0.5` |

### Quad Level Controls (0-2)
Quads group 4 voices: Quad 0 = voices 0-3, Quad 1 = voices 4-7, Quad 2 = voices 8-11

| Command | Description | Example |
|---------|-------------|---------|
| `quadhold:<quad> <val>` | Quad hold level | `d1 $ quadhold:0 0.7` |
| `quadpitch:<quad> <val>` | Quad pitch (0.5 = unity) | `d1 $ quadpitch:1 0.6` |

### Duo Level Controls (0-3)
Duos group 2 voices: Duo 0 = voices 0-1, Duo 1 = voices 2-3, etc.

| Command | Description | Example |
|---------|-------------|---------|
| `duomod:<duo> <source>` | Modulation source (fm/off/lfo) | `d1 $ duomod:0 lfo` |

### Pair Level Controls (0-3)
Pairs group 2 voices with shared waveform sharpness.

| Command | Description | Example |
|---------|-------------|---------|
| `sharp:<pair> <val>` | Waveform sharpness (0=tri, 1=sq) | `d1 $ sharp:0 0.8` |

### Global Effects

| Command | Description | Example |
|---------|-------------|---------|
| `drive:<val>` | Distortion drive | `d1 $ drive:0.5` |
| `distmix:<val>` | Distortion wet/dry mix | `d1 $ distmix:0.3` |
| `vibrato:<val>` | LFO/vibrato depth | `d1 $ vibrato:0.4` |
| `feedback:<val>` | Delay feedback amount | `d1 $ feedback:0.7` |
| `delaymix:<val>` | Delay wet/dry mix | `d1 $ delaymix:0.6` |
| `volume:<val>` | Master volume | `d1 $ volume:0.8` |

---

## Example: Ambient Drone

```tidal
-- Melodic layers
d1 $ slow 2 note "c2 f2 g2"
d2 $ note "c3 e3 g3 b3"
d3 $ slow 4 voices:0 1 2 3

-- Effects setup
d4 $ drive:0.35
d5 $ vibrato:0.4
d6 $ feedback:0.65
d7 $ quadhold:0 0.8

-- Silencing all
hush
```

---

## AI Agent Usage for REPL

When generating Orpheus patterns:

1. **Use synth control commands** for ambient/drone sounds (drive, vibrato, feedback, hold)
2. **Layer multiple d-slots** for complex soundscapes
3. **Use slow transformations** for evolving textures
4. **Set hold values** to sustain voices
5. **Use duomod and sharp** for timbral variation
6. **Keep patterns simple** - let the synth parameters add complexity
