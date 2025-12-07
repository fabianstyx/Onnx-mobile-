package com.example.onnxsc.engine.aim

import android.content.Context
import android.graphics.*
import com.example.onnxsc.FloatingOverlayService
import com.example.onnxsc.OnnxProcessor
import com.example.onnxsc.engine.ConfigEngine
import com.example.onnxsc.engine.ActionEngine
import ai.onnxruntime.*
import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.Random

object XCloudAimbot {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isRunning = false
    private var appContext: Context? = null
    
    private var inputFloatBuffer: FloatBuffer? = null
    private var inputBufferSize = 0

    private val keypointNames = listOf(
        "nose", "left_eye", "right_eye", "left_ear", "right_ear",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_hip", "right_hip",
        "left_knee", "right_knee", "left_ankle", "right_ankle"
    )

    data class Keypoint(val x: Float, val y: Float, val score: Float)
    data class Pose(val keypoints: List<Keypoint>, val score: Float, val id: Int = 0)

    private var currentAim = PointF(0f, 0f)
    private var targetHistory = mutableMapOf<Int, MutableList<PointF>>()
    private var velocityHistory = mutableMapOf<Int, MutableList<PointF>>()
    private var lastFireTime = 0L
    private var burstCount = 0
    private var lastBurstTime = 0L
    private var currentTargetId = -1
    private var targetSwitchTime = 0L
    private val random = Random()
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var lastFrameTime = 0L
    private var currentFps = 60f
    private var frameCount = 0
    private var skipCounter = 0
    private var isAimActive = false
    
    private var captureWidth = 0
    private var captureHeight = 0
    private var detectedPoseCount = 0
    private var lastStatsUpdateTime = 0L
    private var processingLatency = 0L
    private var lastDeltaTime = 16.67f

    fun init(context: Context? = null) {
        if (isRunning) return
        appContext = context
        ortEnv = OrtEnvironment.getEnvironment()
        isRunning = true
        
        context?.let {
            val display = it.resources.displayMetrics
            screenWidth = display.widthPixels
            screenHeight = display.heightPixels
            ActionEngine.setScreenCenter(screenWidth / 2f, screenHeight / 2f)
        }
        
        initializeSession()
    }
    
    private fun initializeSession() {
        val modelPath = findModelPath() ?: return
        
        try {
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
                
                try {
                    addNnapi()
                    android.util.Log.i("XCloudAimbot", "NNAPI ejecutor habilitado")
                } catch (e: Exception) {
                    android.util.Log.w("XCloudAimbot", "NNAPI no disponible, usando CPU: ${e.message}")
                }
            }
            
            ortSession = ortEnv!!.createSession(modelPath, sessionOptions)
            android.util.Log.i("XCloudAimbot", "Sesion ONNX inicializada: $modelPath")
            
            val modelType = ConfigEngine.getString("xcloud_aim", "model_type", "SINGLEPOSE_LIGHTNING")
            val inputSize = if (modelType == "SINGLEPOSE_THUNDER") 256 else 192
            inputBufferSize = inputSize * inputSize * 3
            inputFloatBuffer = FloatBuffer.allocate(inputBufferSize)
            
        } catch (e: Exception) {
            android.util.Log.e("XCloudAimbot", "Error inicializando sesion: ${e.message}")
        }
    }

    fun setAimActive(active: Boolean) {
        isAimActive = active
    }

    private var lastModelWarningTime = 0L
    private var lastErrorTime = 0L
    private var lastError: String? = null
    
    fun processFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ) {
        val xcloudEnabled = ConfigEngine.getBool("xcloud_aim", "enable", true)
        val detectionEnabled = ConfigEngine.getBool("xcloud_aim", "detection_enabled", true)
        
        if (!xcloudEnabled || !detectionEnabled) {
            return
        }

        val skipFrames = ConfigEngine.getInt("xcloud_aim", "skip_frames", 2)
        if (skipFrames > 0) {
            skipCounter++
            if (skipCounter < skipFrames) return
            skipCounter = 0
        }
        
        captureWidth = width
        captureHeight = height

        if (ortSession == null) {
            initializeSession()
            if (ortSession == null) {
                logWarningThrottled("XCloudAim: Modelo MoveNet no encontrado")
                updateFps()
                detectedPoseCount = 0
                updateStatsOverlay()
                return
            }
        }

        val frameStartTime = System.currentTimeMillis()
        updateFps()

        try {
            val inputName = ortSession!!.inputNames.iterator().next()
            
            val modelType = ConfigEngine.getString("xcloud_aim", "model_type", "SINGLEPOSE_LIGHTNING")
            val inputSize = if (modelType == "SINGLEPOSE_THUNDER") 256 else 192
            
            val inputData: FloatArray = if (NativeProcessor.isAvailable()) {
                val startNdk = System.nanoTime()
                val result = NativeProcessor.preprocessFrame(
                    buffer, width, height, pixelStride, rowStride, inputSize
                )
                val ndkTime = (System.nanoTime() - startNdk) / 1000
                if (frameCount % 60 == 0) {
                    android.util.Log.d("XCloudAimbot", "NDK preprocessing: ${ndkTime}µs")
                }
                result
            } else {
                preprocessByteBuffer(buffer, width, height, pixelStride, rowStride, inputSize)
            }
            
            val inputBuffer = FloatBuffer.wrap(inputData)
            val inputTensor = OnnxTensor.createTensor(
                ortEnv!!, 
                inputBuffer, 
                longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3)
            )

            val outputs = ortSession!!.run(mapOf(inputName to inputTensor))
            val outputValue = outputs[0].value
            
            val outputArray = when (outputValue) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = outputValue as Array<Array<Array<FloatArray>>>
                    arr[0][0].flatMap { it.toList() }.toFloatArray()
                }
                is FloatArray -> outputValue
                else -> {
                    inputTensor.close()
                    return
                }
            }

            val poses = parseMoveNetOutput(outputArray, width, height)
            val filteredPoses = filterPosesInIgnoreRegion(poses, width, height)
            detectedPoseCount = filteredPoses.size
            
            processingLatency = System.currentTimeMillis() - frameStartTime
            updateStatsOverlay()
            
            if (frameCount % 60 == 0) {
                android.util.Log.d("XCloudAimbot", "Poses: ${poses.size}, filtradas: ${filteredPoses.size}, FPS: %.1f, latency: ${processingLatency}ms".format(currentFps))
            }
            
            if (filteredPoses.isNotEmpty()) {
                val best = selectBestTarget(filteredPoses)
                if (best != null) {
                    val aimPoint = calculateAimPoint(best)
                    val finalAim = applyPredictionAndSmoothing(aimPoint, best.id)
                    
                    val alwaysOn = ConfigEngine.getBool("xcloud_aim", "always_on_enabled", true)
                    if (isAimActive || alwaysOn) {
                        moveAimRelative(finalAim)
                    }
                    
                    drawVisuals(best, finalAim, width, height)
                    triggerFire(finalAim, best)
                }
            } else {
                drawFovOnlyVisuals(width, height)
            }

            inputTensor.close()

        } catch (e: Exception) {
            logErrorThrottled("XCloudAim error: ${e.message}")
        }
    }
    
    @Deprecated("Use processFrame(ByteBuffer) for better performance")
    fun processFrame(bitmap: Bitmap) {
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        processFrame(buffer, bitmap.width, bitmap.height, 4, bitmap.width * 4)
    }
    
    private fun preprocessByteBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        targetSize: Int
    ): FloatArray {
        val output = FloatArray(targetSize * targetSize * 3)
        val scaleX = width.toFloat() / targetSize
        val scaleY = height.toFloat() / targetSize
        
        var outIdx = 0
        for (y in 0 until targetSize) {
            val srcY = (y * scaleY).toInt()
            val rowOffset = srcY * rowStride
            
            for (x in 0 until targetSize) {
                val srcX = (x * scaleX).toInt()
                val pixelOffset = rowOffset + srcX * pixelStride
                
                val r = buffer.get(pixelOffset).toInt() and 0xFF
                val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                val b = buffer.get(pixelOffset + 2).toInt() and 0xFF
                
                output[outIdx++] = r / 255f
                output[outIdx++] = g / 255f
                output[outIdx++] = b / 255f
            }
        }
        
        return output
    }
    
    private fun logWarningThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastModelWarningTime > 5000) {
            lastModelWarningTime = now
            android.util.Log.w("XCloudAimbot", message)
        }
    }
    
    private fun logErrorThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (message != lastError || now - lastErrorTime > 3000) {
            lastError = message
            lastErrorTime = now
            android.util.Log.e("XCloudAimbot", message)
        }
    }
    
    private fun updateStatsOverlay() {
        val context = appContext ?: return
        
        val now = System.currentTimeMillis()
        if (now - lastStatsUpdateTime < 100) return
        lastStatsUpdateTime = now
        
        try {
            FloatingOverlayService.updateStats(
                context,
                currentFps.toDouble(),
                processingLatency,
                detectedPoseCount
            )
        } catch (e: Exception) {
            if (frameCount % 60 == 0) {
                android.util.Log.w("XCloudAimbot", "updateStatsOverlay error: ${e.message}")
            }
        }
    }

    private fun isMoveNetCompatible(modelPath: String): Boolean {
        val fileName = java.io.File(modelPath).name.lowercase()
        val moveNetKeywords = listOf("movenet", "pose", "lightning", "thunder", "singlepose", "multipose")
        return moveNetKeywords.any { fileName.contains(it) }
    }

    private fun findModelPath(): String? {
        val useMainModel = ConfigEngine.getBool("xcloud_aim", "use_main_model", true)
        
        if (useMainModel) {
            val mainModelPath = OnnxProcessor.getModelPath()
            if (mainModelPath != null) {
                val file = java.io.File(mainModelPath)
                if (file.exists() && file.canRead() && isMoveNetCompatible(mainModelPath)) {
                    return mainModelPath
                }
            }
        }
        
        val configPath = ConfigEngine.getString("xcloud_aim", "model_path", "")
        if (configPath.isNotEmpty()) {
            val file = java.io.File(configPath)
            if (file.exists() && file.canRead()) {
                return configPath
            }
        }
        
        val modelType = ConfigEngine.getString("xcloud_aim", "model_type", "SINGLEPOSE_LIGHTNING")
        val modelName = if (modelType == "SINGLEPOSE_THUNDER") {
            "movenet_singlepose_thunder.onnx"
        } else {
            "movenet_singlepose_lightning.onnx"
        }
        
        val context = appContext
        val appPrivatePaths = if (context != null) {
            listOf(
                "${context.getExternalFilesDir(null)?.absolutePath}/models/$modelName",
                "${context.getExternalFilesDir(null)?.absolutePath}/$modelName",
                "${context.getExternalFilesDir(null)?.absolutePath}/ONNX/$modelName",
                "${context.filesDir.absolutePath}/$modelName"
            )
        } else {
            emptyList()
        }
        
        val possiblePaths = appPrivatePaths + listOf(
            "/sdcard/ONNX/$modelName",
            "/sdcard/Download/$modelName",
            "/storage/emulated/0/ONNX/$modelName",
            "/storage/emulated/0/Download/$modelName"
        )
        
        for (path in possiblePaths) {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                return path
            }
        }
        return null
    }

    private fun updateFps() {
        val now = System.currentTimeMillis()
        if (lastFrameTime > 0) {
            val delta = now - lastFrameTime
            if (delta > 0) {
                lastDeltaTime = delta.toFloat()
                currentFps = 0.9f * currentFps + 0.1f * (1000f / delta)
            }
        }
        lastFrameTime = now
        frameCount++
    }

    private fun parseMoveNetOutput(output: FloatArray, srcW: Int, srcH: Int): List<Pose> {
        val poses = mutableListOf<Pose>()
        val kps = mutableListOf<Keypoint>()
        var totalScore = 0f

        val minKeypointConf = ConfigEngine.getFloat("xcloud_aim", "keypoint_confidence", 0.35f)
        val minPoseScore = ConfigEngine.getFloat("xcloud_aim", "min_pose_score", 0.40f)
        val minValidKeypoints = ConfigEngine.getInt("xcloud_aim", "min_valid_keypoints", 10)

        for (i in keypointNames.indices) {
            val y = output.getOrElse(i * 3) { 0f }
            val x = output.getOrElse(i * 3 + 1) { 0f }
            val score = output.getOrElse(i * 3 + 2) { 0f }
            
            if (score > minKeypointConf) {
                kps.add(Keypoint(x * srcW, y * srcH, score))
                totalScore += score
            } else {
                kps.add(Keypoint(x * srcW, y * srcH, 0f))
            }
        }

        val validKeypoints = kps.count { it.score > 0 }
        val noseValid = kps.getOrNull(0)?.score ?: 0f > minKeypointConf
        
        if (validKeypoints >= minValidKeypoints && noseValid && kps.isNotEmpty()) {
            val avgScore = totalScore / max(validKeypoints, 1)
            if (avgScore >= minPoseScore) {
                poses.add(Pose(kps, avgScore, frameCount))
            }
        }
        return poses
    }

    private fun filterPosesInIgnoreRegion(poses: List<Pose>, srcW: Int, srcH: Int): List<Pose> {
        if (!ConfigEngine.getBool("xcloud_aim", "ignore_self_region_enabled", true)) {
            return poses
        }

        val ignoreXPercent = ConfigEngine.getFloat("xcloud_aim", "ignore_self_x_percent", 0.0f)
        val ignoreYPercent = ConfigEngine.getFloat("xcloud_aim", "ignore_self_y_percent", 0.27f)
        val ignoreWidthPercent = ConfigEngine.getFloat("xcloud_aim", "ignore_self_width_percent", 0.43f)
        val ignoreHeightPercent = ConfigEngine.getFloat("xcloud_aim", "ignore_self_height_percent", 0.74f)

        val ignoreLeft = srcW * ignoreXPercent
        val ignoreTop = srcH * ignoreYPercent
        val ignoreRight = ignoreLeft + srcW * ignoreWidthPercent
        val ignoreBottom = ignoreTop + srcH * ignoreHeightPercent

        return poses.filter { pose ->
            val nose = pose.keypoints.getOrNull(0) ?: return@filter true
            !(nose.x >= ignoreLeft && nose.x <= ignoreRight && 
              nose.y >= ignoreTop && nose.y <= ignoreBottom)
        }
    }

    private fun selectBestTarget(poses: List<Pose>): Pose? {
        if (poses.isEmpty()) return null

        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val fov = ConfigEngine.getInt("xcloud_aim", "fov_radius", 136).toFloat()
        val targetPriority = ConfigEngine.getString("xcloud_aim", "target_priority", "center")
        val targetSwitchCooldown = ConfigEngine.getInt("xcloud_aim", "target_switch_cooldown_ms", 0).toLong()

        val scaleX = screenWidth.toFloat() / captureWidth.coerceAtLeast(1)
        val scaleY = screenHeight.toFloat() / captureHeight.coerceAtLeast(1)

        val validPoses = poses.filter { pose ->
            if (pose.keypoints.isEmpty()) return@filter false
            val aimPart = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
            val kpIndex = keypointNames.indexOf(aimPart).coerceIn(0, pose.keypoints.size - 1)
            val kp = pose.keypoints[kpIndex]
            
            val screenX = kp.x * scaleX
            val screenY = kp.y * scaleY
            
            val betterDetection = ConfigEngine.getBool("xcloud_aim", "better_detection", true)
            if (betterDetection) {
                true
            } else {
                val dist = hypot(screenX - centerX, screenY - centerY)
                dist <= fov
            }
        }

        if (validPoses.isEmpty()) return null

        if (currentTargetId >= 0 && targetSwitchCooldown > 0) {
            val now = System.currentTimeMillis()
            if (now - targetSwitchTime < targetSwitchCooldown) {
                val currentTarget = validPoses.find { it.id == currentTargetId }
                if (currentTarget != null) return currentTarget
            }
        }

        val selected = when (targetPriority) {
            "mouse_position" -> validPoses.minByOrNull { pose ->
                val aimPart = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
                val kpIndex = keypointNames.indexOf(aimPart).coerceIn(0, pose.keypoints.size - 1)
                val kp = pose.keypoints[kpIndex]
                hypot(kp.x * scaleX - currentAim.x, kp.y * scaleY - currentAim.y)
            }
            else -> validPoses.minByOrNull { pose ->
                val aimPart = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
                val kpIndex = keypointNames.indexOf(aimPart).coerceIn(0, pose.keypoints.size - 1)
                val kp = pose.keypoints[kpIndex]
                hypot(kp.x * scaleX - centerX, kp.y * scaleY - centerY)
            }
        }

        if (selected != null && selected.id != currentTargetId) {
            currentTargetId = selected.id
            targetSwitchTime = System.currentTimeMillis()
        }

        return selected
    }

    private fun calculateAimPoint(pose: Pose): PointF {
        val aimPartName = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
        val kpIndex = keypointNames.indexOf(aimPartName).coerceIn(0, pose.keypoints.size - 1)
        val kp = pose.keypoints[kpIndex]

        val headOffset = ConfigEngine.getFloat("xcloud_aim", "head_offset", 0.15f)
        val bodyOffset = ConfigEngine.getFloat("xcloud_aim", "body_offset", 0.40f)

        var offsetY = 0f
        val headParts = listOf("nose", "left_eye", "right_eye", "left_ear", "right_ear")
        val bodyParts = listOf("left_shoulder", "right_shoulder", "left_hip", "right_hip")
        
        if (headParts.contains(aimPartName)) {
            offsetY = -headOffset * 100
        } else if (bodyParts.contains(aimPartName)) {
            offsetY = -bodyOffset * 100
        }

        val scaleX = screenWidth.toFloat() / captureWidth.coerceAtLeast(1)
        val scaleY = screenHeight.toFloat() / captureHeight.coerceAtLeast(1)

        return PointF(kp.x * scaleX, kp.y * scaleY + offsetY)
    }

    private fun applyPredictionAndSmoothing(target: PointF, targetId: Int): PointF {
        val history = targetHistory.getOrPut(targetId) { mutableListOf() }
        val velHistory = velocityHistory.getOrPut(targetId) { mutableListOf() }
        
        history.add(PointF(target.x, target.y))
        val jitterBufferSize = ConfigEngine.getInt("xcloud_aim", "jitter_buffer_size", 5)
        if (history.size > jitterBufferSize * 2) history.removeAt(0)

        var predicted = PointF(target.x, target.y)
        
        val deltaTime = lastDeltaTime / 1000f

        if (ConfigEngine.getBool("xcloud_aim", "prediction_enabled", true) && history.size >= 3) {
            val prevIndex = max(0, history.size - 3)
            val velX = (target.x - history[prevIndex].x) / 3f
            val velY = (target.y - history[prevIndex].y) / 3f
            
            velHistory.add(PointF(velX, velY))
            if (velHistory.size > jitterBufferSize) velHistory.removeAt(0)
            
            val smoothedVelX = velHistory.map { it.x }.average().toFloat()
            val smoothedVelY = velHistory.map { it.y }.average().toFloat()
            
            val maxVelocity = ConfigEngine.getInt("xcloud_aim", "max_velocity", 1000).toFloat()
            val clampedVelX = smoothedVelX.coerceIn(-maxVelocity * deltaTime, maxVelocity * deltaTime)
            val clampedVelY = smoothedVelY.coerceIn(-maxVelocity * deltaTime, maxVelocity * deltaTime)
            
            val latencyComp = ConfigEngine.getInt("xcloud_aim", "latency_compensation", 75)
            val predictionScale = ConfigEngine.getFloat("xcloud_aim", "prediction_scale", 1.10f)
            val predictionFrames = (latencyComp / 1000f) / deltaTime
            
            predicted = PointF(
                target.x + clampedVelX * predictionFrames * predictionScale,
                target.y + clampedVelY * predictionFrames * predictionScale
            )
        }

        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val distToTarget = hypot(predicted.x - centerX, predicted.y - centerY)

        var speedPercent = if (ConfigEngine.getBool("xcloud_aim", "aim_speed_enabled", true)) {
            ConfigEngine.getFloat("xcloud_aim", "aim_speed_percent", 45f) / 100f
        } else {
            0.5f
        }

        if (ConfigEngine.getBool("xcloud_aim", "smart_slowdown_enabled", true)) {
            val slowdownRadius = ConfigEngine.getInt("xcloud_aim", "slowdown_radius", 90).toFloat()
            
            if (distToTarget < slowdownRadius) {
                val maxSpeed = ConfigEngine.getFloat("xcloud_aim", "smart_slowdown_max_speed", 100f)
                val minSpeed = ConfigEngine.getFloat("xcloud_aim", "smart_slowdown_min_speed", 30f)
                val nonlinear = ConfigEngine.getBool("xcloud_aim", "nonlinear_enabled", true)
                val exponent = ConfigEngine.getFloat("xcloud_aim", "nonlinear_exponent", 1.5f)
                
                var t = distToTarget / slowdownRadius
                if (nonlinear) {
                    t = t.pow(exponent)
                }
                
                val slowdownSpeed = minSpeed + (maxSpeed - minSpeed) * t
                speedPercent *= slowdownSpeed / 100f
                
                if (ConfigEngine.getBool("xcloud_aim", "prediction_in_slowdown", true)) {
                    val velocityBoost = ConfigEngine.getFloat("xcloud_aim", "velocity_boost_factor", 0.5f)
                    val boostFactor = 1f + (1f - t) * velocityBoost
                    predicted.x = target.x + (predicted.x - target.x) * boostFactor
                    predicted.y = target.y + (predicted.y - target.y) * boostFactor
                }
            }
        }

        val timeScaledSpeed = speedPercent * (deltaTime * 60f)

        val smoothingFactor = ConfigEngine.getFloat("xcloud_aim", "smoothing_factor", 0.20f)
        val finalSmoothing = if (ConfigEngine.getBool("xcloud_aim", "enable_smoothing", true)) {
            timeScaledSpeed * (1f - smoothingFactor) + smoothingFactor
        } else {
            timeScaledSpeed
        }

        currentAim.x += (predicted.x - currentAim.x) * finalSmoothing.coerceIn(0.05f, 1f)
        currentAim.y += (predicted.y - currentAim.y) * finalSmoothing.coerceIn(0.05f, 1f)

        return PointF(currentAim.x, currentAim.y)
    }

    private fun moveAimRelative(point: PointF) {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        
        val deltaX = point.x - centerX
        val deltaY = point.y - centerY
        
        val deadZone = ConfigEngine.getFloat("xcloud_aim", "dead_zone", 0.20f) * 100
        if (abs(deltaX) < deadZone && abs(deltaY) < deadZone) return
        
        val sensX = ConfigEngine.getFloat("xcloud_aim", "sensitivity_x", 2.0f)
        val sensY = ConfigEngine.getFloat("xcloud_aim", "sensitivity_y", 2.0f)
        
        val moveX = deltaX * sensX * 0.1f
        val moveY = deltaY * sensY * 0.1f
        
        val processingInterval = ConfigEngine.getInt("xcloud_aim", "processing_interval_ms", 5).toLong()
        ActionEngine.moveRelative(moveX, moveY, processingInterval.coerceAtLeast(1))
    }

    private fun triggerFire(aim: PointF, pose: Pose) {
        if (!ConfigEngine.getBool("xcloud_aim", "auto_shoot", false)) return
        
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val distToCenter = hypot(aim.x - centerX, aim.y - centerY)
        
        val triggerThreshold = ConfigEngine.getFloat("xcloud_aim", "trigger_threshold", 0.70f)
        val fovRadius = ConfigEngine.getInt("xcloud_aim", "fov_radius", 136).toFloat()
        val triggerZone = fovRadius * (1f - triggerThreshold)
        
        if (distToCenter > triggerZone) return
        
        val now = System.currentTimeMillis()
        val triggerDelay = ConfigEngine.getInt("xcloud_aim", "trigger_delay_before_shoot", 10).toLong()
        
        if (now - lastFireTime < triggerDelay) return
        
        val burstMode = ConfigEngine.getBool("xcloud_aim", "burst_mode", false)
        
        if (burstMode) {
            val maxBurst = ConfigEngine.getInt("xcloud_aim", "burst_count", 3)
            val burstInterval = ConfigEngine.getInt("xcloud_aim", "burst_interval", 100).toLong()
            
            if (burstCount < maxBurst) {
                if (now - lastBurstTime >= burstInterval || burstCount == 0) {
                    performShoot()
                    burstCount++
                    lastBurstTime = now
                }
            } else {
                burstCount = 0
            }
        } else {
            performShoot()
        }
        
        lastFireTime = now
    }

    private fun performShoot() {
        val shootButton = ConfigEngine.getString("xcloud_aim", "shoot_button", "RT")
        ActionEngine.gameButtonPress(shootButton)
    }

    private fun drawVisuals(pose: Pose, aim: PointF, srcWidth: Int, srcHeight: Int) {
        val context = appContext ?: return
        
        val alwaysOn = ConfigEngine.getBool("xcloud_aim", "always_on_enabled", true)
        val espOnlyWhenAiming = ConfigEngine.getBool("xcloud_aim", "esp_show_only_when_aiming", false)
        
        if (espOnlyWhenAiming && !isAimActive && !alwaysOn) {
            return
        }
        
        val showSkeleton = ConfigEngine.getBool("xcloud_aim", "skeleton_enabled", true)
        val fovEnabled = ConfigEngine.getBool("xcloud_aim", "fov_circle_enabled", true)
        val fovShowOnlyWhenAiming = ConfigEngine.getBool("xcloud_aim", "fov_circle_show_only_when_aiming", true)
        val showFov = fovEnabled && (!fovShowOnlyWhenAiming || isAimActive || alwaysOn)
        val showTracers = ConfigEngine.getBool("xcloud_aim", "tracers_enabled", true)
        val fovRadius = ConfigEngine.getInt("xcloud_aim", "fov_radius", 136).toFloat()
        
        val scaleX = screenWidth.toFloat() / srcWidth.toFloat()
        val scaleY = screenHeight.toFloat() / srcHeight.toFloat()
        
        val keypoints = FloatArray(pose.keypoints.size * 2)
        for (i in pose.keypoints.indices) {
            keypoints[i * 2] = pose.keypoints[i].x * scaleX
            keypoints[i * 2 + 1] = pose.keypoints[i].y * scaleY
        }
        
        val showCrosshair = ConfigEngine.getBool("xcloud_aim", "crosshair_enabled", true)
        val crosshairStyle = ConfigEngine.getString("xcloud_aim", "crosshair_style", "dot")
        val crosshairColor = ConfigEngine.getString("xcloud_aim", "crosshair_color", "#000000")
        val crosshairSize = ConfigEngine.getInt("xcloud_aim", "crosshair_size", 3)
        val showHeadDot = ConfigEngine.getBool("xcloud_aim", "head_dot_enabled", true)
        val headDotColor = ConfigEngine.getString("xcloud_aim", "head_dot_color", "#00FFFF")
        val headDotSize = ConfigEngine.getInt("xcloud_aim", "head_dot_size", 4)
        val skeletonColor = ConfigEngine.getString("xcloud_aim", "skeleton_color", "#FFFFFF")
        val fovCircleColor = ConfigEngine.getString("xcloud_aim", "fov_circle_color", "rgba(255,255,255,0.3)")
        val tracersColor = ConfigEngine.getString("xcloud_aim", "tracers_color", "rgba(255,255,255,0.9)")
        val showIgnoreRegion = ConfigEngine.getBool("xcloud_aim", "draw_ignore_region_enabled", false)
        
        FloatingOverlayService.updatePoseVisualsExtended(
            context, keypoints, aim.x, aim.y, fovRadius,
            showSkeleton, showFov, showTracers, showCrosshair,
            crosshairStyle, crosshairColor, crosshairSize,
            showHeadDot, headDotColor, headDotSize,
            skeletonColor, fovCircleColor, tracersColor,
            showIgnoreRegion, detectedPoseCount
        )
    }

    private fun drawFovOnlyVisuals(srcWidth: Int, srcHeight: Int) {
        val context = appContext ?: return
        
        val alwaysOn = ConfigEngine.getBool("xcloud_aim", "always_on_enabled", true)
        val fovShowOnlyWhenAiming = ConfigEngine.getBool("xcloud_aim", "fov_circle_show_only_when_aiming", true)
        
        if (fovShowOnlyWhenAiming && !isAimActive && !alwaysOn) return
        
        val showFov = ConfigEngine.getBool("xcloud_aim", "fov_circle_enabled", true)
        if (!showFov) return
        
        val fovRadius = ConfigEngine.getInt("xcloud_aim", "fov_radius", 136).toFloat()
        val fovCircleColor = ConfigEngine.getString("xcloud_aim", "fov_circle_color", "rgba(255,255,255,0.3)")
        val showCrosshair = ConfigEngine.getBool("xcloud_aim", "crosshair_enabled", true)
        val crosshairStyle = ConfigEngine.getString("xcloud_aim", "crosshair_style", "dot")
        val crosshairColor = ConfigEngine.getString("xcloud_aim", "crosshair_color", "#000000")
        val crosshairSize = ConfigEngine.getInt("xcloud_aim", "crosshair_size", 3)
        
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        
        FloatingOverlayService.updatePoseVisualsExtended(
            context, floatArrayOf(), centerX, centerY, fovRadius,
            false, true, false, showCrosshair,
            crosshairStyle, crosshairColor, crosshairSize,
            false, "#00FFFF", 4, "#FFFFFF", fovCircleColor,
            "rgba(255,255,255,0.9)", false, 0
        )
    }

    fun destroy() {
        appContext?.let { context ->
            if (FloatingOverlayService.isRunning()) {
                FloatingOverlayService.clearPoseVisuals(context)
            }
        }
        ortSession?.close()
        ortSession = null
        inputFloatBuffer = null
        isRunning = false
        targetHistory.clear()
        velocityHistory.clear()
        currentTargetId = -1
        burstCount = 0
        appContext = null
    }

    fun isInitialized(): Boolean = isRunning && ortEnv != null

    fun getStatus(): String {
        return buildString {
            appendLine("=== XCloudAimbot Status ===")
            appendLine("Running: $isRunning")
            appendLine("Session: ${if (ortSession != null) "loaded" else "null"}")
            appendLine("NDK: ${if (NativeProcessor.isAvailable()) "available" else "fallback"}")
            appendLine("FPS: %.1f".format(currentFps))
            appendLine("Delta: %.2fms".format(lastDeltaTime))
            appendLine("Active: $isAimActive")
            appendLine("Target: $currentTargetId")
        }
    }
}
