plugins {
    id("com.android.application")
}

android {
    namespace = "ai.cocog.mic"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.cocog.mic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
