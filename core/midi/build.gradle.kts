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
        jvmMain.dependencies {
            implementation(libs.ktmidi.jvm.desktop)
            implementation(libs.coremidi4j)
        }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
        }
    }
}
