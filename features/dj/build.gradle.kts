plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.dj"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.pluginApi)
            implementation(projects.core.audio)
            implementation(projects.ui.widgets)
            implementation(projects.ui.theme)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
