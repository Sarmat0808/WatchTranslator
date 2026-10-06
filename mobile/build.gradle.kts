plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sarmat.perevodchik.phone"
    compileSdk = 35

    defaultConfig {
        // Тот же id, что у часов: так часы и телефон находят друг друга по Bluetooth
        applicationId = "com.sarmat.perevodchik"
        minSdk = 28
        targetSdk = 34
        versionCode = 9
        versionName = "5.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("perevodchik.keystore")
            storePassword = "perevodchik"
            keyAlias = "perevodchik"
            keyPassword = "perevodchik"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets["main"].java.srcDir("../shared/java")
}

dependencies {
    implementation(files("../app/libs/sherpa-onnx.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.google.mlkit:translate:17.0.3")
    // Камера и офлайн-распознавание текста (модель встроена в приложение)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
    // Установка на часы по «Отладке по Wi‑Fi» (как Bugjaeger / Wear Installer)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
