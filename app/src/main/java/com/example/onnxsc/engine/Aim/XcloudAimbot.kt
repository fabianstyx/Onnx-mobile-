package com.example.onnxsc.engine.aim

import android.content.Context
import android.graphics.*
import com.example.onnxsc.FloatingOverlayService
import com.example.onnxsc.engine.ConfigEngine
import com.example.onnxsc.engine.ActionEngine
import ai.onnxruntime.*
import kotlin.math.*
import java.nio.FloatBuffer
import java.util.Random

object XCloudAimbot {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isRunning = false
    private var appContext: Context? = null

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

    fun init(context: Context? = null) {
        if (isRunning) return
        appContext = context
        ortEnv = OrtEnvironment.getEnvironment()
        isRunning = true
        
        context?.let {
            val display = it.resources.displayMetrics
            screenWidth = display.widthPixels
            screenHeight = display.heightPixels
        }
    }

    fun setAimActive(active: Boolean) {
        val alwaysOn = ConfigEngine.getBool("xcloud_aim", "always_on_enabled", false)
        isAimActive = if (alwaysOn) !active else active
    }

    fun processFrame(bitmap: Bitmap) {
        if (!ConfigEngine.getBool("xcloud_aim", "enable", false)) return
        if (!ConfigEngine.getBool("xcloud_aim", "detection_enabled", true)) return

        val skipFrames = ConfigEngine.getInt("xcloud_aim", "skip_frames", 2)
        if (skipFrames > 0) {
            skipCounter++
            if (skipCounter < skipFrames) return
            skipCounter = 0
        }

        val modelPath = ConfigEngine.getString("xcloud_aim", "model_path", "")
        if (modelPath.isEmpty() || !java.io.File(modelPath).exists()) return

        updateFps()

        try {
            if (ortSession == null) {
                val sessionOptions = OrtSession.SessionOptions()
                try {
                    sessionOptions.addNnapi()
                } catch (e: Exception) {
                    // NNAPI not available, use CPU
                }
                ortSession = ortEnv!!.createSession(modelPath, sessionOptions)
            }

            val inputName = ortSession!!.inputNames.iterator().next()
            
            val modelType = ConfigEngine.getString("xcloud_aim", "model_type", "SINGLEPOSE_LIGHTNING")
            val inputSize = if (modelType == "SINGLEPOSE_THUNDER") 256 else 192
            
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = bitmapToFloatBuffer(resized, inputSize)
            val inputTensor = OnnxTensor.createTensor(ortEnv!!, inputBuffer, longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3))

            val outputs = ortSession!!.run(mapOf(inputName to inputTensor))
            val outputValue = outputs[0].value
            
            val outputArray = when (outputValue) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = outputValue as Array<Array<Array<FloatArray>>>
                    arr[0][0].flatMap { it.toList() }.toFloatArray()
                }
                is FloatArray -> outputValue
                else -> return
            }

            val poses = parseMoveNetOutput(outputArray, bitmap.width, bitmap.height)
            val filteredPoses = filterPosesInIgnoreRegion(poses, bitmap.width, bitmap.height)
            
            if (filteredPoses.isNotEmpty()) {
                val best = selectBestTarget(filteredPoses)
                if (best != null) {
                    val aimPoint = calculateAimPoint(best)
                    val finalAim = applyPredictionAndSmoothing(aimPoint, best.id)
                    
                    if (isAimActive || ConfigEngine.getBool("xcloud_aim", "always_on_enabled", false)) {
                        moveAim(finalAim)
                    }
                    
                    drawVisuals(best, finalAim)
                    triggerFire(finalAim, best)
                }
            } else {
                clearVisuals()
            }

            inputTensor.close()
            resized.recycle()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, size: Int): FloatBuffer {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        
        val buffer = FloatBuffer.allocate(size * size * 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val pixel = pixels[y * size + x]
                buffer.put(((pixel shr 16) and 0xFF) / 255f)
                buffer.put(((pixel shr 8) and 0xFF) / 255f)
                buffer.put((pixel and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun updateFps() {
        val now = System.currentTimeMillis()
        if (lastFrameTime > 0) {
            val delta = now - lastFrameTime
            if (delta > 0) {
                currentFps = 0.9f * currentFps + 0.1f * (1000f / delta)
            }
        }
        lastFrameTime = now
    }

    private fun parseMoveNetOutput(output: FloatArray, srcW: Int, srcH: Int): List<Pose> {
        val poses = mutableListOf<Pose>()
        val kps = mutableListOf<Keypoint>()
        var totalScore = 0f

        val minKeypointConf = ConfigEngine.getFloat("xcloud_aim", "keypoint_confidence", 0.20f)
        val minPoseScore = ConfigEngine.getFloat("xcloud_aim", "min_pose_score", 0.25f)

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
        if (validKeypoints >= 5 && kps.isNotEmpty()) {
            val avgScore = totalScore / max(validKeypoints, 1)
            if (avgScore >= minPoseScore) {
                poses.add(Pose(kps, avgScore, frameCount++))
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

        val validPoses = poses.filter { pose ->
            if (pose.keypoints.isEmpty()) return@filter false
            val aimPart = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
            val kpIndex = keypointNames.indexOf(aimPart).coerceIn(0, pose.keypoints.size - 1)
            val kp = pose.keypoints[kpIndex]
            
            val betterDetection = ConfigEngine.getBool("xcloud_aim", "better_detection", true)
            if (betterDetection) {
                true
            } else {
                val dist = hypot(kp.x - centerX, kp.y - centerY)
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
                hypot(kp.x - currentAim.x, kp.y - currentAim.y)
            }
            else -> validPoses.minByOrNull { pose ->
                val aimPart = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
                val kpIndex = keypointNames.indexOf(aimPart).coerceIn(0, pose.keypoints.size - 1)
                val kp = pose.keypoints[kpIndex]
                hypot(kp.x - centerX, kp.y - centerY)
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

        return PointF(kp.x, kp.y + offsetY)
    }

    private fun applyPredictionAndSmoothing(target: PointF, targetId: Int): PointF {
        val history = targetHistory.getOrPut(targetId) { mutableListOf() }
        val velHistory = velocityHistory.getOrPut(targetId) { mutableListOf() }
        
        history.add(PointF(target.x, target.y))
        val jitterBufferSize = ConfigEngine.getInt("xcloud_aim", "jitter_buffer_size", 5)
        if (history.size > jitterBufferSize * 2) history.removeAt(0)

        var predicted = PointF(target.x, target.y)
        
        if (ConfigEngine.getBool("xcloud_aim", "prediction_enabled", true) && history.size >= 3) {
            val prevIndex = max(0, history.size - 3)
            val velX = (target.x - history[prevIndex].x) / 3f
            val velY = (target.y - history[prevIndex].y) / 3f
            
            velHistory.add(PointF(velX, velY))
            if (velHistory.size > jitterBufferSize) velHistory.removeAt(0)
            
            val smoothedVelX = velHistory.map { it.x }.average().toFloat()
            val smoothedVelY = velHistory.map { it.y }.average().toFloat()
            
            val maxVelocity = ConfigEngine.getInt("xcloud_aim", "max_velocity", 1000).toFloat()
            val clampedVelX = smoothedVelX.coerceIn(-maxVelocity / 60f, maxVelocity / 60f)
            val clampedVelY = smoothedVelY.coerceIn(-maxVelocity / 60f, maxVelocity / 60f)
            
            val latencyComp = ConfigEngine.getInt("xcloud_aim", "latency_compensation", 75)
            val predictionScale = ConfigEngine.getFloat("xcloud_aim", "prediction_scale", 1.10f)
            val predictionFrames = latencyComp / 16.67f
            
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

        if (ConfigEngine.getBool("xcloud_aim", "fps_compensation", true)) {
            val minFpsThreshold = ConfigEngine.getInt("xcloud_aim", "min_fps_threshold", 30)
            if (currentFps < minFpsThreshold && currentFps > 0) {
                speedPercent *= minFpsThreshold / currentFps
            }
        }

        val smoothingFactor = ConfigEngine.getFloat("xcloud_aim", "smoothing_factor", 0.20f)
        val finalSmoothing = if (ConfigEngine.getBool("xcloud_aim", "enable_smoothing", true)) {
            speedPercent * (1f - smoothingFactor) + smoothingFactor
        } else {
            speedPercent
        }

        currentAim.x += (predicted.x - currentAim.x) * finalSmoothing.coerceIn(0.05f, 1f)
        currentAim.y += (predicted.y - currentAim.y) * finalSmoothing.coerceIn(0.05f, 1f)

        return PointF(currentAim.x, currentAim.y)
    }

    private fun moveAim(point: PointF) {
        if (!ConfigEngine.getBool("xcloud_aim", "controller_enabled", false)) {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            
            val deltaX = point.x - centerX
            val deltaY = point.y - centerY
            
            val deadZone = ConfigEngine.getFloat("xcloud_aim", "dead_zone", 0.20f) * 100
            if (abs(deltaX) < deadZone && abs(deltaY) < deadZone) return
            
            val sensX = ConfigEngine.getFloat("xcloud_aim", "sensitivity_x", 2.0f)
            val sensY = ConfigEngine.getFloat("xcloud_aim", "sensitivity_y", 2.0f)
            
            val moveX = centerX + deltaX * sensX * 0.1f
            val moveY = centerY + deltaY * sensY * 0.1f
            
            val processingInterval = ConfigEngine.getInt("xcloud_aim", "processing_interval_ms", 5).toLong()
            ActionEngine.swipe(centerX, centerY, moveX, moveY, processingInterval)
        }
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
        
        when (shootButton) {
            "A" -> ActionEngine.keyPress("BUTTON_A")
            "B" -> ActionEngine.keyPress("BUTTON_B")
            "X" -> ActionEngine.keyPress("BUTTON_X")
            "Y" -> ActionEngine.keyPress("BUTTON_Y")
            "LB" -> ActionEngine.keyPress("BUTTON_L1")
            "RB" -> ActionEngine.keyPress("BUTTON_R1")
            "LT" -> ActionEngine.keyPress("BUTTON_L2")
            "RT" -> ActionEngine.keyPress("BUTTON_R2")
            else -> {
                val centerX = screenWidth / 2f
                val shootY = screenHeight * 0.7f
                ActionEngine.tap(centerX + screenWidth * 0.3f, shootY)
            }
        }
    }

    private fun drawVisuals(pose: Pose, aim: PointF) {
        val context = appContext ?: return
        if (!FloatingOverlayService.isRunning()) return
        
        val showDebug = ConfigEngine.getBool("xcloud_aim", "show_debug_info", true)
        val espOnlyWhenAiming = ConfigEngine.getBool("xcloud_aim", "esp_show_only_when_aiming", true)
        
        if (espOnlyWhenAiming && !isAimActive && !ConfigEngine.getBool("xcloud_aim", "always_on_enabled", false)) {
            return
        }
        
        val showSkeleton = ConfigEngine.getBool("xcloud_aim", "skeleton_enabled", true)
        val showFov = ConfigEngine.getBool("xcloud_aim", "fov_circle_enabled", true)
        val showTracers = ConfigEngine.getBool("xcloud_aim", "tracers_enabled", true)
        val fovRadius = ConfigEngine.getInt("xcloud_aim", "fov_radius", 136).toFloat()
        
        val keypoints = FloatArray(pose.keypoints.size * 2)
        for (i in pose.keypoints.indices) {
            keypoints[i * 2] = pose.keypoints[i].x
            keypoints[i * 2 + 1] = pose.keypoints[i].y
        }
        
        FloatingOverlayService.updatePoseVisuals(
            context,
            keypoints,
            aim.x,
            aim.y,
            fovRadius,
            showSkeleton,
            showFov,
            showTracers
        )
    }

    private fun clearVisuals() {
        val context = appContext ?: return
        if (FloatingOverlayService.isRunning()) {
            FloatingOverlayService.clearPoseVisuals(context)
        }
    }

    fun destroy() {
        appContext?.let { context ->
            if (FloatingOverlayService.isRunning()) {
                FloatingOverlayService.clearPoseVisuals(context)
            }
        }
        ortSession?.close()
        ortSession = null
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
            appendLine("FPS: %.1f".format(currentFps))
            appendLine("Active: $isAimActive")
            appendLine("Current target: $currentTargetId")
            appendLine("History entries: ${targetHistory.size}")
        }
    }
}
