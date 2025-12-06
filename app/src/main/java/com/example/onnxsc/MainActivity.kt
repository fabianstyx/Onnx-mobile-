package com.example.onnxsc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.onnxsc.databinding.ActivityMainBinding
import com.example.onnxsc.databinding.DialogPostprocessConfigBinding
import com.example.onnxsc.engine.ActionsApi
import com.example.onnxsc.engine.InferenceInputHandler
import com.example.onnxsc.engine.ScriptLogger
import com.example.onnxsc.engine.ScriptRuntime
import com.example.onnxsc.engine.ScriptStorage
import com.example.onnxsc.ui.ScriptEditorActivity
import com.google.android.material.slider.Slider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null
    private var lastInspection: ModelInspector.Inspection? = null
    private var lastDetailedInspection: ModelInspector.DetailedInspection? = null
    private lateinit var modelSwitcher: ModelSwitcher
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isCapturing = AtomicBoolean(false)
    private var lastCapturedBitmap: Bitmap? = null
    private var lastDetections: List<Detection> = emptyList()
    
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private val isProcessing = AtomicBoolean(false)
    private val pendingFrames = AtomicInteger(0)
    private val MAX_PENDING_FRAMES = 2

    private var currentDetectionCount = 0
    private var isShowingStatus = false
    private var useFloatingOverlay = true
    private var pendingCaptureAfterOverlayPermission = false

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Logger.warn("No se pudo obtener permiso persistente")
                }
                loadNewModel(it)
            } ?: Logger.info("No se eligio archivo")
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Logger.info("Permiso de captura concedido")
                startCaptureService(result.resultCode, result.data!!)
            } else {
                Logger.error("Permiso de captura DENEGADO")
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                Logger.info("Permisos concedidos")
            } else {
                Logger.warn("Algunos permisos no fueron concedidos")
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (Settings.canDrawOverlays(this)) {
                Logger.success("Permiso de overlay concedido")
                if (pendingCaptureAfterOverlayPermission) {
                    pendingCaptureAfterOverlayPermission = false
                    requestScreenCapture()
                }
            } else {
                Logger.warn("Permiso de overlay denegado - usando overlay interno")
                useFloatingOverlay = false
                if (pendingCaptureAfterOverlayPermission) {
                    pendingCaptureAfterOverlayPermission = false
                    requestScreenCapture()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ONNXSC)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Logger.init(binding.txtConsole)
        Logger.info("App iniciada - ONNX Screen v2.0")
        Logger.info("Nuevas funciones: Info modelo, Config post-proceso")

        modelSwitcher = ModelSwitcher(this)
        
        initProcessingThread()

        checkAndRequestPermissions()
        setupButtons()
    }
    
    private fun initProcessingThread() {
        processingThread?.quitSafely()
        processingThread = HandlerThread("OnnxProcessing").apply { 
            start() 
        }
        processingHandler = Handler(processingThread!!.looper)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun setupButtons() {
        binding.btnPickModel.setOnClickListener {
            pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.btnChangeModel.setOnClickListener {
            stopCaptureService()
            ResultOverlay.clear(binding.overlayContainer)
            OnnxProcessor.closeSession()
            FpsMeter.reset()
            modelSwitcher.pick { newUri: Uri ->
                loadNewModel(newUri)
            }
        }

        binding.btnCapture.setOnClickListener {
            if (isCapturing.get()) {
                stopCaptureService()
            } else {
                requestScreenCapture()
            }
        }

        binding.btnClearConsole.setOnClickListener { Logger.clear() }

        binding.btnSaveCapture.setOnClickListener {
            if (lastCapturedBitmap != null) {
                saveCaptureWithOverlay()
            } else {
                Logger.warn("No hay captura disponible para guardar")
            }
        }

        binding.btnModelInfo.setOnClickListener {
            showModelInfoDialog()
        }

        binding.btnPostProcess.setOnClickListener {
            showPostProcessConfigDialog()
        }
    }

    private fun showModelInfoDialog() {
        val uri = modelUri
        if (uri == null) {
            Logger.warn("Primero selecciona un modelo para ver su informacion")
            return
        }

        Logger.info("Inspeccionando modelo...")
        
        Thread {
            val inspection = ModelInspector.inspectDetailed(this, contentResolver, uri)
            lastDetailedInspection = inspection
            
            mainHandler.post {
                val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_info, null)
                val txtModelInfo = dialogView.findViewById<TextView>(R.id.txtModelInfo)
                txtModelInfo.text = inspection.toFormattedString()

                AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton("Cerrar", null)
                    .show()
                    
                Logger.success("Inspeccion completada")
            }
        }.start()
    }

    private fun showPostProcessConfigDialog() {
        val dialogBinding = DialogPostprocessConfigBinding.inflate(LayoutInflater.from(this))
        
        val currentSettings = PostProcessingConfig.getCurrentSettings()
        
        dialogBinding.sliderConfidence.value = (currentSettings.confidenceThreshold * 100).coerceIn(1f, 99f)
        dialogBinding.txtConfidenceValue.text = "${currentSettings.confidenceThreshold.times(100).toInt()}%"
        
        dialogBinding.sliderNms.value = (currentSettings.nmsThreshold * 100).coerceIn(1f, 99f)
        dialogBinding.txtNmsValue.text = "${currentSettings.nmsThreshold.times(100).toInt()}%"
        
        dialogBinding.sliderMaxDet.value = currentSettings.maxDetections.toFloat().coerceIn(1f, 500f)
        dialogBinding.txtMaxDetValue.text = "${currentSettings.maxDetections}"
        
        currentSettings.enabledClasses?.let { classes ->
            dialogBinding.editEnabledClasses.setText(classes.joinToString(", "))
        }
        
        currentSettings.classNames?.let { names ->
            dialogBinding.editClassNames.setText(names.joinToString("\n"))
        }
        
        dialogBinding.sliderConfidence.addOnChangeListener { _, value, _ ->
            dialogBinding.txtConfidenceValue.text = "${value.toInt()}%"
        }
        
        dialogBinding.sliderNms.addOnChangeListener { _, value, _ ->
            dialogBinding.txtNmsValue.text = "${value.toInt()}%"
        }
        
        dialogBinding.sliderMaxDet.addOnChangeListener { _, value, _ ->
            dialogBinding.txtMaxDetValue.text = "${value.toInt()}"
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialogBinding.btnResetConfig.setOnClickListener {
            PostProcessingConfig.resetToDefaults()
            dialog.dismiss()
            Logger.info("Configuracion restablecida")
        }
        
        dialogBinding.btnSaveConfig.setOnClickListener {
            val newSettings = PostProcessingSettings(
                confidenceThreshold = dialogBinding.sliderConfidence.value / 100f,
                nmsThreshold = dialogBinding.sliderNms.value / 100f,
                maxDetections = dialogBinding.sliderMaxDet.value.toInt(),
                enabledClasses = parseEnabledClasses(dialogBinding.editEnabledClasses.text.toString()),
                classNames = parseClassNames(dialogBinding.editClassNames.text.toString())
            )
            
            PostProcessingConfig.updateSettings(newSettings)
            
            modelUri?.lastPathSegment?.let { modelName ->
                PostProcessingConfig.saveConfig(this, modelName)
            }
            
            dialog.dismiss()
            Logger.success("Configuracion aplicada")
            Logger.info(PostProcessingConfig.getConfigSummary())
        }
        
        dialog.show()
    }
    
    private fun parseEnabledClasses(input: String): List<Int>? {
        if (input.isBlank()) return null
        return try {
            input.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toInt() }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseClassNames(input: String): List<String>? {
        if (input.isBlank()) return null
        return input.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    private fun loadNewModel(uri: Uri) {
        modelUri = uri
        val modelName: String = uri.lastPathSegment?.substringAfterLast("/") ?: "desconocido"
        Logger.success("Modelo seleccionado: $modelName")

        binding.txtModel.text = modelName

        ResultOverlay.clear(binding.overlayContainer)
        FpsMeter.reset()

        val hasCustomConfig = PostProcessingConfig.loadConfig(this, modelName)

        inspectModel(uri)
        
        if (!hasCustomConfig) {
            applyEmbeddedModelConfig(uri, modelName)
        }

        Thread {
            val success = OnnxProcessor.loadModel(this, uri) { log ->
                mainHandler.post { Logger.info(log) }
            }
            mainHandler.post {
                if (success) {
                    Logger.success("Modelo listo para inferencia")
                } else {
                    Logger.error("Error al cargar el modelo")
                }
            }
        }.start()
    }
    
    private fun applyEmbeddedModelConfig(uri: Uri, modelName: String) {
        Thread {
            try {
                val embeddedConfig = ModelInspector.extractEmbeddedConfig(this, contentResolver, uri)
                
                if (embeddedConfig.hasCustomConfig()) {
                    mainHandler.post {
                        Logger.info("Configuracion embebida detectada: ${embeddedConfig.toSummary()}")
                        
                        val currentSettings = PostProcessingConfig.getCurrentSettings()
                        
                        val newSettings = currentSettings.copy(
                            confidenceThreshold = embeddedConfig.confidenceThreshold ?: currentSettings.confidenceThreshold,
                            nmsThreshold = embeddedConfig.nmsThreshold ?: currentSettings.nmsThreshold,
                            maxDetections = embeddedConfig.maxDetections ?: currentSettings.maxDetections,
                            classNames = embeddedConfig.classNames ?: currentSettings.classNames,
                            modelName = modelName
                        )
                        
                        PostProcessingConfig.updateSettings(newSettings)
                        Logger.success("Configuracion del modelo aplicada automaticamente")
                    }
                } else {
                    mainHandler.post {
                        Logger.info("No se encontro configuracion embebida en el modelo")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Logger.warn("No se pudo leer configuracion embebida: ${e.message}")
                }
            }
        }.start()
    }

    private fun inspectModel(uri: Uri) {
        try {
            lastInspection = contentResolver.let { ModelInspector.inspect(it, uri) }
            lastInspection?.let { ins ->
                Logger.info("Tamano: ${ins.sizeKb} KB")

                DependencyInstaller.checkAndInstall(this, ins) { log -> Logger.info(log) }

                if (ins.hasExternalWeights) {
                    ExternalWeightsManager.copyExternalWeights(this, contentResolver, uri) { log ->
                        Logger.info(log)
                    }
                }

                if (ins.hasJsOperators || ins.hasNodeOps) {
                    Logger.warn("Operadores especiales detectados - pueden no estar soportados")
                }
            }
        } catch (e: Exception) {
            Logger.error("Error al inspeccionar modelo: ${e.message}")
        }
    }

    private fun requestScreenCapture() {
        if (modelUri == null) {
            Logger.error("Primero selecciona un modelo ONNX")
            return
        }

        if (!OnnxProcessor.isModelLoaded()) {
            Logger.error("El modelo aun no esta cargado")
            return
        }

        if (useFloatingOverlay && !Settings.canDrawOverlays(this)) {
            Logger.info("Solicitando permiso para overlay flotante...")
            pendingCaptureAfterOverlayPermission = true
            requestOverlayPermission()
            return
        }

        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mpManager.createScreenCaptureIntent()
            captureLauncher.launch(intent)
        } catch (e: Exception) {
            Logger.error("Error al solicitar captura: ${e.message}")
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Logger.error("Error al solicitar permiso de overlay: ${e.message}")
            useFloatingOverlay = false
            pendingCaptureAfterOverlayPermission = false
            requestScreenCapture()
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        Logger.info("Iniciando servicio de captura...")
        
        StatusOverlay.reset()
        ResultOverlay.clear(binding.overlayContainer)
        
        if (useFloatingOverlay && Settings.canDrawOverlays(this)) {
            startFloatingOverlayService()
        }
        
        ScreenCaptureService.setCallbacks(
            onFrame = { bitmap ->
                processFrame(bitmap)
            },
            onError = { error ->
                Logger.error("[Captura] Error: $error")
            },
            onStarted = {
                Logger.info("[Callback] onStarted recibido")
                isCapturing.set(true)
                binding.btnCapture.text = "Detener captura"
                Logger.success("Captura iniciada - Procesando frames...")
                
                if (useFloatingOverlay && Settings.canDrawOverlays(this)) {
                    FloatingOverlayService.setRecording(this, true)
                    Logger.info("[Callback] FloatingOverlayService activo")
                } else {
                    StatusOverlay.show(binding.overlayContainer)
                    StatusOverlay.setRecording(true)
                    Logger.info("[Callback] StatusOverlay.show() ejecutado")
                }
                isShowingStatus = true
            },
            onStopped = {
                Logger.info("[Callback] onStopped recibido")
                isCapturing.set(false)
                binding.btnCapture.text = getString(R.string.capture)
                Logger.info("Captura detenida")
                
                if (useFloatingOverlay) {
                    FloatingOverlayService.setRecording(this, false)
                    stopFloatingOverlayService()
                } else {
                    StatusOverlay.setRecording(false)
                    StatusOverlay.hide(binding.overlayContainer)
                }
                isShowingStatus = false
            }
        )
        
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Logger.error("Error al iniciar servicio: ${e.message}")
            ScreenCaptureService.clearCallbacks()
        }
    }
    
    private fun stopCaptureService() {
        isCapturing.set(false)
        pendingFrames.set(0)
        currentDetectionCount = 0
        
        processingHandler?.removeCallbacksAndMessages(null)
        
        if (useFloatingOverlay) {
            FloatingOverlayService.setRecording(this, false)
            FloatingOverlayService.clearDetections(this)
            stopFloatingOverlayService()
        } else {
            StatusOverlay.setRecording(false)
            StatusOverlay.hide(binding.overlayContainer)
        }
        isShowingStatus = false
        
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(serviceIntent)
        } catch (e: Exception) {
            Logger.error("Error al detener servicio: ${e.message}")
        }
        
        binding.btnCapture.text = getString(R.string.capture)
    }

    private fun startFloatingOverlayService() {
        try {
            val intent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Logger.info("Servicio de overlay flotante iniciado")
        } catch (e: Exception) {
            Logger.error("Error al iniciar overlay flotante: ${e.message}")
            useFloatingOverlay = false
        }
    }

    private fun stopFloatingOverlayService() {
        try {
            val intent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            Logger.error("Error al detener overlay flotante: ${e.message}")
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        FpsMeter.tick { fps ->
            Logger.info(fps)
        }

        val uri = modelUri ?: run {
            safeRecycleBitmap(bitmap)
            return
        }
        
        val handler = processingHandler ?: run {
            safeRecycleBitmap(bitmap)
            return
        }

        if (pendingFrames.get() >= MAX_PENDING_FRAMES) {
            safeRecycleBitmap(bitmap)
            return
        }
        
        pendingFrames.incrementAndGet()
        
        handler.post {
            var bitmapCopy: Bitmap? = null
            try {
                if (!isCapturing.get()) {
                    safeRecycleBitmap(bitmap)
                    pendingFrames.decrementAndGet()
                    return@post
                }
                
                bitmapCopy = try {
                    if (!bitmap.isRecycled) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else null
                } catch (e: Exception) { null }
                
                safeRecycleBitmap(bitmap)
                
                if (bitmapCopy == null) {
                    pendingFrames.decrementAndGet()
                    return@post
                }
                
                val settings = PostProcessingConfig.getCurrentSettings()
                
                val result = OnnxProcessor.processImageWithConfig(
                    this, 
                    uri, 
                    bitmapCopy!!, 
                    settings
                ) { log ->
                    mainHandler.post { Logger.info(log) }
                }
                
                val finalBitmapCopy = bitmapCopy
                bitmapCopy = null

                val bitmapWidth = finalBitmapCopy?.width ?: 0
                val bitmapHeight = finalBitmapCopy?.height ?: 0
                
                mainHandler.post {
                    try {
                        if (result != null && isCapturing.get()) {
                            safeRecycleBitmap(lastCapturedBitmap)
                            lastCapturedBitmap = finalBitmapCopy
                            lastDetections = result.allDetections
                            
                            currentDetectionCount = result.allDetections.size
                            val fps = FpsMeter.getCurrentFps()
                            val latency = FpsMeter.getCurrentLatency()
                            
                            if (useFloatingOverlay && Settings.canDrawOverlays(this)) {
                                FloatingOverlayService.updateStats(this, fps, latency, currentDetectionCount)
                                
                                if (result.allDetections.isNotEmpty()) {
                                    Logger.info("[Frame] ${result.allDetections.size} detecciones, FPS=${"%.1f".format(fps)}, ${bitmapWidth}x${bitmapHeight}")
                                    FloatingOverlayService.updateDetections(
                                        this,
                                        ArrayList(result.allDetections),
                                        bitmapWidth,
                                        bitmapHeight
                                    )
                                } else {
                                    FloatingOverlayService.clearDetections(this)
                                    if (result.probability > 0) {
                                        Logger.info("[Frame] Clasificacion: ${result.className} (${result.probability})")
                                    }
                                }
                            } else {
                                StatusOverlay.updateStats(fps, latency, currentDetectionCount)
                                
                                ResultOverlay.setSourceDimensions(bitmapWidth, bitmapHeight)
                                ResultOverlay.clear(binding.overlayContainer)
                                if (result.allDetections.isNotEmpty()) {
                                    Logger.info("[Frame] ${result.allDetections.size} detecciones, FPS=${"%.1f".format(fps)}, ${bitmapWidth}x${bitmapHeight}")
                                    ResultOverlay.showMultiple(binding.overlayContainer, result.allDetections)
                                } else if (result.probability > 0) {
                                    Logger.info("[Frame] Clasificacion: ${result.className} (${result.probability})")
                                    ResultOverlay.show(
                                        binding.overlayContainer,
                                        result.className,
                                        result.probability,
                                        result.bbox
                                    )
                                }
                            }
                        } else {
                            safeRecycleBitmap(finalBitmapCopy)
                        }
                    } catch (e: Exception) {
                        Logger.error("[Frame] Error mostrando resultado: ${e.message}")
                        safeRecycleBitmap(finalBitmapCopy)
                    } finally {
                        pendingFrames.decrementAndGet()
                    }
                }
            } catch (e: Exception) {
                safeRecycleBitmap(bitmap)
                safeRecycleBitmap(bitmapCopy)
                pendingFrames.decrementAndGet()
                mainHandler.post { 
                    Logger.error("Error procesando frame: ${e.message}") 
                }
            }
        }
    }
    
    private fun safeRecycleBitmap(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (e: Exception) { }
    }

    private fun saveCaptureWithOverlay() {
        val bitmap = lastCapturedBitmap ?: run {
            Logger.warn("No hay frame para guardar")
            return
        }

        Thread {
            try {
                val overlayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(overlayBitmap)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4f
                    isAntiAlias = true
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 32f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
                }

                for (det in lastDetections) {
                    if (det.bbox.width() > 0 && det.bbox.height() > 0) {
                        canvas.drawRect(det.bbox, paint)
                        val label = "${det.className} ${(det.confidence * 100).toInt()}%"
                        canvas.drawText(label, det.bbox.left + 8, det.bbox.top - 8, textPaint)
                    }
                }

                if (lastDetections.isEmpty()) {
                    val infoText = "ONNX Screen - Sin detecciones"
                    canvas.drawText(infoText, 20f, 50f, textPaint)
                }

                val filename = "onnx_capture_${System.currentTimeMillis()}.png"
                GallerySaver.save(this, overlayBitmap, filename) { log ->
                    mainHandler.post { 
                        Logger.success(log)
                        overlayBitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { Logger.error("Error al guardar: ${e.message}") }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCaptureService()
        ScreenCaptureService.clearCallbacks()
        
        StatusOverlay.hide(binding.overlayContainer)
        isShowingStatus = false
        
        try {
            processingThread?.quitSafely()
        } catch (e: Exception) { }
        processingThread = null
        processingHandler = null
        
        safeRecycleBitmap(lastCapturedBitmap)
        lastCapturedBitmap = null
        lastDetections = emptyList()
        
        OnnxProcessor.closeSession()
    }
}
