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
- Convierte Bitmap → tensor NCHW 1×3×H×W (ajustable automáticamente)
- Auto-detecta formato de salida (YOLO v5-v8, RT-DETR, SSD, clasificación, segmentación)
- Aplica post-proceso real: NMS, filtro de confianza, decode de bboxes
- Devuelve bbox + clase + probabilidad listos para dibujar

### 4. Resultados Visuales Superpuestos
- Texto flotante con clase, probabilidad y bbox
- Soporte para múltiples detecciones simultáneas con colores distintos
- Se actualiza en cada frame
- Se limpia automáticamente al cambiar de modelo

### 5. Cambio de Modelo en Caliente
- Botón "Cambiar modelo" sin salir de la app
- Re-inspecciona dependencias y vuelve a cargar

### 6. Consola Interna
- Log de carga, FPS, errores y resultados
- Botón "Limpiar" para vaciar el log

### 7. Compatibilidad Total
- Modelos pre-entrenados: sigue las instrucciones que traiga (ops, inputs, outputs)
- Modelos sin entrenar: exportar a ONNX y cargarlos
- Detecta automáticamente si necesita pesos externos u ops personalizadas

## Project Structure
```
app/src/main/java/com/example/onnxsc/
├── MainActivity.kt           # Actividad principal, UI y orquestación
├── OnnxProcessor.kt          # Carga modelo, inferencia ONNX, parseo de resultados
├── PostProcessor.kt          # Auto-detección de formato, NMS, decode de bboxes
├── ModelInspector.kt         # Inspección de modelos (dependencias, tamaño)
├── ModelSwitcher.kt          # Cambio de modelo en caliente
├── ResultOverlay.kt          # Overlay visual de resultados y bbox múltiples
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

## Formatos Soportados
- **Clasificación**: ImageNet, MobileNet, ResNet, etc.
- **Detección YOLO**: v5, v7, v8, v11 (auto-detecta formato)
- **RT-DETR**: Detección end-to-end con transformers
- **SSD**: Single Shot Detector (multi-output)
- **Segmentación**: Masks por clase

## Build
El workflow de GitHub Actions (`main.yaml`) compila automáticamente.
APK generado en: `app/build/outputs/apk/debug/app-debug.apk`

## Uso
1. Subir tu modelo `.onnx` a la carpeta Download
2. Ejecutar la app en Android
3. Elegir modelo → Capturar pantalla → Ver resultados en vivo
4. Cambiar de modelo sin reiniciar
5. Guardar capturas con resultados superpuestos (botón "Guardar captura")

## Recent Changes
- **2025-12-05**: 
  - **FIX CRÍTICO**: Corregido crash de MediaProjection en Android 14+ (API 34)
    - El servicio foreground ahora se inicia ANTES de obtener MediaProjection
    - Sistema de callback para garantizar que el servicio esté listo
    - Clonación correcta del Intent de captura
  - PostProcessor con auto-detección de formato (YOLO, RT-DETR, SSD, clasificación, segmentación)
  - NMS real aplicado a todos los formatos de detección
  - Soporte para múltiples detecciones simultáneas
  - Botón "Guardar captura" que dibuja overlay en la imagen
  - ResultOverlay con colores por clase y resumen de detecciones
  - Manejo de modelos multi-output (SSD)
  - OnnxProcessor con validación de tipos FLOAT/DOUBLE
  - ScreenCaptureService para compatibilidad con Android 10+
  - Código sin mocks y con manejo robusto de errores
  - Eliminado ScreenCaptureManager.kt (código placeholder no usado)
