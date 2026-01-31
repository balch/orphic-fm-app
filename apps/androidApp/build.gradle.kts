plugins {
    alias(libs.plugins.androidApplication)
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

android {
    namespace = "org.balch.orpheus"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.balch.orpheus"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(projects.apps.composeApp)
    implementation(project(":foundation:plugins:delay"))
    implementation(project(":foundation:plugins:distortion"))
    implementation(project(":foundation:plugins:resonator"))
    implementation(project(":foundation:plugins:bender"))
    implementation(project(":foundation:plugins:stereo"))
    implementation(project(":foundation:plugins:vibrato"))
    implementation(project(":foundation:plugins:warps"))
    implementation(project(":foundation:plugins:grains"))
    implementation(project(":foundation:plugins:drum"))
    implementation(project(":foundation:plugins:duolfo"))
    implementation(project(":foundation:plugins:flux"))
    implementation(project(":foundation:plugins:looper"))
    implementation(project(":foundation:plugins:perstringbender"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media)
    implementation(libs.kmlogging)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
