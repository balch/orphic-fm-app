import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.ai.orpheus"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // The slim agent core + vibe tools + agent data model live in :features:ai.
            // This module holds the Orpheus-only AI surface (heavy tools, chat/generative UI,
            // AiOptions/Chat/VibeCreate panels) so the lean DJ AI edition never pulls them in.
            api(project(":features:ai"))
            implementation(project(":core:ai"))
            implementation(project(":core:tts"))
            implementation(project(":core:tidal"))
            implementation(project(":features:visualizations"))
            implementation(project(":features:drum"))
            implementation(project(":features:warps"))
            implementation(project(":features:flux"))
            implementation(project(":features:voice"))
            implementation(project(":features:presets"))
            implementation(project(":features:pulsar"))
            implementation(project(":core:plugins:delay"))
            implementation(project(":core:plugins:duolfo"))
            implementation(project(":core:plugins:distortion"))
            implementation(project(":ui:panels"))
            implementation(libs.liquid)

            api(libs.koog.agents)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.markdown)
            implementation(libs.markdown.m3)
            implementation(libs.kotlinx.datetime)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
