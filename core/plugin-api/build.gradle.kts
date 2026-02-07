plugins {
    id("orpheus.kmp.api")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.plugin"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
        }
    }
}
