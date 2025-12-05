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

## Replit Environment Setup
The project is configured to build in Replit with:
- **Java**: OpenJDK 17.0.15
- **Android SDK**: Command-line tools with platform-tools, build-tools 34.0.0, platforms android-34
- **Gradle**: 8.7 with Kotlin DSL

### Environment Variables
- `ANDROID_HOME`: `/home/runner/android-sdk`
- `JAVA_HOME`: Set to OpenJDK 17 path

### Build Workflow
The "Build Android" workflow compiles the project and generates the APK at:
`app/build/outputs/apk/debug/app-debug.apk`

## What Replit Can Do
- Build the project using Gradle
- Validate code syntax
- Manage dependencies
- Generate debug APK
- Run unit tests (if added)

## What Replit Cannot Do
- Run the app (requires Android emulator/device)
- Test screen capture functionality
- Test ONNX inference on actual devices

## Build Instructions
The workflow automatically runs:
```bash
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

To manually build:
```bash
export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6
export ANDROID_HOME=$HOME/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
./gradlew :app:assembleDebug --no-daemon
```

## Development Setup
The project is configured with:
- Android Gradle Plugin 8.2.2
- Kotlin 1.9.22
- Java 17 compatibility
- View binding enabled
- Gradle memory: 1024MB (optimized for Replit)

## Recent Changes
- **2025-12-05**: 
  - Imported from GitHub
  - Configured Android SDK command-line tools
  - Installed OpenJDK 17, platform-tools, build-tools 34.0.0, platforms android-34
  - Created local.properties with SDK path
  - Optimized Gradle memory settings for Replit environment
  - Successfully building debug APK
