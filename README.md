# Songe-8

An 8-oscillator organismic synthesizer inspired by the [SOMA Lyra-8](https://somasynths.com/lyra-organismic-synthesizer/), built with Kotlin Multiplatform targeting Desktop and Android.

## Overview

Songe-8 features non-linear voice generators (similar to old electric organ tone generators) with a hierarchical modulation structure:

- **8 Voices**: Individual tone generators with tune, pulse, and hold controls
- **4 Duo Groups**: Paired voices with cross-modulation and LFO
- **2 Quad Groups**: Groups of 4 with pitch shift and sustain
- **Global Controls**: Vibrato, distortion, volume, pan, and master drive

## Tech Stack

- **Kotlin 2.3.0** with Kotlin Multiplatform
- **Compose Multiplatform** (Desktop & Android)
- **Metro** for compile-time dependency injection
- **Navigation3** with adaptive layouts
- **Material3** with dark synth theme
- **Haze** for glassmorphism effects
- **JSyn** for audio synthesis (Desktop/JVM)
- **KmLogging** for structured logging

## Project Structure

```
composeApp/
├── src/
│   ├── commonMain/kotlin/org/balch/songe/
│   │   ├── navigation/     # Nav3 routing
│   │   ├── synth/          # Audio engine & state
│   │   └── ui/
│   │       ├── components/ # Reusable controls
│   │       ├── panels/     # Voice & group panels
│   │       ├── screens/    # Full screens
│   │       └── theme/      # Dark synth theme
│   ├── androidMain/        # Android-specific
│   └── jvmMain/            # Desktop-specific (JSyn)
```

## Build & Run

### Desktop (JVM)
```bash
./gradlew :composeApp:run
```

### Android
```bash
./gradlew :composeApp:assembleDebug
```

### Build All
```bash
./gradlew build
```

## Status

🚧 **Work in Progress** - Phase 1 (Foundation) complete

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)