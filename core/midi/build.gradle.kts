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
