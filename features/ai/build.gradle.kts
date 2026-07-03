import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.ai"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // Slim agent-core + vibe tools ONLY. The Orpheus-only tools, VMs and panels (which
            // dragged in tts / drum / warps / flux / voice / presets / plugins / markdown) live in
            // :features:ai-orpheus so the lean DJ AI edition never pulls them onto its classpath.
            implementation(project(":core:ai"))
            // api (not implementation): ReplCodeEventBus appears in OrpheusAgent's PUBLIC
            // constructor, so any DI graph that builds OrpheusAgent (incl. the DJ AI edition, whose
            // graph is generated in its entry module) must see :core:tidal on its compile classpath
            // to generate the @Inject factory. Only ReplCodeEventBus (a lightweight event bus) is
            // reachable here — the heavy TidalRepl's consumers moved to :features:ai-orpheus.
            api(project(":core:tidal"))
            implementation(project(":features:pulsar")) // vibe tools read/apply Pulsar vibes

            // AI/koog
            api(libs.koog.agents)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
