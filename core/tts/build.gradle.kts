plugins {
    id("orpheus.kmp.library")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.tts"
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
    }
}
