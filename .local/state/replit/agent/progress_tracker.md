[x] 1. Install the required packages
[x] 2. Restart the workflow to see if the project is working
[x] 3. Verify the project is working using the feedback tool
[x] 4. Inform user the import is completed and they can start building, mark the import as completed using the complete_project_import tool
[x] 5. Fix ONNX tensor error - handle OnnxSequence, OnnxMap, and other output types
[x] 6. Reduce console spam - add throttling and duplicate message filtering
[x] 7. Make console scrollable and text selectable/copyable
[x] 8. Fix Kotlin smart cast error in ScreenCaptureService.kt - capture mutable bitmap variable in immutable local before closure
[x] 9. Enhanced Model Inspector - Added inspectDetailed() method to read full ONNX metadata (inputs, outputs, shapes, data types, operators)
[x] 10. Model Info UI - Added "Info" button and dialog_model_info.xml for displaying model metadata in scrollable dialog
[x] 11. Post-Processing Config - Created PostProcessingConfig.kt with JSON save/load, confidence/NMS/maxDetections settings
[x] 12. Config Editor UI - Added "Config" button and dialog_postprocess_config.xml with sliders and class filters
[x] 13. OnnxProcessor Updates - Added processImageWithConfig() and parseOutputWithConfig() methods
[x] 14. Floating Overlay Service - Created FloatingOverlayService.kt for system-wide floating overlays
[x] 15. System Overlay Permission - Added SYSTEM_ALERT_WINDOW permission to AndroidManifest.xml
[x] 16. Detection Parcelable - Made Detection class Parcelable for passing between services
[x] 17. MainActivity Floating Overlay Integration - Updated MainActivity to request overlay permission and use floating overlay for status (FPS, REC, detections) and bounding boxes over other apps
[x] 18. Fix InferenceInputHandler.kt - Changed import from PostProcessor to Detection class, fixed property references (label->className, x/y/width/height->bbox properties)
[x] 19. Fix type mismatch in OnnxProcessor.kt - Changed processImageWithConfig parameter from ModelConfig to PostProcessingSettings to match caller and callee
[x] 20. Setup Android SDK - Installed Android command-line tools, SDK platform-tools, build-tools;34.0.0, platforms;android-34
[x] 21. Created local.properties with Android SDK path
[x] 22. Fix NNAPI incompatibility error - Added automatic fallback from NNAPI to CPU when model operations are not supported by NNAPI (e.g., AddNnapiSplit dimension errors)
[x] 23. ConfigEngine.kt - Lector de config.ini con tipos nativos, acceso global, copia desde assets, hot-reload
[x] 24. LogicEngine.kt - Evaluador de detecciones ONNX según parámetros INI (ROI, filtros, prioridades, modos)
[x] 25. ActionEngine.kt - Métodos de acciones programables (tap, swipe, keyPress, axisControl, etc.) via root o accessibility
[x] 26. config.ini - Archivo de ejemplo con secciones: general, detection, regions, filters, actions, targeting, overlay, performance, logging
[x] 27. MainActivity Integration - Inicializa ConfigEngine y ActionEngine, conecta flujo OnnxProcessor → LogicEngine → ActionEngine
[x] 28. Fix PickleLoader.kt build error - Removed pyrolite dependency, created stub implementation (pickle loading disabled but interface preserved for future use)
[x] 29. Re-setup Android SDK and local.properties for Replit environment
[x] 30. Build successful - APK generated at app/build/outputs/apk/debug/app-debug.apk (~100MB)
[x] 31. Fix Material3 style error - Changed Widget.Material3.Button.FilledButton to Widget.Material3.Button (default filled style)
[x] 32. Re-installed Android SDK (platform-tools, build-tools;34.0.0, platforms;android-34)
[x] 33. Re-created local.properties with Android SDK path
[x] 34. Build successful - APK generated at app/build/outputs/apk/debug/app-debug.apk (~100MB)
[x] 35. Fix XCloudAimbot build errors - Added missing imports (ConfigEngine, XCloudAimbot) to ScreenCaptureService.kt
[x] 36. Fix XcloudAimbot.kt - Fixed PointF division operator issue, Random import, smoothMoveTo → swipe, Int to Float type conversion for tap(), removed non-existent drawVisuals canvas access
[x] 37. MAJOR: XcloudAimbot.kt REWRITE - Fixed ALL config key references to match new config.ini format
[x] 38. MAJOR: Added full xcloud_aim section to config.ini with 100+ parameters matching JS original
[x] 39. XcloudAimbot features added: ignore_self_region, burst_mode, smart_slowdown, fps_compensation, prediction enhancements, target_switch_cooldown
[x] 40. FIX: ScreenCaptureService - Added missing startedCallback/stoppedCallback invocations (fixes FPS/detection not showing)
[x] 41. FIX: Bitmap recycling crash - XCloudAimbot now receives a copy of the bitmap to avoid recycled bitmap errors
[x] 42. FIX: XCloudAimbot error handling - Added throttled logging for missing model warnings and errors
[x] 43. FIX: XCloudAimbot processing moved to background thread (captureHandler) to avoid blocking UI and causing ANRs
[x] 44. IMPORT COMPLETE - All code migrated successfully, ready for GitHub build
[x] 45. FIX: False positive detections - increased min keypoints from 5 to 10, min_pose_score 0.25→0.40, keypoint_confidence 0.20→0.35
[x] 46. FIX: Added min_valid_keypoints config option and nose validation requirement
[x] 47. FIX: setAimActive logic bug - removed inverted logic
[x] 48. FIX: Default values in code now match config.ini (always_on_enabled=true, esp_show_only_when_aiming=false)
[x] 49. ADDED: Debug logging to XCloudAimbot for troubleshooting overlay issues
[x] 50. FIX: Changed xcloud_aim enable default from false to true in ScreenCaptureService.kt
[x] 51. FIX: Added debug logging to BboxOverlayView (setSourceDimensions, updateDetections, onDraw)
[x] 52. FIX: Added fallback to screen dimensions when sourceWidth/sourceHeight are 0 in onDraw
[x] 53. FIX: XCloudAimConfigActivity crash - Added ConfigEngine.init(this) call in onCreate() to ensure config is loaded before accessing values
[x] 54. FIX: Slider value validation crash - Values must be valueFrom + multiple of stepSize. Changed FOV default 136→140, added rounding for all slider values loaded from config
[x] 55. FEATURE: Model selector - Added dropdown to choose between Lightning (fast, ~30 FPS) and Thunder (accurate, ~15 FPS)
[x] 56. FEATURE: Auto download models - Added download button with progress bar, downloads from Hugging Face
[x] 57. FEATURE: Model status display - Shows if model is downloaded, file size, and path
[x] 58. FIX: Added INTERNET and ACCESS_NETWORK_STATE permissions to AndroidManifest.xml for model download
[x] 59. FIX: Android 12+ storage - Changed model download path from /sdcard/ONNX/ to app's private directory (getExternalFilesDir) which doesn't require permissions
[x] 60. FIX: OnnxProcessor conflicting overloads - Renamed private getEnvironment() to getOrCreateEnvironment() to fix Kotlin compilation error