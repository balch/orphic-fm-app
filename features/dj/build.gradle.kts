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
        // coroutines-test must live in commonTest, not jvmTest: commonTest is compiled by
        // every target and deps only flow downward. Mirrors features/pulsar.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
