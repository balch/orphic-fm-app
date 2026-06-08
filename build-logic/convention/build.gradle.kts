import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
plugins {
    `kotlin-dsl`
}

dependencies {
    // Use explicit coordinates - versions from libs.versions.toml
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.gradle)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    // Metro for orpheus.kmp.library convention plugin
    implementation(libs.dev.zacsweers.metro.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
}

// Target JVM 21: the metro gradle-plugin only publishes Java-21 variants, and the project runs
// Gradle on JDK 21. Java + Kotlin targets must match or Gradle fails with
// "Inconsistent JVM Target Compatibility".
kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
