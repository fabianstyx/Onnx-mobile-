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
