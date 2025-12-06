package com.example.onnxsc

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "onnx_screen_capture"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        
        private var frameCallback: ((Bitmap) -> Unit)? = null
        private var errorCallback: ((String) -> Unit)? = null
        private var startedCallback: (() -> Unit)? = null
        private var stoppedCallback: (() -> Unit)? = null
        
        fun setCallbacks(
            onFrame: ((Bitmap) -> Unit)?,
            onError: ((String) -> Unit)?,
            onStarted: (() -> Unit)?,
            onStopped: (() -> Unit)?
        ) {
            frameCallback = onFrame
            errorCallback = onError
            startedCallback = onStarted
            stoppedCallback = onStopped
        }
        
        fun clearCallbacks() {
            frameCallback = null
            errorCallback = null
            startedCallback = null
            stoppedCallback = null
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val isCapturing = AtomicBoolean(false)
    private val lastProcessedTime = AtomicLong(0)
    private val minFrameInterval = 16L // ~60 FPS target (optimizado para Snapdragon 888+)
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var captureWidth = 0
    private var captureHeight = 0
    
    // Optimización: capturar a resolución reducida para mejor rendimiento
    private val captureScale = 0.75f // 75% de resolución = mejor FPS sin perder calidad de detección

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { 
                stopCapture()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                handleStart(intent)
            }
        }
        return START_NOT_STICKY
    }
    
    private fun handleStart(intent: Intent?) {
        val notification = createNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            notifyError("Error iniciando servicio foreground: ${e.message}")
            stopSelf()
            return
        }

        mainHandler.postDelayed({
            initializeMediaProjection(intent)
        }, 100)
    }
    
    private fun initializeMediaProjection(intent: Intent?) {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) 
            ?: Activity.RESULT_CANCELED
            
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyError("Datos de permiso inválidos")
            stopSelf()
            return
        }
        
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            
            if (mediaProjection == null) {
                notifyError("No se pudo obtener MediaProjection")
                stopSelf()
                return
            }
            
            setupCapture()
            
        } catch (e: SecurityException) {
            e.printStackTrace()
            notifyError("Error de seguridad: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
            notifyError("Error obteniendo MediaProjection: ${e.message}")
            stopSelf()
        }
    }
    
    private fun setupCapture() {
        if (isCapturing.get()) {
            notifyError("Ya hay una captura en curso")
            return
        }
        
        val projection = mediaProjection
        if (projection == null) {
            notifyError("MediaProjection no disponible")
            return
        }
        
        try {
            projection.registerCallback(projectionCallback, mainHandler)
            
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
                screenDensity = resources.configuration.densityDpi
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                screenDensity = metrics.densityDpi
            }
            
            // Optimización: capturar a resolución reducida para mejor FPS
            captureWidth = (screenWidth * captureScale).toInt()
            captureHeight = (screenHeight * captureScale).toInt()
            
            // Thread de alta prioridad para captura
            captureThread = HandlerThread("ScreenCapture", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
            captureHandler = Handler(captureThread!!.looper)
            
            // Buffer de 3 imágenes para pipeline más fluido
            imageReader = ImageReader.newInstance(
                captureWidth, 
                captureHeight, 
                PixelFormat.RGBA_8888, 
                3
            )
            
            virtualDisplay = projection.createVirtualDisplay(
                "OnnxScreenCapture",
                captureWidth, 
                captureHeight, 
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, 
                captureHandler
            )
            
            if (virtualDisplay == null) {
                notifyError("No se pudo crear VirtualDisplay")
                cleanup()
                stopSelf()
                return
            }
            
            isCapturing.set(true)
            setupImageListener()
            
            mainHandler.post {
                startedCallback?.invoke()
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            notifyError("Error configurando captura: ${e.message}")
            cleanup()
            stopSelf()
        }
    }
    
    private fun setupImageListener() {
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isCapturing.get()) {
                try { reader.acquireLatestImage()?.close() } catch (e: Exception) { }
                return@setOnImageAvailableListener
            }
            
            val now = System.currentTimeMillis()
            if (now - lastProcessedTime.get() < minFrameInterval) {
                try {
                    reader.acquireLatestImage()?.close()
                } catch (e: Exception) { }
                return@setOnImageAvailableListener
            }
            
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            
            if (image == null) return@setOnImageAvailableListener
            
            var bitmap: Bitmap? = null
            var finalBitmap: Bitmap? = null
            
            try {
                lastProcessedTime.set(now)
                
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * captureWidth
                
                val bitmapWidth = captureWidth + rowPadding / pixelStride
                bitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                
                finalBitmap = if (bitmapWidth > captureWidth) {
                    val bitmapToRecycle = bitmap
                    Bitmap.createBitmap(bitmapToRecycle, 0, 0, captureWidth, captureHeight).also {
                        bitmapToRecycle?.recycle()
                        bitmap = null
                    }
                } else {
                    bitmap
                }
                
                val bitmapToSend = finalBitmap
                finalBitmap = null
                
                mainHandler.post {
                    try {
                        frameCallback?.invoke(bitmapToSend!!)
                    } catch (e: Exception) {
                        try { bitmapToSend?.recycle() } catch (_: Exception) { }
                    }
                }
                
            } catch (e: OutOfMemoryError) {
                try { image.close() } catch (_: Exception) { }
                try { bitmap?.recycle() } catch (_: Exception) { }
                try { finalBitmap?.recycle() } catch (_: Exception) { }
                System.gc()
            } catch (e: Exception) {
                try { image.close() } catch (_: Exception) { }
                try { bitmap?.recycle() } catch (_: Exception) { }
                try { finalBitmap?.recycle() } catch (_: Exception) { }
                mainHandler.post {
                    errorCallback?.invoke("Error procesando frame: ${e.message}")
                }
            }
        }, captureHandler)
    }
    
    private fun stopCapture() {
        if (!isCapturing.getAndSet(false)) {
            stopSelf()
            return
        }
        
        cleanup()
        
        mainHandler.post {
            stoppedCallback?.invoke()
        }
        
        stopSelf()
    }
    
    private fun cleanup() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (e: Exception) { }
        
        try {
            imageReader?.close()
        } catch (e: Exception) { }
        imageReader = null
        
        try {
            virtualDisplay?.release()
        } catch (e: Exception) { }
        virtualDisplay = null
        
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) { }
        
        try {
            mediaProjection?.stop()
        } catch (e: Exception) { }
        mediaProjection = null
        
        try {
            captureThread?.quitSafely()
        } catch (e: Exception) { }
        captureThread = null
        captureHandler = null
        
        isCapturing.set(false)
    }
    
    private fun notifyError(message: String) {
        mainHandler.post {
            errorCallback?.invoke(message)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Captura de Pantalla",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación de captura de pantalla activa"
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
            .setContentTitle("ONNX Screen")
            .setContentText("Capturando pantalla...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
