import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

// ── C++ header inputs for PulsarSectionLimitsTest's mirror-parity guards ──────
// The guards regex-parse these headers' text for specific constants (kMaxSections,
// kSectionDataFields, kMaxLickPool, HalfLickMode ordinals) — not a Kotlin source
// change, so without this a header-only edit leaves jvmTest falsely up-to-date and
// the guards silently never re-run.
tasks.named<Test>("jvmTest") {
    inputs.files(
        file("../../liborpheus_dsp/src/pulsar_limits.h"),
        file("../../liborpheus_dsp/src/orpheus_engine.h"),
        file("../../liborpheus_dsp/src/orpheus_unit_pulsar.h"),
        file("../../liborpheus_dsp/src/pulsar_score_clock.h"),
    )
        .withPropertyName("cppSectionLimitsHeaders")
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
        .optional()
}

// ── MIDI score inspection (score-player tooling) ──────────────────────────────
// MidiScoreImporter lives in jvmTest — javax.sound.midi is JVM-only and free on the
// test classpath, and this tool never ships in the app. `inspectScore` reruns just
// the report test with a caller-supplied MIDI path, mirroring exportPresets above.
//
//   ./gradlew :features:pulsar:inspectScore -Pmidi=/path/to/score.mid
tasks.register<Test>("inspectScore") {
    description = "Print a per-part summary of a MIDI file for score mapping"
    group = "verification"
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs
    classpath = tasks.named<Test>("jvmTest").get().classpath
    filter { includeTestsMatching("*InspectScoreReportTest*") }
    systemProperty("orpheus.score.midi", providers.gradleProperty("midi").getOrElse(""))
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

// ── Generated JSON score assets (score-player tooling) ────────────────────────
// `composeResources/files/scores/*.json` is generated from `scores/source/` (a MIDI
// file plus a same-named ScoreMapping JSON) and checked in for the app to load at
// runtime via NotatedScoreProvider — the raw MIDI itself never ships. A plain `jvmTest`
// only verifies the generated JSON, mirroring exportPresets in
// features/presets/build.gradle.kts. Both scores/source/ and the generated JSON live
// outside any tracked Kotlin source set, so both are declared as jvmTest inputs —
// otherwise a hand-edit to either could leave the guard falsely up-to-date.
//
//   ./gradlew :features:pulsar:importScore
//   ./gradlew jvmTest -Pexport-fixtures
val scoreWriteProperty = "orpheus.score.write"
val scoreSourceFiles = fileTree("../../scores/source")
val scoreFixtures = fileTree("src/commonMain/composeResources/files/scores") { include("*.json") }
val exportScoreFixtures = providers.gradleProperty("export-fixtures").isPresent

tasks.register<Test>("importScore") {
    description = "Regenerate checked-in notated-score JSON assets from scores/source/"
    group = "verification"
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs
    classpath = tasks.named<Test>("jvmTest").get().classpath
    filter { includeTestsMatching("*ImportScoreFixtureTest*") }
    systemProperty(scoreWriteProperty, "true")
    outputs.upToDateWhen { false }
}

tasks.named<Test>("jvmTest") {
    if (exportScoreFixtures) {
        systemProperty(scoreWriteProperty, "true")
        outputs.upToDateWhen { false }
    } else {
        // Re-run verification when scores/source/ or a checked-in asset is edited by
        // hand, otherwise Gradle's up-to-date check would skip the guard and let
        // drift through.
        inputs.files(scoreSourceFiles, scoreFixtures)
            .withPropertyName("notatedScoreFixtures")
            .withPathSensitivity(PathSensitivity.NAME_ONLY)
            .optional()
    }
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.pulsar"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.audio)
            implementation(projects.core.pluginApi)
            implementation(libs.kotlinx.datetime)
        }
        // kotlin("test") comes from the orpheus.kmp.compose convention plugin.
        // coroutines-test must live here, not jvmTest: commonTest is compiled by
        // every target (wasmJs, iOS, androidHostTest), and deps only flow downward.
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
