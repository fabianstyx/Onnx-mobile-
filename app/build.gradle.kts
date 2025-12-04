plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.onnxsc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.onnxsc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            // signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
    }
}

dependencies {
    // Dependencias Base de Android y Material Design
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Dependencia de ONNX
    implementation("com.microsoft.onnxruntime:onnxruntime-android:latest.release")
    
    // Dependencias de TensorFlow Lite (Core)
    implementation("org.tensorflow:tensorflow-lite:2.15.0")
    
    // ✅ CORRECCIÓN 1: TENSORFLOW LITE SUPPORT (para TensorImage, TensorBuffer)
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") 
    
    // ✅ CORRECCIÓN 2: ACTIVITY KTX (para registerForActivityResult)
    implementation("androidx.activity:activity-ktx:1.8.2") 
}
