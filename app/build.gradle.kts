plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.protocolvoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.protocolvoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // sherpa-onnx native libraries для двух основных архитектур.
        // Современные Xiaomi (включая 12T) — arm64-v8a.
        // armeabi-v7a оставим для совместимости с более старыми устройствами.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
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
        // Нужен для BuildConfig.DEBUG (определяет разницу между debug/release сборками)
        buildConfig = true
    }

    // ASR-модели уже сжаты внутри (ONNX = protobuf). AAPT2 пытается их сжать
    // при сборке APK — это медленно и бессмысленно. Отключаем.
    androidResources {
        // Не сжимаем эти форматы в APK — RandomAccessFile/mmap требует сырых файлов
        noCompress += listOf("onnx", "tflite", "bin", "gz")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // sherpa-onnx native libraries не должны жаться при упаковке APK
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // AppCompat — нужен только для AppCompatDelegate.setApplicationLocales()
    // (переключение языка интерфейса в runtime). Самим экранам не нужен — всё на Compose.
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Sherpa-ONNX — speech recognition + diarization (offline).
    // Static-link AAR содержит и sherpa-onnx, и onnxruntime внутри (36 МБ).
    // Скачан с https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.0
    implementation(files("libs/sherpa-onnx.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
