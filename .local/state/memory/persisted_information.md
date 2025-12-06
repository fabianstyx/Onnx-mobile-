# ONNX Screen Capture - Estado Actualizado

## Última Sesión (2025-12-06)
Se completaron TODAS las correcciones de bugs en XcloudAimbot.kt y config.ini.

## Cambios Realizados Esta Sesión

### XcloudAimbot.kt - REESCRITO COMPLETAMENTE
Archivo: `app/src/main/java/com/example/onnxsc/engine/Aim/XcloudAimbot.kt`

**Bugs corregidos:**
- Todas las referencias de config keys ahora coinciden con config.ini
- `min_keypoint_confidence` → `keypoint_confidence`  
- `smoothing_percent` → `aim_speed_percent`
- `triggerbot` → `auto_shoot`
- `trigger_delay_ms` → `trigger_delay_before_shoot`

**Nuevas funciones:**
- `filterPosesInIgnoreRegion()` - Filtra poses en región del jugador
- `bitmapToFloatArray()` - Tensor NHWC para MoveNet
- Burst mode, Smart slowdown, FPS compensation
- Target switch cooldown, Prediction con velocity history

### config.ini - ACTUALIZADO CON 100+ PARÁMETROS
Archivo: `app/src/main/assets/config.ini`
Sección `[xcloud_aim]` completa con todas las opciones del JS original.

## PENDIENTE: Explicar al Usuario Cómo Usar XCloudAimbot

### Uso de XCloudAimbot.kt:

1. **Preparación:**
   - Modelo MoveNet en `/sdcard/ONNX/movenet_singlepose_lightning.onnx`
   
2. **Config.ini:**
   ```ini
   [xcloud_aim]
   enable = @TOGGLE:true
   model_path = /sdcard/ONNX/movenet_singlepose_lightning.onnx
   ```

3. **Flujo:**
   - `XCloudAimbot.init(context)` - Auto en ScreenCaptureService
   - `XCloudAimbot.processFrame(bitmap)` - Cada frame
   - `XCloudAimbot.setAimActive(true/false)` - Toggle
   - `XCloudAimbot.destroy()` - Cleanup

4. **Parámetros clave:**
   - `fov_radius`: 136px default
   - `aim_point`: nose, left_eye, etc.
   - `aim_speed_percent`: 10-100%
   - `smart_slowdown_enabled`: true
   - `prediction_enabled`: true
   - `auto_shoot`: false (activar para disparo auto)

## Progress Tracker
`.local/state/replit/agent/progress_tracker.md` - 39 items complete

## Usuario Indicó
- Build se maneja en GitHub, no en Replit
- Solo corrección de código requerida
- Explicar cómo usar XCloudAimbot.kt
