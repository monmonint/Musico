plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.musico"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.musico"
        // AudioPlaybackCapture et l'encodeur Opus de MediaCodec : Android 10+
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Génération du QR code (pure Java, sans dépendance réseau)
    implementation("com.google.zxing:core:3.5.3")
    implementation(project(":core"))
}
