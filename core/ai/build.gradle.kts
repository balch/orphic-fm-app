import com.codingfeline.buildkonfig.compiler.FieldSpec
import java.io.FileInputStream
import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

plugins {
    id("orpheus.kmp.library")
    alias(libs.plugins.buildkonfig)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.ai"
        // Koog ships no R8 consumer rules (JetBrains/koog#1068), so this module — the owner
        // of the Koog dependency — ships them instead. AGP merges consumer-rules.pro into
        // every Android app variant that pulls :core:ai (Orpheus app, djapp ai flavor).
        // publish=true is REQUIRED despite its name: without it the KMP android library
        // exports no consumer rules even to same-build project consumers (verified via the
        // apps' merged R8 configuration.txt — the rules simply never arrived).
        // The whole KmpOptimization DSL is @Incubating in AGP 9.x; accepted deliberately —
        // it is the only way for a KMP android library to ship consumer rules, and a future
        // rename would fail configuration loudly right here.
        @Suppress("UnstableApiUsage")
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.files(project.file("consumer-rules.pro"))
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:foundation"))

            // AI/koog
            api(libs.koog.agents)
            api(libs.koog.google.client)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
        }
    }
}

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
