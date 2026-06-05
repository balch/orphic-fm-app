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
