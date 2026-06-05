plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.tides"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.pluginApi)
            implementation(projects.core.audio)
            implementation(projects.core.plugins.tides)
            implementation(projects.ui.widgets)
            implementation(projects.ui.theme)
        }
    }
}
