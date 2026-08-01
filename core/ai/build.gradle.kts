import com.codingfeline.buildkonfig.compiler.FieldSpec

/**
 * API keys resolve from any Gradle property source, then the matching environment variable
 * (for CI). In practice that means ~/.gradle/gradle.properties, which is the intended home:
 * it sits outside every checkout, so a key there cannot ride along in a diff or a zipped
 * working copy. Deliberately NOT read from local.properties, which is routinely opened to
 * diagnose sdk.dir / org.gradle.java.home problems and leaks whatever is stored beside them.
 *
 * Note this is a convention, not an enforced boundary: providers.gradleProperty also reads
 * the repo-root gradle.properties, which IS tracked. Putting a key there would be worse than
 * local.properties, not better. Keep them in the user-level file.
 *
 * A missing key yields "", which leaves that provider unavailable at runtime rather than
 * failing the build, so contributors without keys can still build the app.
 */
fun apiKey(name: String): String {
    val value = providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: ""
    if (value.isEmpty()) {
        logger.warn("$name not set — add it to ~/.gradle/gradle.properties to enable that AI provider.")
    }
    return value
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
        buildConfigField(FieldSpec.Type.STRING, geminiKey, apiKey(geminiKey))

        val anthropicKey = "ANTHROPIC_API_KEY"
        buildConfigField(FieldSpec.Type.STRING, anthropicKey, apiKey(anthropicKey))
    }
}
