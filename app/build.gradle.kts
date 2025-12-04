// Aplica los plugins definidos en settings.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.onnxsc"
    // Usar SDK 34 (Android 14) para compatibilidad con Gradle 8.x
    compileSdk = 34 

    defaultConfig {
        applicationId = "com.example.onnxsc"
        minSdk = 26
        targetSdk = 34 // Usar targetSdk 34 para compatibilidad
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            // Asegúrate de que el signingConfig 'debug' esté disponible
            signingConfig = signingConfigs.getByName("debug") 
        }
    }

    // Opciones de compilación de Java
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures { viewBinding = true }
}

dependencies {
    // Dependencias de AndroidX y Material actualizadas
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Dependencia de ONNX (dejada en latest.release)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:latest.release")

    // Añadir la dependencia de Kotlin si falta
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
