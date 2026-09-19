plugins {
    id("orpheus.kmp.library")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.tts"

        // Only module with device tests: the platform TTS engines can only be exercised on a
        // real Android runtime, and WavDecoder's real input is whatever the engine writes.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // JvmTtsGenerator uses DispatcherProvider from foundation
            implementation(project(":core:foundation"))

            // Coroutines (SharedFlow in SpeechEventBus, withContext in JvmTtsGenerator)
            implementation(libs.kotlinx.coroutines.core)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
