plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.features.mediapipe"
    }

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
