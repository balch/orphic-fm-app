import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

// ── Generated C++ preset fixtures ──────────────────────────────────────────────
// `liborpheus_dsp/test/data/preset_*.h` is generated from the bundled preset JSON
// and checked in for the C++ harness to #include. A plain `jvmTest` only verifies
// it, so a build can never leave spurious diffs in the working tree.
//
// Regenerate deliberately:
//   ./gradlew :features:presets:exportPresets
//   ./gradlew jvmTest -Pexport-fixtures
val fixtureWriteProperty = "orpheus.fixtures.write"
// Globbed rather than listed so adding a preset can't silently skip the drift guard.
val presetFixtures = fileTree("../../liborpheus_dsp/test/data") { include("preset_*.h") }
val exportFixtures = providers.gradleProperty("export-fixtures").isPresent

tasks.register<Test>("exportPresets") {
    description = "Regenerate the checked-in C++ preset fixtures from the bundled preset JSON"
    group = "verification"
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs
    classpath = tasks.named<Test>("jvmTest").get().classpath
    filter { includeTestsMatching("*ExportPresetsTest*") }
    systemProperty(fixtureWriteProperty, "true")
    outputs.upToDateWhen { false }
}

tasks.named<Test>("jvmTest") {
    if (exportFixtures) {
        systemProperty(fixtureWriteProperty, "true")
        outputs.upToDateWhen { false }
    } else {
        // Re-run verification when a checked-in fixture is edited by hand, otherwise
        // Gradle's up-to-date check would skip the guard and let drift through.
        inputs.files(presetFixtures)
            .withPropertyName("cppPresetFixtures")
            .withPathSensitivity(PathSensitivity.NAME_ONLY)
            .optional()
    }
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.presets"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
             implementation(project(":features:visualizations")) // For LiquidPreview
             implementation(libs.kotlinx.datetime)
             implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
