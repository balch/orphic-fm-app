import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.library")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.mediapipe"

        // publish=true is REQUIRED despite its name: without it a KMP android library
        // exports no consumer rules at all, even to same-build project consumers. See the
        // same block in core/ai.
        @Suppress("UnstableApiUsage")
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.files(project.file("consumer-rules.pro"))
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:gestures"))
            implementation(project(":core:foundation"))
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.mediapipe.tasks.vision)
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.androidx.lifecycle.process)
        }
        jvmMain.dependencies {
            implementation(libs.javacv.platform)
        }
    }
}

// The wasm test bundle imports skiko.mjs (Compose reaches it through :core:foundation), but only
// the Compose convention copies that file in, so Karma's webpack fails and Chrome sits silent
// until it times out. commonTest still runs on JVM, Android and iOS; re-enable if this module
// adopts orpheus.kmp.compose.
tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach { enabled = false }
