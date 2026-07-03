plugins {
    id("orpheus.kmp.compose")
}

kotlin {
    android {
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

            implementation(libs.compose.material.icons)

            implementation(libs.liquid)
            implementation(libs.compose.ui.tooling.preview)
        }

        androidMain.dependencies {
            // WindowCompat / WindowInsetsControllerCompat for re-hiding system bars on
            // the ModalBottomSheet's own window (ImmersiveSheetEffect).
            implementation(libs.androidx.core.ktx)
        }
    }
}
