import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "songe.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.jsyn)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.haze)
            implementation(libs.haze.materials)
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktmidi)
            implementation(libs.metrox.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.jsyn)
            implementation(libs.ktmidi.jvm.desktop)
            implementation(libs.coremidi4j)
        }
        wasmJsMain.dependencies {
            // ktmidi provides WebMidiAccess for browser MIDI
            // Web Audio API used directly via Kotlin/JS interop
        }
    }
}

// Exclude libremidi-panama from test configurations (requires JVM 22+, we use JVM 21)
configurations.matching { it.name.contains("test", ignoreCase = true) }.all {
    exclude(group = "dev.atsushieno", module = "libremidi-panama")
}

android {
    namespace = "org.balch.songe"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.balch.songe"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.balch.songe.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.balch.songe"
            packageVersion = "1.0.0"
        }
    }
}
