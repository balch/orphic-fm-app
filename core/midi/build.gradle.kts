plugins {
    id("orpheus.kmp.library")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.midi"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:foundation"))

            // MIDI library
            implementation(libs.ktmidi)

            // Serialization (MidiMappingState is @Serializable)
            implementation(libs.kotlinx.serialization.json)

            // Coroutines (MidiController, MidiMappingStateHolder)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktmidi.jvm.desktop)
            implementation(libs.coremidi4j)
        }
        jvmTest.dependencies {
            implementation(project(":core:plugins:bass"))
            implementation(project(":core:plugins:beats"))
            implementation(project(":core:plugins:cowbell"))
            implementation(project(":core:plugins:dj"))
            implementation(project(":core:plugins:drum"))
            implementation(project(":core:plugins:duolfo"))
            implementation(project(":core:plugins:flux"))
            implementation(project(":core:plugins:grains"))
            implementation(project(":core:plugins:resonator"))
            implementation(project(":core:plugins:stereo"))
            implementation(project(":core:plugins:tides"))
            implementation(project(":core:plugins:voice"))
            implementation(project(":core:plugins:warps"))
        }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
        }
    }
}

// The wasm test bundle imports skiko.mjs (Compose reaches it through :core:foundation), but only
// the Compose convention copies that file in, so Karma's webpack fails and Chrome sits silent
// until it times out. commonTest still runs on JVM, Android and iOS; re-enable if this module
// adopts orpheus.kmp.compose.
tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach { enabled = false }

// PanelLearnIdGuardTest reads panels in other modules, so a change there must re-run it.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("features/**/*.kt", "apps/**/*.kt")
            exclude("**/build/**", "**/bin/**", "**/.claude/**")
        }
    )
        .withPropertyName("panelLearnIdSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
