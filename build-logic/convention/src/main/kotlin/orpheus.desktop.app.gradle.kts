import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention plugin for Compose Desktop application modules (plain Kotlin/JVM).
 * These are thin launcher modules that depend on an :apps:<app>:shared KMP library.
 *
 * Metro is applied because the launcher's main() calls
 * createGraphFactory<…Graph.Factory>() — a Metro compiler intrinsic processed at
 * the call site — even though the module declares no @DependencyGraph itself.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.zacsweers.metro")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// libremidi-panama (transitive via ktmidi-jvm-desktop) targets JVM 22+ (Panama FFI),
// but desktop modules build/run on the JVM 21 toolchain. Exclude it — mirrors the
// test-config exclusion in orpheus.kmp.library/orpheus.kmp.compose. Desktop MIDI uses
// coremidi4j (macOS) / ktmidi's other JVM backends.
configurations.all {
    exclude(group = "dev.atsushieno", module = "libremidi-panama")
}
