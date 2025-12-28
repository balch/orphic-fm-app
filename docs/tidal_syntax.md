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
