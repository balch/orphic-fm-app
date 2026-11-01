import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.voice"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.pluginApi)
            implementation(project(":features:midi"))
            implementation(project(":features:drum"))
        }
        jvmTest.dependencies {
            // runComposeUiTest for the duo's locked harmonics knob and patch picks.
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}
