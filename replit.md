# ONNX Screen Capture - Android App

## Overview
ONNX Screen es una aplicación Android que captura la pantalla en tiempo real, ejecuta un modelo ONNX sobre cada frame y muestra los resultados (clase, probabilidad, bbox) superpuestos.

Sirve para inferencia visual en vivo con cualquier modelo ONNX (clasificación, detección, segmentación, etc.) sin necesidad de re-entrenar.

## Funcionalidades
1. **Selector de modelo**: Carga cualquier archivo `.onnx` del almacenamiento
2. **Captura de pantalla en vivo**: MediaProjection + ImageReader a 60 FPS
3. **Ejecución ONNX real**: Inferencia sin mocks con ONNX Runtime Android
4. **Resultados visuales superpuestos**: Texto y bbox sobre la pantalla
5. **Cambio de modelo en caliente**: Sin reiniciar la app
6. **Consola interna**: Log de carga, FPS, errores y resultados

## Project Structure
```
app/src/main/java/com/example/onnxsc/
├── MainActivity.kt          # Actividad principal, UI y orquestación
├── OnnxProcessor.kt          # Carga modelo, inferencia ONNX, parseo de resultados
├── ModelInspector.kt         # Inspección de modelos (dependencias, tamaño)
├── ModelSwitcher.kt          # Cambio de modelo en caliente
├── ResultOverlay.kt          # Overlay visual de resultados y bbox
├── FpsMeter.kt               # Medición de FPS y latencia
├── GallerySaver.kt           # Guardar capturas en Pictures/ONNX-SC
├── DependencyInstaller.kt    # Verificación de dependencias
├── ExternalWeightsManager.kt # Manejo de pesos externos
├── NodeJsManager.kt          # Soporte para ops Node.js
└── ScreenCaptureManager.kt   # Gestión de captura de pantalla
```

## Technical Details
- **Language**: Kotlin
- **Build System**: Gradle 8.7 with Kotlin DSL
- **Android SDK**: Target SDK 34, Min SDK 26
- **Key Dependencies**:
  - ONNX Runtime Android 1.23.2
  - TensorFlow Lite 2.15.0
  - AndroidX libraries
  - Material Design 3

## Replit Environment
- **Java**: OpenJDK 17.0.15
- **Android SDK**: platform-tools, build-tools 34.0.0, platforms android-34
- **Gradle memory**: 1024MB (optimized for Replit)

## Build
El workflow "Build Android" compila automáticamente. APK generado en:
`app/build/outputs/apk/debug/app-debug.apk`

## Recent Changes
- **2025-12-05**: 
  - Implementado OnnxProcessor con caché de sesión y parseo real de resultados
  - Actualizado MainActivity para usar inferencia real sin mocks
  - Mejorado ResultOverlay con soporte para bbox y limpieza automática
  - Corregido FpsMeter para calcular latencia por frame
  - Añadido FrameLayout overlay al layout principal
