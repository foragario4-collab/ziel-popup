plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber: Int = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0

android {
    namespace = "at.agar.zielpopup"
    compileSdk = 34

    defaultConfig {
        applicationId = "at.agar.zielpopup"
        minSdk = 26
        targetSdk = 34
        // Build-Nummer kommt von GitHub (-PbuildNumber=…) – daran erkennt die App ihre Updates
        versionCode = 1000 + buildNumber
        versionName = "3.1"
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
