plugins {
    id("orpheus.kmp.library")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.plugins.reverb"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:audio"))
        }
        androidMain.dependencies {
            implementation(libs.jsyn)
        }
        jvmMain.dependencies {
            implementation(libs.jsyn)
        }
    }
}
