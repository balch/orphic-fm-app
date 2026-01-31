plugins {
    id("orpheus.kmp.library")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.plugins.distortion"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":foundation:audio"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
