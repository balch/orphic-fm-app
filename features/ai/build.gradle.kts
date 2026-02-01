import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.features.ai"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":features:visualizations")) // For LiquidPreview if needed
            implementation(project(":features:drum")) // For DrumsTool
            implementation(project(":features:warps")) // For SynthControlTool?
            implementation(project(":features:flux")) // For SynthControlTool?
            implementation(project(":features:voice"))
            implementation(libs.liquid)

            // AI/koog
            implementation(libs.koog.agents)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            // Markdown rendering (core + Material 3 theme)
            implementation(libs.markdown)
            implementation(libs.markdown.m3)
        }
    }
}
