plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sarmat.perevodchik"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sarmat.perevodchik"
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Постоянный ключ: новые версии ставятся поверх старой без удаления
    // (и без потери скачанных голосовых пакетов).
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
    // Офлайн-речь: sherpa-onnx (скачивается при сборке в app/libs)
    implementation(files("libs/sherpa-onnx.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("androidx.wear:wear-input:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
