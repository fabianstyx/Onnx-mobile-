# ONNX Screen Capture - Android App

## Overview
ONNX Screen es una aplicación Android que captura la pantalla en tiempo real, ejecuta un modelo ONNX sobre cada frame y muestra los resultados (clase, probabilidad, bbox) superpuestos.

Sirve para inferencia visual en vivo con cualquier modelo ONNX (clasificación, detección, segmentación, etc.) sin necesidad de re-entrenar.

## Funcionalidades Clave

### 1. Selector de Modelo
- Abre cualquier archivo `.onnx` del almacenamiento
- Muestra nombre humano, tamaño y dependencias detectadas
- Copia pesos externos si el modelo los requiere

### 2. Captura de Pantalla en Vivo
- MediaProjection + ImageReader → Bitmap real 60 FPS
- Guarda el frame como PNG en Pictures/ONNX-SC
- Mide FPS y latencia (ms) en consola interna

### 3. Ejecución ONNX Real
- Carga el modelo con ONNX Runtime Android
- Convierte Bitmap → tensor NCHW 1×3×224×224
- Ejecuta inferencia y devuelve OnnxTensor sin mocks

### 4. Resultados Visuales Superpuestos
- Texto flotante con clase, probabilidad y bbox
- Se actualiza en cada frame
- Se limpia automáticamente al cambiar de modelo

### 5. Cambio de Modelo en Caliente
- Botón "Cambiar modelo" sin salir de la app
- Re-inspecciona dependencias y vuelve a cargar

### 6. Consola Interna
- Log de carga, FPS, errores y resultados
- Botón "Limpiar" para vaciar el log

### 7. Compatibilidad Total
- Modelos pre-entrenados: sigue las instrucciones que traiga
- Modelos sin entrenar: exportar a ONNX y cargarlos
- Detecta automáticamente si necesita pesos externos u ops personalizadas

## Project Structure
```
app/src/main/java/com/example/onnxsc/
├── MainActivity.kt           # Actividad principal, UI y orquestación
├── OnnxProcessor.kt          # Carga modelo, inferencia ONNX, parseo de resultados
├── ModelInspector.kt         # Inspección de modelos (dependencias, tamaño)
├── ModelSwitcher.kt          # Cambio de modelo en caliente
├── ResultOverlay.kt          # Overlay visual de resultados y bbox
├── FpsMeter.kt               # Medición de FPS y latencia
├── GallerySaver.kt           # Guardar capturas en Pictures/ONNX-SC
├── DependencyInstaller.kt    # Verificación de dependencias
├── ExternalWeightsManager.kt # Manejo de pesos externos
├── NodeJsManager.kt          # Soporte para ops Node.js/ML
├── ScreenCaptureService.kt   # Servicio de captura en primer plano
└── Logger.kt                 # Sistema de logging thread-safe
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

## Build
El workflow de GitHub Actions (`main.yaml`) compila automáticamente.
APK generado en: `app/build/outputs/apk/debug/app-debug.apk`

## Uso
1. Ejecutar la app en Android
2. Elegir modelo → Capturar pantalla → Ver resultados en vivo
3. Cambiar de modelo sin reiniciar
4. Guardar capturas con resultados superpuestos

## Recent Changes
- **2025-12-05**: 
  - Implementado OnnxProcessor con caché de sesión y parseo real de resultados
  - MainActivity con captura continua y mejor manejo de errores
  - ResultOverlay con soporte para bbox y limpieza automática thread-safe
  - FpsMeter con cálculo de latencia promedio
  - GallerySaver independiente de Logger para evitar crashes
  - ScreenCaptureService para compatibilidad con Android 10+
  - Logger thread-safe con WeakReference
  - ModelInspector con detección de operaciones del modelo
  - Código sin mocks y con manejo robusto de errores
