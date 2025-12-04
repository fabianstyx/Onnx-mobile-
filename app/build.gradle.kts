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
            // Asumiendo que signingConfigs.getByName("debug") está definido en un lugar accesible
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
        viewBinding = true // Es bueno para referenciar vistas desde MainActivity.kt
    }
}

dependencies {
    // Dependencias Base de Android y Material Design
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Dependencia de ONNX (se mantiene)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:latest.release")
    
    // CORRECCIÓN CLAVE: Usamos una versión estable de TensorFlow Lite (2.15.0)
    // Esto resuelve el error 'Could not find org.tensorflow:tensorflow-lite:0.0.0-nightly'
    implementation("org.tensorflow:tensorflow-lite:2.15.0")
    
    // Opcional: Recomendado si usas modelos de TFLite en la GPU
    // implementation("org.tensorflow:tensorflow-lite-gpu:2.15.0")
}