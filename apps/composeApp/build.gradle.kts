import com.codingfeline.buildkonfig.compiler.FieldSpec
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
    alias(libs.plugins.buildkonfig)
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
            implementation(project(":core:plugins:delay"))
            implementation(project(":core:plugins:distortion"))
            implementation(project(":core:plugins:resonator"))
            implementation(project(":core:plugins:bender"))
            implementation(project(":core:plugins:stereo"))
            implementation(project(":core:plugins:vibrato"))
            implementation(project(":core:plugins:warps"))
            implementation(project(":core:plugins:grains"))
            implementation(project(":core:plugins:drum"))
            implementation(project(":core:plugins:duolfo"))
            implementation(project(":core:plugins:flux"))
            implementation(project(":core:plugins:looper"))
            implementation(project(":core:plugins:perstringbender"))
            implementation(project(":ui:theme"))
            implementation(project(":ui:widgets"))
            implementation(project(":features:warps"))
            implementation(project(":features:drum"))
            implementation(project(":features:flux"))
            implementation(project(":features:grains"))
            implementation(project(":features:resonator"))
            implementation(project(":features:lfo"))
            implementation(project(":features:delay"))
            implementation(project(":features:distortion"))
            implementation(project(":features:looper"))
            implementation(project(":features:beats"))
            implementation(project(":features:draw"))
            implementation(project(":features:evo"))
            implementation(project(":features:tidal"))
            implementation(project(":features:visualizations"))
            implementation(project(":features:ai"))
            implementation(project(":features:debug"))
            implementation(project(":features:midi"))
            implementation(project(":features:presets"))
            implementation(project(":features:tweaks"))
            implementation(project(":features:voice"))
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
buildkonfig {
    packageName = "org.balch.orpheus"

    defaultConfigs {
        val geminiKey = "GEMINI_API_KEY"
        val geminiApiKey = localProperties.getProperty(geminiKey) ?: ""
        buildConfigField(FieldSpec.Type.STRING, geminiKey, geminiApiKey)

        val anthropicKey = "ANTHROPIC_API_KEY"
        val anthropicApiKey = localProperties.getProperty(anthropicKey) ?: ""
        buildConfigField(FieldSpec.Type.STRING, anthropicKey, anthropicApiKey)
    }
}
