
plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.metro)

}

kotlin {
    // Override namespace for this specific module
    android {
        namespace = "org.balch.orpheus.shared"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "OrpheusShared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.media3.session)
            implementation(libs.androidx.profileinstaller)
        }
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:dsp-engine"))
            api(project(":core:features"))
            api(project(":core:foundation"))
            api(project(":core:midi"))
            api(project(":core:ai"))
            api(project(":core:tidal"))
            api(project(":core:tts"))
            api(project(":core:plugins:beats"))
            api(project(":core:plugins:voice"))
            api(project(":core:plugins:delay"))
            api(project(":core:plugins:distortion"))
            api(project(":core:plugins:horn"))
            api(project(":core:plugins:resonator"))
            api(project(":core:plugins:reverb"))
            api(project(":core:plugins:bender"))
            api(project(":core:plugins:stereo"))
            api(project(":core:plugins:vibrato"))
            api(project(":core:plugins:warps"))
            api(project(":core:plugins:grains"))
            api(project(":core:plugins:drum"))
            api(project(":core:plugins:plaits"))
            api(project(":core:plugins:duolfo"))
            api(project(":core:plugins:flux"))
            api(project(":core:plugins:looper"))
            api(project(":core:plugins:perstringbender"))
            api(project(":core:plugins:bass"))
            api(project(":core:plugins:dj"))
            api(project(":core:plugins:tides"))
            api(project(":core:plugins:pulsar"))
            api(project(":ui:panels"))
            api(project(":ui:theme"))
            api(project(":ui:widgets"))
            api(project(":features:warps"))
            api(project(":features:drum"))
            api(project(":features:flux"))
            api(project(":features:grains"))
            api(project(":features:horn"))
            api(project(":features:resonator"))
            api(project(":features:reverb"))
            api(project(":features:lfo"))
            api(project(":features:delay"))
            api(project(":features:distortion"))
            api(project(":features:looper"))
            api(project(":features:beats"))
            api(project(":features:draw"))
            api(project(":features:evo"))
            api(project(":features:tidal"))
            api(project(":features:speech"))
            api(project(":features:visualizations"))
            api(project(":features:ai"))
            api(project(":features:debug"))
            api(project(":features:midi"))
            api(project(":features:presets"))
            api(project(":features:tweaks"))
            api(project(":features:voice"))
            api(project(":features:mediapipe"))
            api(project(":features:bass"))
            api(project(":features:dj"))
            api(project(":features:tides"))
            api(project(":features:pulsar"))
            api(project(":features:timer"))
            api(project(":core:mediapipe"))
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
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
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
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// For Compose previews with AGP 9.0 and the android KMP library plugin
dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
