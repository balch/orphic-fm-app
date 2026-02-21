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
    androidLibrary {
        namespace = "org.balch.orpheus.core.ai"
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
