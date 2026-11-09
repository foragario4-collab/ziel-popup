plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "at.agar.zielpopup"
    compileSdk = 34

    defaultConfig {
        applicationId = "at.agar.zielpopup"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "2.3"
    }

    // Fester Schlüssel, damit neue Versionen einfach drüber installiert werden können
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
