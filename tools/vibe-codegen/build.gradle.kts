plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

application {
    mainClass.set("org.balch.orpheus.tools.vibecodegen.MainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.features.pulsar)
    implementation(projects.features.ai)
    implementation(projects.core.audio)
    implementation(projects.core.foundation)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.metro.runtime)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}
