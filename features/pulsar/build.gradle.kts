import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.pulsar"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.audio)
            implementation(projects.core.pluginApi)
            implementation(libs.kotlinx.datetime)
        }
        // kotlin("test") comes from the orpheus.kmp.compose convention plugin.
        // coroutines-test must live here, not jvmTest: commonTest is compiled by
        // every target (wasmJs, iOS, androidHostTest), and deps only flow downward.
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
