plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ralph.applemusictv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ralph.applemusictv"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.9.1"
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
