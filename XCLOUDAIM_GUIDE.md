# XCloudAimbot - Guía de Uso

## Descripción
XCloudAimbot es un módulo de detección de poses humanas usando el modelo ONNX MoveNet. Dibuja overlays visuales (skeleton, FOV circle, crosshair, tracers) sobre personas detectadas en la pantalla.

---

## Paso 1: Descargar el Modelo MoveNet

1. Descarga el modelo `movenet_singlepose_lightning.onnx` de TensorFlow Hub
2. Crea la carpeta `/sdcard/ONNX/` en tu dispositivo Android
3. Coloca el archivo del modelo en: `/sdcard/ONNX/movenet_singlepose_lightning.onnx`

> **Nota**: El modelo SINGLEPOSE_LIGHTNING es más rápido. Para mayor precisión, usa SINGLEPOSE_THUNDER (modelo de 256px).

---

## Paso 2: Permisos Necesarios

La app necesita los siguientes permisos:

| Permiso | Descripción |
|---------|-------------|
| Overlay (Dibujar sobre otras apps) | Para mostrar los overlays visuales |
| Captura de pantalla | Para analizar los frames de pantalla |
| Almacenamiento | Para leer el modelo ONNX |

---

## Paso 3: Configuración en config.ini

El archivo de configuración se encuentra en:
```
Android/data/com.onxxs.on/config.ini
```

### Configuración Básica
```ini
[xcloud_aim]
enable = true
model_path = /sdcard/ONNX/movenet_singlepose_lightning.onnx
always_on_enabled = true
detection_enabled = true
```

### Configuración Recomendada para Evitar Falsos Positivos
```ini
min_pose_score = 0.40
keypoint_confidence = 0.35
min_valid_keypoints = 10
```

---

## Paso 4: Iniciar la Aplicación

1. Abre la aplicación ONNX Screen Capture
2. Concede todos los permisos cuando se soliciten:
   - Permiso de overlay
   - Permiso de captura de pantalla
   - Permiso de almacenamiento
3. Presiona **"Iniciar captura"**

---

## Paso 5: Overlays Visuales

Una vez iniciada la captura, verás los siguientes elementos:

| Elemento | Descripción |
|----------|-------------|
| **FOV Circle** | Círculo en el centro de la pantalla que muestra el campo de visión |
| **Skeleton** | Líneas conectando los keypoints del cuerpo detectado |
| **Head Dot** | Punto en la cabeza de la persona detectada |
| **Crosshair** | Punto de mira en el centro |
| **Tracers** | Líneas desde el centro hacia el objetivo |

---

## Paso 6: Ajustar Sensibilidad

Si tienes problemas de detección, ajusta estos valores:

### Demasiados falsos positivos (detecta cosas que no son personas):
```ini
min_pose_score = 0.50      # Aumentar (0.40 - 0.60)
keypoint_confidence = 0.40 # Aumentar (0.35 - 0.50)
min_valid_keypoints = 12   # Aumentar (10 - 15)
```

### No detecta personas visibles:
```ini
min_pose_score = 0.30      # Reducir
keypoint_confidence = 0.25 # Reducir
min_valid_keypoints = 8    # Reducir
```

---

## Flujo de Procesamiento

```
ScreenCaptureService (captura frames)
        ↓
XCloudAimbot.processFrame()
        ↓
Modelo ONNX MoveNet (inferencia)
        ↓
parseMoveNetOutput() → extrae 17 keypoints
        ↓
Filtros:
  - Mínimo 10 keypoints válidos
  - Nariz debe ser detectada
  - Score > min_pose_score
        ↓
filterPosesInIgnoreRegion() → excluye región del jugador
        ↓
selectBestTarget() → selecciona objetivo más cercano al centro
        ↓
drawVisuals() → FloatingOverlayService
        ↓
BboxOverlayView.drawPoseVisuals() → dibuja overlays
```

---

## Solución de Problemas

### Los overlays no aparecen

1. **Verifica que el servicio está corriendo**
   - Revisa logcat con tag `XCloudAimbot`
   - Si ves: `"FloatingOverlayService no está corriendo"` → El servicio de overlay no inició

2. **Verifica la configuración**
   ```ini
   enable = true
   always_on_enabled = true
   esp_show_only_when_aiming = false
   ```

3. **Verifica el modelo**
   - Si ves: `"Modelo no encontrado en..."` → Verifica que el archivo existe en la ruta

### No detecta personas

1. Revisa logcat: `"Poses detectadas: 0"`
2. Reduce los thresholds de detección
3. Asegúrate que la persona esté visible completamente

### Detecta falsos positivos

1. Aumenta `min_pose_score` a 0.50 o más
2. Aumenta `min_valid_keypoints` a 12 o 15
3. Aumenta `keypoint_confidence` a 0.40 o más

---

## Keypoints de MoveNet

El modelo detecta 17 puntos del cuerpo:

| Índice | Nombre | Descripción |
|--------|--------|-------------|
| 0 | nose | Nariz (requerido) |
| 1 | left_eye | Ojo izquierdo |
| 2 | right_eye | Ojo derecho |
| 3 | left_ear | Oreja izquierda |
| 4 | right_ear | Oreja derecha |
| 5 | left_shoulder | Hombro izquierdo |
| 6 | right_shoulder | Hombro derecho |
| 7 | left_elbow | Codo izquierdo |
| 8 | right_elbow | Codo derecho |
| 9 | left_wrist | Muñeca izquierda |
| 10 | right_wrist | Muñeca derecha |
| 11 | left_hip | Cadera izquierda |
| 12 | right_hip | Cadera derecha |
| 13 | left_knee | Rodilla izquierda |
| 14 | right_knee | Rodilla derecha |
| 15 | left_ankle | Tobillo izquierdo |
| 16 | right_ankle | Tobillo derecho |

---

## Build

El proyecto se compila usando GitHub Actions. No se necesita Android SDK local.
