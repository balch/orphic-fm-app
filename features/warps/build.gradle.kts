plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.features.warps"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.audio)
            implementation(projects.core.foundation)
            implementation(projects.core.plugins.warps)
            implementation(projects.ui.widgets)
            implementation(projects.ui.theme)
        }
    }
}
