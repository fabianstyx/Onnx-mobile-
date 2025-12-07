package com.example.onnxsc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicBoolean

class FloatingOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "floating_overlay_channel"
        const val NOTIFICATION_ID = 2001
        
        const val ACTION_START = "action_start_overlay"
        const val ACTION_STOP = "action_stop_overlay"
        const val ACTION_UPDATE_STATS = "action_update_stats"
        const val ACTION_UPDATE_DETECTIONS = "action_update_detections"
        const val ACTION_CLEAR_DETECTIONS = "action_clear_detections"
        const val ACTION_SET_RECORDING = "action_set_recording"
        
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_LATENCY = "extra_latency"
        const val EXTRA_DETECTION_COUNT = "extra_detection_count"
        const val EXTRA_DETECTIONS = "extra_detections"
        const val EXTRA_IS_RECORDING = "extra_is_recording"
        const val EXTRA_SOURCE_WIDTH = "extra_source_width"
        const val EXTRA_SOURCE_HEIGHT = "extra_source_height"
        
        private var instance: FloatingOverlayService? = null
        
        fun isRunning(): Boolean = instance != null
        
        fun getInstance(): FloatingOverlayService? = instance
        
        const val ACTION_UPDATE_POSE = "action_update_pose"
        const val EXTRA_POSE_KEYPOINTS = "extra_pose_keypoints"
        const val EXTRA_AIM_X = "extra_aim_x"
        const val EXTRA_AIM_Y = "extra_aim_y"
        const val EXTRA_FOV_RADIUS = "extra_fov_radius"
        const val EXTRA_SHOW_SKELETON = "extra_show_skeleton"
        const val EXTRA_SHOW_FOV = "extra_show_fov"
        const val EXTRA_SHOW_PREDICTION = "extra_show_prediction"
        
        fun updatePoseVisuals(
            context: Context,
            keypoints: FloatArray,
            aimX: Float,
            aimY: Float,
            fovRadius: Float,
            showSkeleton: Boolean,
            showFov: Boolean,
            showPrediction: Boolean
        ) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE_POSE
                putExtra(EXTRA_POSE_KEYPOINTS, keypoints)
                putExtra(EXTRA_AIM_X, aimX)
                putExtra(EXTRA_AIM_Y, aimY)
                putExtra(EXTRA_FOV_RADIUS, fovRadius)
                putExtra(EXTRA_SHOW_SKELETON, showSkeleton)
                putExtra(EXTRA_SHOW_FOV, showFov)
                putExtra(EXTRA_SHOW_PREDICTION, showPrediction)
            }
            context.startService(intent)
        }
        
        fun clearPoseVisuals(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE_POSE
                putExtra(EXTRA_POSE_KEYPOINTS, floatArrayOf())
            }
            context.startService(intent)
        }
        
        const val ACTION_UPDATE_POSE_EXTENDED = "action_update_pose_extended"
        const val EXTRA_SHOW_CROSSHAIR = "extra_show_crosshair"
        const val EXTRA_CROSSHAIR_STYLE = "extra_crosshair_style"
        const val EXTRA_CROSSHAIR_COLOR = "extra_crosshair_color"
        const val EXTRA_CROSSHAIR_SIZE = "extra_crosshair_size"
        const val EXTRA_SHOW_HEAD_DOT = "extra_show_head_dot"
        const val EXTRA_HEAD_DOT_COLOR = "extra_head_dot_color"
        const val EXTRA_HEAD_DOT_SIZE = "extra_head_dot_size"
        const val EXTRA_SKELETON_COLOR = "extra_skeleton_color"
        const val EXTRA_FOV_COLOR = "extra_fov_color"
        const val EXTRA_TRACERS_COLOR = "extra_tracers_color"
        const val EXTRA_SHOW_IGNORE_REGION = "extra_show_ignore_region"
        const val EXTRA_POSE_COUNT = "extra_pose_count"
        
        fun updatePoseVisualsExtended(
            context: Context,
            keypoints: FloatArray,
            aimX: Float,
            aimY: Float,
            fovRadius: Float,
            showSkeleton: Boolean,
            showFov: Boolean,
            showTracers: Boolean,
            showCrosshair: Boolean,
            crosshairStyle: String,
            crosshairColor: String,
            crosshairSize: Int,
            showHeadDot: Boolean,
            headDotColor: String,
            headDotSize: Int,
            skeletonColor: String,
            fovCircleColor: String,
            tracersColor: String,
            showIgnoreRegion: Boolean,
            poseCount: Int
        ) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE_POSE_EXTENDED
                putExtra(EXTRA_POSE_KEYPOINTS, keypoints)
                putExtra(EXTRA_AIM_X, aimX)
                putExtra(EXTRA_AIM_Y, aimY)
                putExtra(EXTRA_FOV_RADIUS, fovRadius)
                putExtra(EXTRA_SHOW_SKELETON, showSkeleton)
                putExtra(EXTRA_SHOW_FOV, showFov)
                putExtra(EXTRA_SHOW_PREDICTION, showTracers)
                putExtra(EXTRA_SHOW_CROSSHAIR, showCrosshair)
                putExtra(EXTRA_CROSSHAIR_STYLE, crosshairStyle)
                putExtra(EXTRA_CROSSHAIR_COLOR, crosshairColor)
                putExtra(EXTRA_CROSSHAIR_SIZE, crosshairSize)
                putExtra(EXTRA_SHOW_HEAD_DOT, showHeadDot)
                putExtra(EXTRA_HEAD_DOT_COLOR, headDotColor)
                putExtra(EXTRA_HEAD_DOT_SIZE, headDotSize)
                putExtra(EXTRA_SKELETON_COLOR, skeletonColor)
                putExtra(EXTRA_FOV_COLOR, fovCircleColor)
                putExtra(EXTRA_TRACERS_COLOR, tracersColor)
                putExtra(EXTRA_SHOW_IGNORE_REGION, showIgnoreRegion)
                putExtra(EXTRA_POSE_COUNT, poseCount)
            }
            context.startService(intent)
        }
        
        fun updateStats(context: Context, fps: Double, latency: Long, detectionCount: Int) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE_STATS
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_LATENCY, latency)
                putExtra(EXTRA_DETECTION_COUNT, detectionCount)
            }
            context.startService(intent)
        }
        
        fun updateDetections(context: Context, detections: ArrayList<Detection>, sourceWidth: Int, sourceHeight: Int) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE_DETECTIONS
                putParcelableArrayListExtra(EXTRA_DETECTIONS, detections)
                putExtra(EXTRA_SOURCE_WIDTH, sourceWidth)
                putExtra(EXTRA_SOURCE_HEIGHT, sourceHeight)
            }
            context.startService(intent)
        }
        
        fun clearDetections(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_CLEAR_DETECTIONS
            }
            context.startService(intent)
        }
        
        fun setRecording(context: Context, isRecording: Boolean) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SET_RECORDING
                putExtra(EXTRA_IS_RECORDING, isRecording)
            }
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var statusView: View? = null
    private var bboxOverlayView: BboxOverlayView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isVisible = AtomicBoolean(false)
    
    private var txtStatus: TextView? = null
    private var txtFps: TextView? = null
    private var txtLatency: TextView? = null
    private var txtDetections: TextView? = null
    
    private var recordingAnimator: Runnable? = null
    private var isRecordingDotVisible = true
    
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        getScreenDimensions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                showOverlays()
            }
            ACTION_STOP -> {
                hideOverlays()
                stopSelf()
            }
            ACTION_UPDATE_STATS -> {
                val fps = intent.getDoubleExtra(EXTRA_FPS, 0.0)
                val latency = intent.getLongExtra(EXTRA_LATENCY, 0L)
                val detectionCount = intent.getIntExtra(EXTRA_DETECTION_COUNT, 0)
                updateStatsInternal(fps, latency, detectionCount)
            }
            ACTION_UPDATE_DETECTIONS -> {
                val detections = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_DETECTIONS, Detection::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(EXTRA_DETECTIONS)
                }
                val sourceWidth = intent.getIntExtra(EXTRA_SOURCE_WIDTH, screenWidth)
                val sourceHeight = intent.getIntExtra(EXTRA_SOURCE_HEIGHT, screenHeight)
                updateDetectionsInternal(detections ?: arrayListOf(), sourceWidth, sourceHeight)
            }
            ACTION_CLEAR_DETECTIONS -> {
                clearDetectionsInternal()
            }
            ACTION_SET_RECORDING -> {
                val isRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
                setRecordingInternal(isRecording)
            }
            ACTION_UPDATE_POSE -> {
                val keypoints = intent.getFloatArrayExtra(EXTRA_POSE_KEYPOINTS) ?: floatArrayOf()
                val aimX = intent.getFloatExtra(EXTRA_AIM_X, 0f)
                val aimY = intent.getFloatExtra(EXTRA_AIM_Y, 0f)
                val fovRadius = intent.getFloatExtra(EXTRA_FOV_RADIUS, 0f)
                val showSkeleton = intent.getBooleanExtra(EXTRA_SHOW_SKELETON, true)
                val showFov = intent.getBooleanExtra(EXTRA_SHOW_FOV, true)
                val showPrediction = intent.getBooleanExtra(EXTRA_SHOW_PREDICTION, true)
                updatePoseInternal(keypoints, aimX, aimY, fovRadius, showSkeleton, showFov, showPrediction)
            }
            ACTION_UPDATE_POSE_EXTENDED -> {
                val keypoints = intent.getFloatArrayExtra(EXTRA_POSE_KEYPOINTS) ?: floatArrayOf()
                val aimX = intent.getFloatExtra(EXTRA_AIM_X, 0f)
                val aimY = intent.getFloatExtra(EXTRA_AIM_Y, 0f)
                val fovRadius = intent.getFloatExtra(EXTRA_FOV_RADIUS, 0f)
                val showSkeleton = intent.getBooleanExtra(EXTRA_SHOW_SKELETON, true)
                val showFov = intent.getBooleanExtra(EXTRA_SHOW_FOV, true)
                val showTracers = intent.getBooleanExtra(EXTRA_SHOW_PREDICTION, true)
                val showCrosshair = intent.getBooleanExtra(EXTRA_SHOW_CROSSHAIR, true)
                val crosshairStyle = intent.getStringExtra(EXTRA_CROSSHAIR_STYLE) ?: "dot"
                val crosshairColor = intent.getStringExtra(EXTRA_CROSSHAIR_COLOR) ?: "#000000"
                val crosshairSize = intent.getIntExtra(EXTRA_CROSSHAIR_SIZE, 3)
                val showHeadDot = intent.getBooleanExtra(EXTRA_SHOW_HEAD_DOT, true)
                val headDotColor = intent.getStringExtra(EXTRA_HEAD_DOT_COLOR) ?: "#00FFFF"
                val headDotSize = intent.getIntExtra(EXTRA_HEAD_DOT_SIZE, 4)
                val skeletonColor = intent.getStringExtra(EXTRA_SKELETON_COLOR) ?: "#FFFFFF"
                val fovColor = intent.getStringExtra(EXTRA_FOV_COLOR) ?: "rgba(255,255,255,0.3)"
                val tracersColor = intent.getStringExtra(EXTRA_TRACERS_COLOR) ?: "rgba(255,255,255,0.9)"
                val showIgnoreRegion = intent.getBooleanExtra(EXTRA_SHOW_IGNORE_REGION, false)
                val poseCount = intent.getIntExtra(EXTRA_POSE_COUNT, 0)
                updatePoseExtendedInternal(
                    keypoints, aimX, aimY, fovRadius, showSkeleton, showFov, showTracers,
                    showCrosshair, crosshairStyle, crosshairColor, crosshairSize,
                    showHeadDot, headDotColor, headDotSize,
                    skeletonColor, fovColor, tracersColor, showIgnoreRegion, poseCount
                )
            }
        }
        return START_NOT_STICKY
    }
    
    private fun updatePoseExtendedInternal(
        keypoints: FloatArray, aimX: Float, aimY: Float, fovRadius: Float,
        showSkeleton: Boolean, showFov: Boolean, showTracers: Boolean,
        showCrosshair: Boolean, crosshairStyle: String, crosshairColor: String, crosshairSize: Int,
        showHeadDot: Boolean, headDotColor: String, headDotSize: Int,
        skeletonColor: String, fovColor: String, tracersColor: String,
        showIgnoreRegion: Boolean, poseCount: Int
    ) {
        mainHandler.post {
            bboxOverlayView?.updatePoseExtended(
                keypoints, aimX, aimY, fovRadius, showSkeleton, showFov, showTracers,
                showCrosshair, crosshairStyle, crosshairColor, crosshairSize,
                showHeadDot, headDotColor, headDotSize,
                skeletonColor, fovColor, tracersColor, showIgnoreRegion, poseCount
            )
        }
    }
    
    private fun updatePoseInternal(
        keypoints: FloatArray,
        aimX: Float,
        aimY: Float,
        fovRadius: Float,
        showSkeleton: Boolean,
        showFov: Boolean,
        showPrediction: Boolean
    ) {
        mainHandler.post {
            bboxOverlayView?.updatePose(keypoints, aimX, aimY, fovRadius, showSkeleton, showFov, showPrediction)
        }
    }

    private fun getScreenDimensions() {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun startForegroundWithNotification() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlays() {
        if (isVisible.get()) {
            Logger.info("[FloatingOverlay] showOverlays: already visible, skipping")
            return
        }
        isVisible.set(true)
        Logger.info("[FloatingOverlay] showOverlays: setting visible=true, creating overlays")
        
        mainHandler.post {
            createStatusOverlay()
            createBboxOverlay()
            Logger.info("[FloatingOverlay] showOverlays: overlays created successfully")
        }
    }

    private fun hideOverlays() {
        isVisible.set(false)
        recordingAnimator?.let { mainHandler.removeCallbacks(it) }
        recordingAnimator = null
        
        mainHandler.post {
            try {
                statusView?.let { windowManager.removeView(it) }
            } catch (e: Exception) { }
            statusView = null
            
            try {
                bboxOverlayView?.let { windowManager.removeView(it) }
            } catch (e: Exception) { }
            bboxOverlayView = null
            
            txtStatus = null
            txtFps = null
            txtLatency = null
            txtDetections = null
        }
    }

    private fun createStatusOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 14, 24, 14)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#EE111111"))
                setStroke(3, Color.parseColor("#555555"))
                cornerRadius = 16f
            }
            elevation = 32f
        }

        txtStatus = createStatusText("REC", Color.parseColor("#F44336"))
        txtFps = createStatusText("-- FPS", Color.parseColor("#4CAF50"))
        txtLatency = createStatusText("--ms", Color.parseColor("#FF9800"))
        txtDetections = createStatusText("0 det", Color.parseColor("#2196F3"))

        statusLayout.addView(txtStatus)
        statusLayout.addView(createSpacer())
        statusLayout.addView(txtFps)
        statusLayout.addView(createSpacer())
        statusLayout.addView(txtLatency)
        statusLayout.addView(createSpacer())
        statusLayout.addView(txtDetections)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 100
        }

        try {
            windowManager.addView(statusLayout, params)
            statusView = statusLayout
            startRecordingAnimation()
        } catch (e: Exception) {
            Logger.error("[FloatingOverlay] Error al crear status overlay: ${e.message}")
        }
    }

    private fun createBboxOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bboxView = BboxOverlayView(this)
        bboxView.setScreenDimensions(screenWidth, screenHeight)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(bboxView, params)
            bboxOverlayView = bboxView
        } catch (e: Exception) {
            Logger.error("[FloatingOverlay] Error al crear bbox overlay: ${e.message}")
        }
    }

    private fun createStatusText(text: String, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12, 6, 12, 6)
        }
    }

    private fun createSpacer(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(2, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(6, 6, 6, 6)
            }
            setBackgroundColor(Color.parseColor("#555555"))
        }
    }

    private fun startRecordingAnimation() {
        recordingAnimator?.let { mainHandler.removeCallbacks(it) }
        
        recordingAnimator = object : Runnable {
            override fun run() {
                if (!isVisible.get()) return
                
                isRecordingDotVisible = !isRecordingDotVisible
                txtStatus?.alpha = if (isRecordingDotVisible) 1f else 0.3f
                
                mainHandler.postDelayed(this, 500)
            }
        }
        mainHandler.post(recordingAnimator!!)
    }

    private fun updateStatsInternal(fps: Double, latency: Long, detectionCount: Int) {
        if (!isVisible.get()) {
            Logger.info("[FloatingOverlay] updateStatsInternal: overlay not visible, skipping")
            return
        }
        
        mainHandler.post {
            txtFps?.text = "%.1f FPS".format(fps)
            txtFps?.setTextColor(when {
                fps >= 20 -> Color.parseColor("#4CAF50")
                fps >= 10 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            })
            
            txtLatency?.text = "${latency}ms"
            txtLatency?.setTextColor(when {
                latency <= 50 -> Color.parseColor("#4CAF50")
                latency <= 100 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            })
            
            txtDetections?.text = "$detectionCount det"
            txtDetections?.setTextColor(when {
                detectionCount > 0 -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#2196F3")
            })
        }
    }

    private fun updateDetectionsInternal(detections: ArrayList<Detection>, sourceWidth: Int, sourceHeight: Int) {
        mainHandler.post {
            bboxOverlayView?.setSourceDimensions(sourceWidth, sourceHeight)
            bboxOverlayView?.updateDetections(detections)
        }
    }

    private fun clearDetectionsInternal() {
        mainHandler.post {
            bboxOverlayView?.clearDetections()
        }
    }

    private fun setRecordingInternal(isRecording: Boolean) {
        mainHandler.post {
            if (isRecording) {
                txtStatus?.text = "REC"
                txtStatus?.setTextColor(Color.parseColor("#F44336"))
                startRecordingAnimation()
            } else {
                recordingAnimator?.let { mainHandler.removeCallbacks(it) }
                recordingAnimator = null
                txtStatus?.text = "STOP"
                txtStatus?.setTextColor(Color.parseColor("#9E9E9E"))
                txtStatus?.alpha = 1f
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlays()
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Flotante",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Overlay flotante de detecciones"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ONNX Overlay Activo")
            .setContentText("Mostrando detecciones sobre pantalla")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    inner class BboxOverlayView(context: Context) : View(context) {
        
        private val detections = mutableListOf<Detection>()
        private var sourceWidth = 0
        private var sourceHeight = 0
        private val df = DecimalFormat("0.0%")
        
        private var poseKeypoints = floatArrayOf()
        private var aimX = 0f
        private var aimY = 0f
        private var fovRadius = 0f
        private var showSkeleton = true
        private var showFov = true
        private var showPrediction = true
        
        private var showCrosshair = true
        private var crosshairStyle = "dot"
        private var crosshairColor = "#000000"
        private var crosshairSize = 3
        private var showHeadDot = true
        private var headDotColor = "#00FFFF"
        private var headDotSize = 4
        private var skeletonColorStr = "#FFFFFF"
        private var fovColorStr = "rgba(255,255,255,0.3)"
        private var tracersColorStr = "rgba(255,255,255,0.9)"
        private var showIgnoreRegion = false
        private var poseCount = 0
        
        private val skeletonConnections = listOf(
            0 to 1, 0 to 2, 1 to 3, 2 to 4,
            5 to 7, 6 to 8, 7 to 9, 8 to 10,
            5 to 6, 5 to 11, 6 to 12, 11 to 12,
            11 to 13, 12 to 14, 13 to 15, 14 to 16
        )
        
        private val COLORS = listOf(
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#E91E63"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#FFEB3B"),
            Color.parseColor("#795548")
        )

        private val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        
        private val outlinePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 10f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        private val bgPaint = Paint().apply {
            style = Paint.Style.FILL
        }
        
        private val summaryBgPaint = Paint().apply {
            color = Color.parseColor("#EE000000")
            style = Paint.Style.FILL
        }
        
        private val summaryTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        fun setScreenDimensions(width: Int, height: Int) {
            this.sourceWidth = width
            this.sourceHeight = height
        }
        
        fun setSourceDimensions(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                this.sourceWidth = width
                this.sourceHeight = height
                android.util.Log.d("BboxOverlayView", "setSourceDimensions: ${width}x${height}")
            }
        }

        fun updateDetections(newDetections: List<Detection>) {
            detections.clear()
            detections.addAll(newDetections)
            android.util.Log.d("BboxOverlayView", "updateDetections: ${newDetections.size} detecciones, srcDim=${sourceWidth}x${sourceHeight}, viewDim=${width}x${height}")
            if (newDetections.isNotEmpty()) {
                val first = newDetections[0]
                android.util.Log.d("BboxOverlayView", "Primera detección: ${first.className} bbox=${first.bbox}")
            }
            invalidate()
        }

        fun clearDetections() {
            detections.clear()
            invalidate()
        }
        
        fun updatePose(
            keypoints: FloatArray,
            aimX: Float,
            aimY: Float,
            fovRadius: Float,
            showSkeleton: Boolean,
            showFov: Boolean,
            showPrediction: Boolean
        ) {
            this.poseKeypoints = keypoints
            this.aimX = aimX
            this.aimY = aimY
            this.fovRadius = fovRadius
            this.showSkeleton = showSkeleton
            this.showFov = showFov
            this.showPrediction = showPrediction
            invalidate()
        }
        
        fun updatePoseExtended(
            keypoints: FloatArray,
            aimX: Float,
            aimY: Float,
            fovRadius: Float,
            showSkeleton: Boolean,
            showFov: Boolean,
            showTracers: Boolean,
            showCrosshair: Boolean,
            crosshairStyle: String,
            crosshairColor: String,
            crosshairSize: Int,
            showHeadDot: Boolean,
            headDotColor: String,
            headDotSize: Int,
            skeletonColor: String,
            fovColor: String,
            tracersColor: String,
            showIgnoreRegion: Boolean,
            poseCount: Int
        ) {
            this.poseKeypoints = keypoints
            this.aimX = aimX
            this.aimY = aimY
            this.fovRadius = fovRadius
            this.showSkeleton = showSkeleton
            this.showFov = showFov
            this.showPrediction = showTracers
            this.showCrosshair = showCrosshair
            this.crosshairStyle = crosshairStyle
            this.crosshairColor = crosshairColor
            this.crosshairSize = crosshairSize
            this.showHeadDot = showHeadDot
            this.headDotColor = headDotColor
            this.headDotSize = headDotSize
            this.skeletonColorStr = skeletonColor
            this.fovColorStr = fovColor
            this.tracersColorStr = tracersColor
            this.showIgnoreRegion = showIgnoreRegion
            this.poseCount = poseCount
            invalidate()
        }
        
        fun clearPose() {
            poseKeypoints = floatArrayOf()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            if (width <= 0 || height <= 0) return
            
            drawPoseVisuals(canvas)
            
            if (detections.isEmpty()) return
            
            // Use screen dimensions as fallback if source dimensions not set
            val effectiveSrcWidth = if (sourceWidth > 0) sourceWidth else this@FloatingOverlayService.screenWidth.takeIf { it > 0 } ?: width
            val effectiveSrcHeight = if (sourceHeight > 0) sourceHeight else this@FloatingOverlayService.screenHeight.takeIf { it > 0 } ?: height
            
            android.util.Log.d("BboxOverlayView", "onDraw: detections=${detections.size}, effective=${effectiveSrcWidth}x${effectiveSrcHeight}, view=${width}x${height}")
            
            val srcAspect = effectiveSrcWidth.toFloat() / effectiveSrcHeight.toFloat()
            val dstAspect = width.toFloat() / height.toFloat()
            
            val scale: Float
            val offsetX: Float
            val offsetY: Float
            
            if (srcAspect > dstAspect) {
                scale = width.toFloat() / effectiveSrcWidth.toFloat()
                offsetX = 0f
                offsetY = (height - effectiveSrcHeight * scale) / 2f
            } else {
                scale = height.toFloat() / effectiveSrcHeight.toFloat()
                offsetX = (width - effectiveSrcWidth * scale) / 2f
                offsetY = 0f
            }

            for ((index, det) in detections.withIndex()) {
                if (det.bbox.width() <= 0 || det.bbox.height() <= 0) continue
                
                val color = COLORS[det.classId % COLORS.size]
                strokePaint.color = color
                bgPaint.color = (color and 0x00FFFFFF) or 0xDD000000.toInt()

                val scaledBox = RectF(
                    (det.bbox.left * scale + offsetX).coerceIn(5f, width.toFloat() - 5f),
                    (det.bbox.top * scale + offsetY).coerceIn(5f, height.toFloat() - 5f),
                    (det.bbox.right * scale + offsetX).coerceIn(5f, width.toFloat() - 5f),
                    (det.bbox.bottom * scale + offsetY).coerceIn(5f, height.toFloat() - 5f)
                )
                
                if (scaledBox.width() <= 10 || scaledBox.height() <= 10) continue

                canvas.drawRect(scaledBox, outlinePaint)
                canvas.drawRect(scaledBox, strokePaint)

                val labelText = "${det.className} ${(det.confidence * 100).toInt()}%"
                val textWidth = textPaint.measureText(labelText)
                val textHeight = 48f
                val labelTop = (scaledBox.top - textHeight - 6).coerceAtLeast(0f)

                canvas.drawRect(
                    scaledBox.left - 4,
                    labelTop,
                    scaledBox.left + textWidth + 24,
                    labelTop + textHeight + 4,
                    bgPaint
                )

                canvas.drawText(labelText, scaledBox.left + 10, labelTop + textHeight - 10, textPaint)
            }
            
            if (detections.isNotEmpty()) {
                drawSummary(canvas)
            }
        }
        
        private fun drawSummary(canvas: Canvas) {
            val padding = 20f
            val lineHeight = 36f
            val maxToShow = minOf(detections.size, 5)
            val summaryHeight = (maxToShow + 1) * lineHeight + padding * 2 + 
                (if (detections.size > maxToShow) lineHeight else 0f)
            val summaryWidth = 300f
            
            val left = width - summaryWidth - 20f
            val top = 120f
            
            val rect = RectF(left, top, left + summaryWidth, top + summaryHeight)
            canvas.drawRoundRect(rect, 16f, 16f, summaryBgPaint)
            
            summaryTextPaint.textSize = 32f
            summaryTextPaint.color = Color.WHITE
            canvas.drawText("Detecciones: ${detections.size}", left + padding, top + padding + 28f, summaryTextPaint)
            
            summaryTextPaint.textSize = 28f
            for (i in 0 until maxToShow) {
                val det = detections[i]
                val color = COLORS[det.classId % COLORS.size]
                summaryTextPaint.color = color
                val text = "${det.className}: ${df.format(det.confidence)}"
                canvas.drawText(text, left + padding, top + padding + 28f + (i + 1) * lineHeight, summaryTextPaint)
            }
            
            if (detections.size > maxToShow) {
                summaryTextPaint.color = Color.GRAY
                summaryTextPaint.textSize = 24f
                val moreText = "...y ${detections.size - maxToShow} mas"
                canvas.drawText(moreText, left + padding, top + padding + 28f + (maxToShow + 1) * lineHeight, summaryTextPaint)
            }
        }
        
        private fun parseColor(colorStr: String): Int {
            return try {
                when {
                    colorStr.startsWith("rgba(") -> {
                        val parts = colorStr.removePrefix("rgba(").removeSuffix(")").split(",")
                        if (parts.size >= 4) {
                            val r = parts[0].trim().toInt()
                            val g = parts[1].trim().toInt()
                            val b = parts[2].trim().toInt()
                            val a = (parts[3].trim().toFloat() * 255).toInt()
                            Color.argb(a, r, g, b)
                        } else Color.WHITE
                    }
                    colorStr.startsWith("#") -> Color.parseColor(colorStr)
                    else -> Color.WHITE
                }
            } catch (e: Exception) {
                Color.WHITE
            }
        }
        
        private fun drawPoseVisuals(canvas: Canvas) {
            val centerX = width / 2f
            val centerY = height / 2f
            
            val posePaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
            }
            
            // ALWAYS draw FOV circle first (even without keypoints)
            if (showFov && fovRadius > 0) {
                posePaint.color = parseColor(fovColorStr)
                posePaint.strokeWidth = 4f
                posePaint.style = Paint.Style.STROKE
                canvas.drawCircle(centerX, centerY, fovRadius, posePaint)
            }
            
            // ALWAYS draw crosshair at aim point (even without keypoints)
            if (showCrosshair && aimX > 0 && aimY > 0) {
                posePaint.color = parseColor(crosshairColor)
                val size = crosshairSize.toFloat() * 4
                
                when (crosshairStyle) {
                    "dot" -> {
                        posePaint.style = Paint.Style.FILL
                        canvas.drawCircle(aimX, aimY, size, posePaint)
                    }
                    "cross" -> {
                        posePaint.style = Paint.Style.STROKE
                        posePaint.strokeWidth = 3f
                        canvas.drawLine(aimX - size, aimY, aimX + size, aimY, posePaint)
                        canvas.drawLine(aimX, aimY - size, aimX, aimY + size, posePaint)
                    }
                    "circle" -> {
                        posePaint.style = Paint.Style.STROKE
                        posePaint.strokeWidth = 3f
                        canvas.drawCircle(aimX, aimY, size, posePaint)
                    }
                }
                
                // Outer ring for visibility
                posePaint.color = Color.WHITE
                posePaint.style = Paint.Style.STROKE
                posePaint.strokeWidth = 2f
                canvas.drawCircle(aimX, aimY, size + 4, posePaint)
            }
            
            // Return early if no keypoints for skeleton drawing
            if (poseKeypoints.isEmpty()) return
            
            val numKeypoints = poseKeypoints.size / 2
            if (numKeypoints < 17) return
            
            // Draw skeleton
            if (showSkeleton) {
                posePaint.color = parseColor(skeletonColorStr)
                posePaint.strokeWidth = 6f
                posePaint.style = Paint.Style.STROKE
                
                for ((a, b) in skeletonConnections) {
                    if (a >= numKeypoints || b >= numKeypoints) continue
                    val x1 = poseKeypoints[a * 2]
                    val y1 = poseKeypoints[a * 2 + 1]
                    val x2 = poseKeypoints[b * 2]
                    val y2 = poseKeypoints[b * 2 + 1]
                    
                    if (x1 > 0 && y1 > 0 && x2 > 0 && y2 > 0) {
                        canvas.drawLine(x1, y1, x2, y2, posePaint)
                    }
                }
                
                // Draw keypoints
                posePaint.style = Paint.Style.FILL
                posePaint.color = Color.GREEN
                for (i in 0 until numKeypoints) {
                    val x = poseKeypoints[i * 2]
                    val y = poseKeypoints[i * 2 + 1]
                    if (x > 0 && y > 0) {
                        canvas.drawCircle(x, y, 8f, posePaint)
                    }
                }
            }
            
            // Draw head dot
            if (showHeadDot && poseKeypoints.size >= 2) {
                val noseX = poseKeypoints[0]
                val noseY = poseKeypoints[1]
                if (noseX > 0 && noseY > 0) {
                    posePaint.color = parseColor(headDotColor)
                    posePaint.style = Paint.Style.FILL
                    canvas.drawCircle(noseX, noseY, headDotSize.toFloat() * 3, posePaint)
                }
            }
            
            // Draw tracers (line from head to aim point)
            if (showPrediction && aimX > 0 && aimY > 0) {
                val noseX = if (poseKeypoints.size >= 2) poseKeypoints[0] else 0f
                val noseY = if (poseKeypoints.size >= 2) poseKeypoints[1] else 0f
                
                if (noseX > 0 && noseY > 0) {
                    posePaint.color = parseColor(tracersColorStr)
                    posePaint.strokeWidth = 4f
                    posePaint.style = Paint.Style.STROKE
                    canvas.drawLine(noseX, noseY, aimX, aimY, posePaint)
                }
            }
            
            // Draw pose count indicator
            if (poseCount > 0) {
                val infoText = "Poses: $poseCount"
                summaryTextPaint.color = Color.GREEN
                summaryTextPaint.textSize = 28f
                canvas.drawText(infoText, 24f, height - 24f, summaryTextPaint)
            }
        }
    }
}
