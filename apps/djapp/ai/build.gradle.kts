plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.djapp.ai"
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DjAppAi"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":apps:djapp:shared"))
            api(project(":features:ai"))
            api(project(":core:ai"))
            implementation(libs.compose.material.icons)
            implementation(libs.kmlogging)
            implementation(libs.metrox.viewmodel)
            implementation(libs.metrox.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
