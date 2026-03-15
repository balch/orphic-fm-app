plugins {
    id("orpheus.kmp.library")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.plugins.drum"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:plugins:plaits"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
