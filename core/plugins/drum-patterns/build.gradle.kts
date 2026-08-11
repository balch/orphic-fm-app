plugins {
    id("orpheus.kmp.library")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.plugins.drumpatterns"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // Deliberately no dependencies. Both sources are self-contained, and keeping them that
    // way is what lets the commercial apps drop this module wholesale.
}
