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
        }
        return START_NOT_STICKY
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
        if (isVisible.get()) return
        isVisible.set(true)
        
        mainHandler.post {
            createStatusOverlay()
            createBboxOverlay()
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
        if (!isVisible.get()) return
        
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
            }
        }

        fun updateDetections(newDetections: List<Detection>) {
            detections.clear()
            detections.addAll(newDetections)
            invalidate()
        }

        fun clearDetections() {
            detections.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            if (detections.isEmpty()) return
            if (width <= 0 || height <= 0) return
            
            val effectiveSrcWidth = if (sourceWidth > 0) sourceWidth else width
            val effectiveSrcHeight = if (sourceHeight > 0) sourceHeight else height
            
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
    }
}
