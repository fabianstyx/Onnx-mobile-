// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Configuración de la construcción del proyecto
buildscript {
    repositories {
        google() // Repositorio de Google (esencial para TFLite, Compose, etc.)
        mavenCentral()
    }
    dependencies {
        // Asegúrate de usar la versión de AGP que deseas
        // classpath("com.android.tools.build:gradle:8.2.2")
        // classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

// Configuración de plugins de la raíz (puede estar también en settings.gradle.kts)
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.android.library") version "8.2.2" apply false
}