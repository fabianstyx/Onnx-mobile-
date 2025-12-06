package com.example.onnxsc.engine.aim

import android.content.Context
import android.graphics.*
import com.example.onnxsc.FloatingOverlayService
import com.example.onnxsc.engine.ConfigEngine
import com.example.onnxsc.engine.ActionEngine
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import ai.onnxruntime.*
import kotlin.math.*
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
    data class Pose(val keypoints: List<Keypoint>, val score: Float)

    private var currentAim = PointF(0f, 0f)
    private var targetHistory = mutableMapOf<Int, MutableList<PointF>>()
    private var lastFireTime = 0L
    private val random = Random()

    fun init(context: Context? = null) {
        if (isRunning) return
        appContext = context
        ortEnv = OrtEnvironment.getEnvironment()
        isRunning = true
    }

    fun processFrame(bitmap: Bitmap) {
        if (!ConfigEngine.getBool("xcloud_aim", "enable", false)) return

        val modelPath = ConfigEngine.getString("xcloud_aim", "model_path", "")
        if (modelPath.isEmpty() || !java.io.File(modelPath).exists()) return

        try {
            if (ortSession == null) {
                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.addNnapi()
                ortSession = ortEnv!!.createSession(modelPath, sessionOptions)
            }

            val inputName = ortSession!!.inputNames.iterator().next()
            val resized = Bitmap.createScaledBitmap(bitmap, 192, 192, true)
            val tensorImage = TensorImage.fromBitmap(resized)
            val inputTensor = OnnxTensor.createTensor(ortEnv!!, tensorImage.buffer, longArrayOf(1, 3, 192, 192))

            val outputs = ortSession!!.run(mapOf(inputName to inputTensor))
            val outputArray = (outputs[0].value as Array<FloatArray>)[0][0] as FloatArray

            val poses = parseMoveNetOutput(outputArray, bitmap.width, bitmap.height)
            if (poses.isNotEmpty()) {
                val best = selectBestTarget(poses)
                val aimPoint = calculateAimPoint(best)
                val finalAim = applyPredictionAndSmoothing(aimPoint)
                moveAim(finalAim)
                drawVisuals(best, finalAim)
                triggerFire(finalAim)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseMoveNetOutput(output: FloatArray, srcW: Int, srcH: Int): List<Pose> {
        val poses = mutableListOf<Pose>()
        val kps = mutableListOf<Keypoint>()
        var totalScore = 0f

        for (i in keypointNames.indices) {
            val y = output[i * 3]
            val x = output[i * 3 + 1]
            val score = output[i * 3 + 2]
            if (score > ConfigEngine.getFloat("xcloud_aim", "min_keypoint_confidence", 0.22f)) {
                kps.add(Keypoint(x * srcW, y * srcH, score))
                totalScore += score
            }
        }

        if (kps.size >= 9 && totalScore / kps.size >= ConfigEngine.getFloat("xcloud_aim", "min_pose_score", 0.30f)) {
            poses.add(Pose(kps, totalScore / kps.size))
        }
        return poses
    }

    private fun selectBestTarget(poses: List<Pose>): Pose {
        val centerX = ConfigEngine.getInt("general", "screen_width", 1080) / 2f
        val centerY = ConfigEngine.getInt("general", "screen_height", 2400) / 2f
        val fov = ConfigEngine.getInt("xcloud_aim", "fov_radius", 420)

        return poses.minByOrNull { pose ->
            val nose = pose.keypoints[0]
            val dist = hypot(nose.x - centerX, nose.y - centerY)
            if (dist > fov) Float.MAX_VALUE else dist
        } ?: poses[0]
    }

    private fun calculateAimPoint(pose: Pose): PointF {
        val aimPart = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
        val kp = pose.keypoints[keypointNames.indexOf(aimPart)]
        return PointF(kp.x, kp.y + ConfigEngine.getFloat("xcloud_aim", "head_offset", 0f) * 100)
    }

    private fun applyPredictionAndSmoothing(target: PointF): PointF {
        val id = target.hashCode()
        val history = targetHistory.getOrPut(id) { mutableListOf() }
        history.add(target)
        if (history.size > 10) history.removeAt(0)

        var predicted = target
        if (ConfigEngine.getBool("xcloud_aim", "prediction_enabled", true) && history.size > 3) {
            val velX = (target.x - history[history.size - 3].x) / 3f
            val velY = (target.y - history[history.size - 3].y) / 3f
            val predictionScale = ConfigEngine.getFloat("xcloud_aim", "prediction_scale", 1.3f)
            predicted = PointF(
                target.x + velX * 8 * predictionScale,
                target.y + velY * 8 * predictionScale
            )
        }

        val smoothing = ConfigEngine.getInt("xcloud_aim", "smoothing_percent", 45) / 100f
        currentAim.x += (predicted.x - currentAim.x) * smoothing
        currentAim.y += (predicted.y - currentAim.y) * smoothing

        if (random.nextFloat() < ConfigEngine.getFloat("xcloud_aim", "jitter_amount", 0.15f)) {
            currentAim.offset(
                (random.nextGaussian() * 8).toFloat(),
                (random.nextGaussian() * 8).toFloat()
            )
        }

        return currentAim
    }

    private fun moveAim(point: PointF) {
        val centerX = ConfigEngine.getInt("general", "screen_width", 1080) / 2f
        val centerY = ConfigEngine.getInt("general", "screen_height", 2400) / 2f
        val duration = ConfigEngine.getInt("xcloud_aim", "move_duration_ms", 50).toLong()
        ActionEngine.swipe(centerX, centerY, point.x, point.y, duration)
    }

    private fun triggerFire(aim: PointF) {
        if (!ConfigEngine.getBool("xcloud_aim", "triggerbot", true)) return
        val now = System.currentTimeMillis()
        if (now - lastFireTime > ConfigEngine.getInt("xcloud_aim", "trigger_delay_ms", 38)) {
            ActionEngine.tap(
                ConfigEngine.getInt("xcloud_aim", "fire_button_x", 960).toFloat(),
                ConfigEngine.getInt("xcloud_aim", "fire_button_y", 1700).toFloat()
            )
            lastFireTime = now
        }
    }

    private fun drawVisuals(pose: Pose, aim: PointF) {
        val context = appContext ?: return
        if (!FloatingOverlayService.isRunning()) return
        
        val showSkeleton = ConfigEngine.getBool("xcloud_aim", "show_skeleton", true)
        val showFov = ConfigEngine.getBool("xcloud_aim", "show_fov_circle", true)
        val showPrediction = ConfigEngine.getBool("xcloud_aim", "show_prediction_line", true)
        val fovRadius = ConfigEngine.getInt("xcloud_aim", "fov_radius", 420).toFloat()
        
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
            showPrediction
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
        isRunning = false
        targetHistory.clear()
        appContext = null
    }
}
