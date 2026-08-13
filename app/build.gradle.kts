plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dexprobe.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dexprobe.app"
        // The phone is the capture side; 29 covers MediaProjection with a
        // foreground service of type mediaProjection.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-probe"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
