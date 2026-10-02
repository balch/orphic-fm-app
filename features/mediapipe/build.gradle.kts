import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.mediapipe"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.gestures)
            implementation(projects.core.mediapipe)
            implementation(projects.core.pluginApi)
            implementation(projects.ui.theme)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
