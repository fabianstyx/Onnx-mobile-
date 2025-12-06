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

### 7. Información del Modelo (NUEVO)
- Botón "Info" muestra metadatos completos del modelo ONNX
- Entradas: nombre, forma, tipo de dato
- Salidas: nombre, forma, tipo de dato
- Versión de opset y operadores utilizados
- Diálogo scrollable con texto seleccionable/copiable

### 8. Configuración de Post-Proceso (NUEVO)
- Botón "Config" abre editor de configuración
- **Umbral de Confianza**: Slider 1-99% (default 25%)
- **Umbral NMS (IoU)**: Slider 1-99% (default 45%)
- **Máx Detecciones**: Slider 1-500 (default 100)
- **Clases Habilitadas**: Filtrar por IDs de clase específicos
- **Nombres de Clases**: Personalizar nombres para cada clase
- Configuración guardada en JSON por modelo
- Se aplica en tiempo real durante la captura

### 9. Compatibilidad Total
- Modelos pre-entrenados: sigue las instrucciones que traiga (ops, inputs, outputs)
- Modelos sin entrenar: exportar a ONNX y cargarlos
- Detecta automáticamente si necesita pesos externos u ops personalizadas

## Project Structure
```
app/src/main/java/com/example/onnxsc/
├── MainActivity.kt            # Actividad principal, UI y orquestación
├── OnnxProcessor.kt           # Carga modelo, inferencia ONNX, parseo de resultados
├── PostProcessor.kt           # Auto-detección de formato, NMS, decode de bboxes
├── PostProcessingConfig.kt    # Configuración JSON de post-proceso por modelo
├── ModelInspector.kt          # Inspección de modelos (metadatos, dependencias)
├── ModelSwitcher.kt           # Cambio de modelo en caliente
├── ResultOverlay.kt           # Overlay visual de resultados y bbox múltiples
├── StatusOverlay.kt           # Overlay de estado (FPS, REC, latencia)
├── FloatingOverlayService.kt  # Servicio de overlay flotante del sistema
├── FpsMeter.kt                # Medición de FPS y latencia
├── GallerySaver.kt            # Guardar capturas en Pictures/ONNX-SC
├── DependencyInstaller.kt     # Verificación de dependencias
├── ExternalWeightsManager.kt  # Manejo de pesos externos
├── NodeJsManager.kt           # Soporte para ops Node.js/ML
├── ScreenCaptureService.kt    # Servicio de captura en primer plano
└── Logger.kt                  # Sistema de logging thread-safe

app/src/main/res/layout/
├── activity_main.xml          # Layout principal con botones Info y Config
├── dialog_model_info.xml      # Diálogo de información del modelo
└── dialog_postprocess_config.xml  # Editor de configuración post-proceso
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

### Environment Setup (Replit)
The Android SDK is automatically set up in `~/android-sdk` with:
- Command-line tools (latest)
- Platform tools
- Build tools 34.0.0
- Android SDK Platform 34

The `local.properties` file is generated with the SDK path.

### Build Command
```bash
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

APK generado en: `app/build/outputs/apk/debug/app-debug.apk`

## Uso
1. Subir tu modelo `.onnx` a la carpeta Download
2. Ejecutar la app en Android
3. Elegir modelo → Capturar pantalla → Ver resultados en vivo
4. Cambiar de modelo sin reiniciar
5. Guardar capturas con resultados superpuestos (botón "Guardar captura")

## Recent Changes
- **2025-12-05 (v10)**:
  - **NUEVA FUNCIÓN: Overlay Flotante del Sistema (ESP/Bboxes sobre TODAS las apps)**
    - FloatingOverlayService: Nuevo servicio de primer plano con overlays del sistema
    - Usa TYPE_APPLICATION_OVERLAY para dibujar encima de todas las aplicaciones
    - Muestra barra de estado flotante (FPS, REC, latencia, detecciones) visible siempre
    - Dibuja bounding boxes/ESP sobre la pantalla completa del dispositivo
    - Detection class hecha Parcelable para pasar datos entre servicios
    - Flujo de permisos: Solicita SYSTEM_ALERT_WINDOW, fallback a overlay in-app si se deniega
    - Escalado de coordenadas: Bboxes se escalan correctamente al tamaño de pantalla
    - Nuevo archivo: FloatingOverlayService.kt
    - Permisos agregados: SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE_SPECIAL_USE

- **2025-12-05 (v9)**:
  - **FIX CRÍTICO: Overlays no visibles durante captura**
    - StatusOverlay refactorizado: Eliminadas condiciones de carrera entre timer interno y actualizaciones externas
    - Limpieza correcta de layouts: removeExistingLayout() asegura que no queden vistas huérfanas
    - Actualizaciones centralizadas con updateStats(fps, latency, detections)
    - Logging de debug agregado para rastrear callbacks y visualización
    - BboxView mejorado: Maneja correctamente dimensiones de origen faltantes
    - ResultOverlay con logging de detecciones y posiciones de bbox
    - Animación de REC correctamente iniciada/detenida con setRecording()

- **2025-12-05 (v8)**:
  - **NUEVAS FUNCIONES: Overlay en Tiempo Real y Auto-Configuración de Modelos**
    - StatusOverlay: Muestra FPS, estado REC, latencia y contador de detecciones durante la captura
    - Escalado correcto de coordenadas bbox: Los bounding boxes ahora se alinean correctamente con la pantalla
    - Extracción automática de configuración embebida en modelos ONNX (thresholds, nombres de clases)
    - Mejoras visuales: Líneas más gruesas (6px), contorno negro (10px), sombras en etiquetas
    - Mayor elevación del overlay container (32dp) para visibilidad garantizada
    - Nuevos archivos: StatusOverlay.kt, EmbeddedModelConfig (en ModelInspector.kt)
    - Métodos agregados: ModelInspector.extractEmbeddedConfig(), ResultOverlay.setSourceDimensions()

- **2025-12-05 (v7)**:
  - **NUEVAS FUNCIONES: Información del Modelo y Configuración de Post-Proceso**
    - Botón "Info" para ver metadatos completos del modelo (entradas, salidas, formas, tipos, operadores)
    - Botón "Config" para ajustar parámetros de post-proceso (confianza, NMS, max detecciones)
    - Filtrado de clases por ID y nombres personalizados
    - Configuración guardada en JSON por modelo (persistente)
    - Procesamiento con configuración aplicada en tiempo real
    - Nuevos archivos: PostProcessingConfig.kt, dialog_model_info.xml, dialog_postprocess_config.xml
    - Métodos agregados: ModelInspector.inspectDetailed(), OnnxProcessor.processImageWithConfig()

- **2025-12-05 (v6)**:
  - **FIX CRÍTICO: Crash después de 1-2 minutos de captura**
    - Corregido memory leak por acumulación de bitmaps
    - Nuevo sistema de procesamiento con thread dedicado (HandlerThread)
    - Límite de frames pendientes (MAX_PENDING_FRAMES = 2) para evitar OOM
    - Manejo correcto de reciclaje de bitmaps (copia antes de reciclar original)
    - Limpieza de callbacks pendientes al detener captura
    - Manejo de OutOfMemoryError con System.gc()
  - **v5**: Soporte COMPLETO para todos los tipos de output ONNX
    - OnnxTensor, OnnxSequence, OnnxMap Y arrays de Java directos (float[][][], etc.)
    - Funciones recursivas para aplanar datos y calcular shapes correctamente
    - Preserva las formas originales (ej: [1, 25200, 85] para YOLO)
  - **FIX: Consola mejorada**
    - Throttling de mensajes duplicados (ventana de 2 segundos)
    - Límite de 100 líneas para evitar saturación de memoria
    - Texto seleccionable y copiable (textIsSelectable=true)
    - ScrollView independiente con altura de 250dp
    - Scrollbars siempre visibles
  - **Cambios anteriores (v3)**:
    - Arquitectura completa de captura reescrita para Android 14+
    - Todo el procesamiento de MediaProjection ocurre DENTRO del servicio
    - ImageReader, VirtualDisplay y captura de frames todo dentro de ScreenCaptureService
    - PostProcessor con auto-detección de formato (YOLO, RT-DETR, SSD, clasificación, segmentación)
    - NMS real aplicado a todos los formatos de detección
    - Soporte para múltiples detecciones simultáneas
    - Botón "Guardar captura" que dibuja overlay en la imagen
    - ResultOverlay con colores por clase y resumen de detecciones
