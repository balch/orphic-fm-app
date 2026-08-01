plugins {
    id("orpheus.kmp.library")
}

// ── Generated ODWG graph binaries for the C++ test harness ────────────────────
// `default_graph.odwg` is checked in, so a plain `jvmTest` only verifies it and
// never writes to the working tree. (`dj_graph.odwg` is untracked and always
// regenerated — see ExportDjAppOdwgTest.)
//
// Regenerate deliberately:
//   ./gradlew :core:dsp-engine:exportOdwg
//   ./gradlew jvmTest -Pexport-fixtures
val fixtureWriteProperty = "orpheus.fixtures.write"
val defaultGraphFixture = file("../../liborpheus_dsp/test/data/default_graph.odwg")
val exportFixtures = providers.gradleProperty("export-fixtures").isPresent

tasks.register<Test>("exportOdwg") {
    description = "Export wiring graph binaries (ODWG) for C++ tests"
    group = "verification"
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs
    classpath = tasks.named<Test>("jvmTest").get().classpath
    filter {
        includeTestsMatching("*ExportOdwgTest*")
        includeTestsMatching("*ExportDjAppOdwgTest*")
    }
    systemProperty(fixtureWriteProperty, "true")
    outputs.upToDateWhen { false }
}

tasks.named<Test>("jvmTest") {
    if (exportFixtures) {
        systemProperty(fixtureWriteProperty, "true")
        outputs.upToDateWhen { false }
    } else {
        // Re-run verification when the checked-in graph is edited by hand, otherwise
        // Gradle's up-to-date check would skip the guard and let drift through.
        inputs.files(defaultGraphFixture)
            .withPropertyName("cppGraphFixture")
            .withPathSensitivity(PathSensitivity.NAME_ONLY)
            .optional()
    }
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.dsp.engine"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.audio)
            api(projects.core.foundation)
            api(projects.core.tts)
            api(projects.core.plugins.beats)
            api(projects.core.plugins.voice)
            api(projects.core.plugins.delay)
            api(projects.core.plugins.distortion)
            api(projects.core.plugins.resonator)
            api(projects.core.plugins.reverb)
            api(projects.core.plugins.bender)
            api(projects.core.plugins.stereo)
            api(projects.core.plugins.vibrato)
            api(projects.core.plugins.warps)
            api(projects.core.plugins.grains)
            api(projects.core.plugins.drum)
            api(projects.core.plugins.plaits)
            api(projects.core.plugins.duolfo)
            api(projects.core.plugins.flux)
            api(projects.core.plugins.looper)
            api(projects.core.plugins.perstringbender)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.atomicfu)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
