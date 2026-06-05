plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.foundation"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Robolectric host tests for the Android-only AudioFocusController /
        // MediaSessionManager: drives the real OnAudioFocusChangeListener so the
        // focus-event → auto-resume arming (pausedByTransient) and the transient
        // escalation watchdog are covered end-to-end on the JVM.
        //
        // NOTE: these tests pin @Config(sdk = [34]) because Robolectric (see the
        // `robolectric` version in libs.versions.toml) ships no shadow set for
        // this module's compileSdk. If you bump compileSdk or Robolectric, keep
        // the @Config SDK at a level Robolectric actually bundles (and >= minSdk).
        val androidHostTest by getting {
            dependencies {
                implementation(libs.robolectric)
                implementation(libs.junit)
                implementation(libs.kotlin.testJunit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val commonMain by getting {
            dependencies {
                api(project(":core:audio"))

                api(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.coroutines.core)

                // UI & Lifecycle (for SynthFeature and ViewModels)
                implementation(libs.androidx.lifecycle.viewmodel)

                // DI
                api(libs.metro.runtime)
                implementation(libs.metrox.viewmodel)
                implementation(libs.metrox.viewmodel.compose)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.slf4j.api)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.media3.session)
                implementation(libs.androidx.activity)
                implementation(libs.play.app.update)
                implementation(libs.play.app.update.ktx)
            }
        }
        val wasmJsMain by getting {
        }
    }
}
