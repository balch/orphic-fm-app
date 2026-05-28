# Perfetto Capture

Wrappers around `adb perfetto` for capturing traces that pair with the
`perfetto-trace-analysis` skill.

## Two profiles

| Profile | What it surfaces | Use when |
| --- | --- | --- |
| `audio.cfg` | Oboe callbacks, audio-thread sched + freq, JNI/C++ stalls, binder hops, lock contention | Audio dropouts, JNI latency, DSP regressions |
| `ui.cfg`    | Compose frames, gfx + view, vsync misses, touch input, surface composition | Knob/key jank, liquid-glass perf, scroll/redraw |

Both pin to `org.balch.orpheus[.debug]` and `org.balch.djapp[.debug]`.

## Capture

```bash
./scripts/perf/capture-trace.sh                       # audio, 10s
./scripts/perf/capture-trace.sh ui                    # UI, 10s
./scripts/perf/capture-trace.sh audio 20              # audio, 20s
./scripts/perf/capture-trace.sh ui 15 djapp           # UI, 15s, label djapp
```

Traces land in `tmp/traces/<profile>-<label>-<timestamp>.perfetto-trace`.

## Analyze

In Claude Code:

> "Analyze tmp/traces/audio-orpheus-20260527-204512.perfetto-trace with the perfetto-trace-analysis skill"

The skill runs queries against the trace via `trace_processor`, follows its
domain-hint references (CPU/Graphics/I/O/IPC/Memory/Power), and writes a
chain-of-evidence scratchpad next to the trace.

Or open the file in https://ui.perfetto.dev for interactive exploration.

## Tip

Capture both profiles in quick succession when a perf issue is hard to
attribute. UI jank often correlates with audio-thread CPU starvation; having
two traces lets you cross-reference timestamps.
