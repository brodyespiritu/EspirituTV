plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.espiritu.tv"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.espiritu.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            // Emulator test build: reads the update feed from the Mac instead of GitHub (README › Updates).
            buildConfigField("String", "FEED_URL", "\"http://10.0.2.2:8000/latest.json\"")
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            buildConfigField("String", "FEED_URL", "\"\"") // empty → Updater.FEED_URL (GitHub Releases)
            manifestPlaceholders["cleartext"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the local debug key so the APK installs directly via ADB.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
