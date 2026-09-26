---
name: scope-ring-real-audio-tuning
description: How the transport ring's oscilloscope was tuned on real engine audio (Task 24B, 2026-09-24) - capture recipe, what real scope windows look like, why 40ms and the held-shape rule
metadata:
  type: reference
---

Capture recipe (tools lived in the session scratchpad, never committed):
- Kotlin: a throwaway jvmTest in a PRIVATE detached worktree (`git worktree add --detach`, copy
  local.properties) drives `PulsarViewModel` with a recording `SynthController`, Mode One on, then
  sets `SongEndingStubSynthEngine.pulsarArrangementStateFlow` to each section to dump `#section`
  re-push rows. 4 vibes in ~1 min.
- C++: a standalone tool linked to `liborpheus_dsp/build-desktop/liborpheus_dsp.a` (flags from
  compile_commands.json, incl. `-DORPHEUS_TESTING`): load dj_graph.odwg, replay ports, seed
  `stmlib::Random` from the `seed` row, `pulsar_playing=1`, render 256-frame blocks, call
  `orpheus_engine_get_scope` every 768 frames; track levels = newest sample of viz channels 29..36.
  150 s of audio renders in ~5 s.
- Filmstrips: a jvmTest harness replays records into a fake `ScopeFrame` via `LocalScopeFeed`,
  one record per 16ms scene frame, so the real frame loop, gain and smoothing run.

What real windows showed (DJ graph, master 0.7): window peaks are low (DogHouse p50 0.03, p90 0.12);
DogHouse triggers only 40-56% of polls, Mellow Haze ~70%, Deep Space ~88%. Untriggered polls are
often the loudest ones: a kick or snare onset has not repeated yet, so the correlation gate rejects it.
Holding the stale shape there hid the hits; scaling the held shape by the poll's loudness lets hits
swell the ring without drawing free-running squiggles.

Window: 25ms reads as calm blobs, 60ms as dense teeth at 64dp; 40ms (Part A's default) kept.
Related: [[frame-budget-measurement-lessons]], [[dome-cues-and-shared-worktree-testing]]
