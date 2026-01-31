import com.codingfeline.buildkonfig.compiler.FieldSpec
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
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.buildkonfig)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.foundation"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core:audio"))
                
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kmlogging)
                
                // UI & Lifecycle (for SynthFeature and ViewModels)
                implementation(libs.androidx.lifecycle.viewmodel)
                
                // DI
                api(libs.metro.runtime)
                implementation(libs.metrox.viewmodel)
                implementation(libs.metrox.viewmodel.compose)

                // MIDI
                implementation(libs.ktmidi)

                // AI/koog
                implementation(libs.koog.agents)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.client.content.negotiation)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktmidi.jvm.desktop)
                implementation(libs.jsyn)
                implementation(libs.slf4j.api)
                implementation(libs.coremidi4j)
            }
        }
        val androidMain by getting {
        }
        val wasmJsMain by getting {
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
