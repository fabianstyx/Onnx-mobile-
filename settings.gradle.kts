// settings.gradle.kts (Contenido corregido)

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // ESTE ES EL BLOQUE QUE DEBEMOS AÑADIR/MODIFICAR
    plugins {
        // 1. Android Gradle Plugin (AGP) - Versión compatible con Gradle 8.7
        id("com.android.application") version "8.2.2" apply false
        // 2. Kotlin Plugin - Versión compatible con AGP 8.2.2
        id("org.jetbrains.kotlin.android") version "1.9.22" apply false
        // 3. Plugin de Librería (si el proyecto usa módulos de librería)
        id("com.android.library") version "8.2.2" apply false
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "OnnxScreenCapture"
include(":app")

