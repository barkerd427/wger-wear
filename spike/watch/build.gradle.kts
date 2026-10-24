// THROWAWAY: proves the watch-side libraries compile together on the wger app's toolchain.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "de.wger.spike.watch"
    compileSdk = 37 // forced by powersync 1.15.x AAR metadata (minCompileSdk 37)

    defaultConfig {
        applicationId = "de.wger.spike" // long term: de.wger.flutter, signed with the wger key
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0-spike"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.health.services)
    implementation(libs.wear.phone.interactions)
    implementation(libs.coroutines.guava)
}
