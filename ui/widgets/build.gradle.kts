plugins {
    id("orpheus.kmp.compose")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.ui.widgets"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    
    jvm()
    
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    sourceSets {
        commonMain.dependencies {
            api(project(":ui:theme"))
            api(project(":core:foundation"))

            implementation(compose.materialIconsExtended)

            implementation(libs.liquid)
            implementation(libs.compose.ui.tooling.preview)
        }
    }
}
