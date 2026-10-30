plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.qrcodeterminal"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.qrcodeterminal"
        minSdk = 29
        targetSdk = 35
        versionCode = 27
        versionName = "2.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
