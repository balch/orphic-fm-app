
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.FileInputStream
import java.util.Properties

// Load local.properties for API keys
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

plugins {
    id("orpheus.kmp.compose")
    id("org.jetbrains.compose.hot-reload")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)

}

kotlin {
    // Override namespace for this specific module
    androidLibrary {
        namespace = "org.balch.orpheus.shared"
    }

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "orpheus.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.jsyn)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.media)
            implementation(libs.androidx.profileinstaller)
        }
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:foundation"))
            api(project(":core:plugins:delay"))
            api(project(":core:plugins:distortion"))
            api(project(":core:plugins:resonator"))
            api(project(":core:plugins:bender"))
            api(project(":core:plugins:stereo"))
            api(project(":core:plugins:vibrato"))
            api(project(":core:plugins:warps"))
            api(project(":core:plugins:grains"))
            api(project(":core:plugins:drum"))
            api(project(":core:plugins:duolfo"))
            api(project(":core:plugins:flux"))
            api(project(":core:plugins:looper"))
            api(project(":core:plugins:perstringbender"))
            api(project(":ui:theme"))
            api(project(":ui:widgets"))
            api(project(":features:warps"))
            api(project(":features:drum"))
            api(project(":features:flux"))
            api(project(":features:grains"))
            api(project(":features:resonator"))
            api(project(":features:lfo"))
            api(project(":features:delay"))
            api(project(":features:distortion"))
            api(project(":features:looper"))
            api(project(":features:beats"))
            api(project(":features:draw"))
            api(project(":features:evo"))
            api(project(":features:tidal"))
            api(project(":features:visualizations"))
            api(project(":features:ai"))
            api(project(":features:debug"))
            api(project(":features:midi"))
            api(project(":features:presets"))
            api(project(":features:tweaks"))
            api(project(":features:voice"))
            implementation(libs.compose.material.icons)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.liquid)
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
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
            implementation(libs.ktor.client.okhttp)
            implementation(libs.slf4j.api)
            implementation(libs.logback.classic)
        }
        wasmJsMain.dependencies {
            // ktmidi provides WebMidiAccess for browser MIDI
            // Web Audio API used directly via Kotlin/JS interop
        }
    }
}

// For Compose previews with AGP 9.0 and the android KMP library plugin
dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.desktop {
    application {
        mainClass = "org.balch.orpheus.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Orphic-FM"
            packageVersion = "1.0.0"

            macOS {
                iconFile.set(project.file("src/jvmMain/resources/icon.icns"))
                dockName = "Orphic-FM"
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
            }
        }
    }
}

// BuildKonfig configuration for cross-platform BuildConfig
