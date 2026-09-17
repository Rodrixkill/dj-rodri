import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The client ID lives in local.properties (gitignored), never in the repo.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.rvillarroel.djrodri"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rvillarroel.djrodri"
        // Android 16 only: the app is for one phone, so there is no reason to carry old-API branches.
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProperties.getProperty("SPOTIFY_CLIENT_ID", "")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"djrodri://callback\"")

        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core:domain"))

    // App Remote is only published as an AAR on GitHub releases (v0.8.0, the last one); it needs Gson at runtime.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)
    implementation(libs.spotify.auth)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
}
