# ONNX Screen Capture - Android Project

## Overview
This is an Android application written in Kotlin that captures screen content and runs ONNX (Open Neural Network Exchange) machine learning models on the captured images. The app allows users to:
- Load ONNX models from files
- Capture screen content using Android's MediaProjection API
- Run inference on captured images
- Display results with overlays

## Project Structure
- **Language**: Kotlin
- **Build System**: Gradle 8.7 with Kotlin DSL
- **Android SDK**: Target SDK 34, Min SDK 26
- **Key Dependencies**:
  - ONNX Runtime Android 1.23.2
  - TensorFlow Lite 2.15.0
  - AndroidX libraries
  - Material Design components

## Architecture
The app consists of several key components:
- `MainActivity.kt` - Main activity handling UI and orchestration
- `OnnxProcessor.kt` - ONNX model inference processing
- `ScreenCaptureManager.kt` - Screen capture functionality
- `ModelInspector.kt` - Model inspection and validation
- `DependencyInstaller.kt` - Dependency management
- `NodeJsManager.kt` - Node.js integration for JS operators
- `ExternalWeightsManager.kt` - External weights handling
- `ResultOverlay.kt` - UI overlay for displaying results
- `FpsMeter.kt` - Performance monitoring
- `GallerySaver.kt` - Saving captured images
- `Logger.kt` - Logging utility

## Replit Environment Limitations
**Important**: This is an Android mobile application that requires:
- Android SDK and build tools
- Android emulator or physical device for testing
- Screen capture permissions (Android-specific)
- MediaProjection API (Android-specific)

Replit can:
- ✅ Build the project using Gradle
- ✅ Validate code syntax
- ✅ Manage dependencies
- ✅ Run unit tests (if added)

Replit cannot:
- ❌ Run the app (requires Android emulator/device)
- ❌ Test screen capture functionality
- ❌ Test ONNX inference on actual devices

## Build Instructions
To build this project in Replit:
```bash
./gradlew build
```

To build APK:
```bash
./gradlew assembleDebug
```

To run tests:
```bash
./gradlew test
```

## Development Setup
The project is configured with:
- Android Gradle Plugin 8.2.2
- Kotlin 1.9.22
- Java 17 compatibility
- View binding enabled

## Recent Changes
- **2025-12-05**: Imported from GitHub and configured for Replit environment
