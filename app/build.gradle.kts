plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.objectremover"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.objectremover"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    // ONNX Runtime for Android - provides ai.onnxruntime.OrtEnvironment,
    // ai.onnxruntime.OrtSession, ai.onnxruntime.OnnxTensor used by
    // LamaInpainter.kt to run inpainting_lama_2025jan.onnx.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.google.mediapipe:tasks-vision:latest.release")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}