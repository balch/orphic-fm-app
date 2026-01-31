# Macrobenchmark Module for Orphic-FM

This module contains macrobenchmarks for measuring app performance.

## Overview

Macrobenchmarks test app performance from an external perspective (outside the app's process), providing accurate measurements of real-world user experience including:

- **Startup time** (cold, warm, and hot)
- **Frame timing** (UI jank detection)
- **Custom traces** (app-specific operations)

## Prerequisites

- A physical Android device (emulator works but results may be less accurate)
- Android 6.0 (API 23) or higher for basic benchmarks
- Android 7.0 (API 24) or higher for compilation mode features

## Running Benchmarks

### From Android Studio

1. Open the **Build Variants** panel
2. Select `benchmark` for both `:composeApp` and `:macrobenchmark`
3. Run the benchmark tests using the gutter icons next to test methods

### From Command Line

**Run all benchmarks:**
```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

**Run startup benchmarks only:**
```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.class=org.balch.orpheus.macrobenchmark.StartupBenchmark
```

**Run a specific test:**
```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.class=org.balch.orpheus.macrobenchmark.StartupBenchmark#startupCold
```

## Benchmark Results (Baseline - Jan 2026)

The following results were measured on an Android Emulator (API 36).

### Startup Performance
| Mode | TTID (Median) | TTFD (Median) |
|------|---------------|---------------|
| Cold | ~394ms        | ~423ms        |
| Warm | ~56ms         | ~96ms         |

*   **TTID (Time To Initial Display):** Time until the first frame is rendered.
*   **TTFD (Time To Full Display):** Time until `reportFullyDrawn()` is called (in this app, after the first Compose composition).

### UI Smoothness (Frame Timing)
*   **Median Frame Duration:** ~5.2ms
*   **P90 Frame Duration:** ~9.3ms
*   **P95 Frame Duration:** ~18.2ms (Occasional jank)
*   **Frame Overrun:** Mostly negative (well within the 16.6ms budget).

## Troubleshooting

### "Unable to confirm activity launch completion"
This is often caused by a known bug in Macrobenchmark versions prior to 1.4.0 (Issue 313968931). Ensure you are using `androidx-benchmark-macro = "1.4.1"` or later in `libs.versions.toml`.

### Metrics missing for Hot Start
Hot starts can sometimes fail to report metrics if the OS brings the activity to front too quickly for the tracer to catch the transition or if the system doesn't log a new launch event. Focus on Cold and Warm starts for reliable baseline measurements.

**Run frame timing benchmarks:**
```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.class=org.balch.orpheus.macrobenchmark.FrameTimingBenchmark
```

## Available Benchmarks

### StartupBenchmark

Measures app startup time under different conditions:

- **`startupCold`**: Complete cold start (process not running)
- **`startupWarm`**: Warm start (activity recreated, process alive)
- **`startupHot`**: Hot start (activity brought to foreground)

### FrameTimingBenchmark

Measures UI rendering performance:

- **`frameTimingInteraction`**: Captures frame timing during UI scrolling/interaction

## Viewing Results

Benchmark results are output in the Android Studio console and saved to:
```
macrobenchmark/build/outputs/connected_android_test_additional_output/
```

Look for JSON files with benchmark results containing:
- Median, min, max values
- Standard deviation
- Trace file links for detailed analysis

## Adding Custom Benchmarks

1. Create a new test class in `macrobenchmark/src/androidMain/kotlin/`
2. Annotate with `@RunWith(AndroidJUnit4::class)`
3. Use `MacrobenchmarkRule` and `measureRepeated`

Example for measuring a specific operation:
```kotlin
@Test
fun customOperation() = benchmarkRule.measureRepeated(
    packageName = PACKAGE_NAME,
    metrics = listOf(TraceSectionMetric("MyCustomTrace")),
    iterations = 5,
) {
    startActivityAndWait()
    // Perform operations to measure
}
```

## Configuration

The benchmark module is configured to:
- Suppress emulator warnings (for CI compatibility)
- Use debug signing for both app and benchmark APKs
- Inherit from release build type for realistic performance

## Resources

- [Macrobenchmark Overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Benchmark Metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [Benchmarking in CI](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)
