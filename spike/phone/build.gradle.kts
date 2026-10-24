// THROWAWAY: Health Connect write path, as the wger phone app would own it.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "de.wger.spike.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.wger.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0-spike"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.health.connect)
    testImplementation(libs.junit)
}
