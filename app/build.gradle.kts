plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ralph.applemusictv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ralph.applemusictv"
        minSdk = 25
        targetSdk = 34
        versionCode = 19
        versionName = "0.10.2"
    }

    // Release signing comes from ~/.gradle/gradle.properties (AMTV_STORE_FILE, AMTV_STORE_PASSWORD, AMTV_KEY_ALIAS,
    // AMTV_KEY_PASSWORD), never from this repository. Without them, release builds are left unsigned.
    signingConfigs {
        val store = project.findProperty("AMTV_STORE_FILE") as String?
        if (store != null) {
            create("release") {
                storeFile = file(store)
                storePassword = project.findProperty("AMTV_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("AMTV_KEY_ALIAS") as String?
                keyPassword = project.findProperty("AMTV_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

dependencies {
    // per-frame script injection + messaging (lets text entry work inside Apple's sign-in iframe)
    implementation("androidx.webkit:webkit:1.11.0")
}
