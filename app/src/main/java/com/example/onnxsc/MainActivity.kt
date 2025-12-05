package com.example.onnxsc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.onnxsc.databinding.ActivityMainBinding
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null
    private var lastInspection: ModelInspector.Inspection? = null
    private lateinit var modelSwitcher: ModelSwitcher
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isCapturing = AtomicBoolean(false)
    private var lastCapturedBitmap: Bitmap? = null
    private var lastDetections: List<Detection> = emptyList()

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Logger.warn("No se pudo obtener permiso persistente")
                }
                loadNewModel(it)
            } ?: Logger.info("No se eligió archivo")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ONNXSC)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Logger.init(binding.txtConsole)
        Logger.info("App iniciada - ONNX Screen v1.0")

        modelSwitcher = ModelSwitcher(this)

        checkAndRequestPermissions()
        setupButtons()
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
    }

    private fun loadNewModel(uri: Uri) {
        modelUri = uri
        val modelName: String = uri.lastPathSegment?.substringAfterLast("/") ?: "desconocido"
        Logger.success("Modelo seleccionado: $modelName")

        binding.txtModel.text = modelName

        ResultOverlay.clear(binding.overlayContainer)
        FpsMeter.reset()

        inspectModel(uri)

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

    private fun inspectModel(uri: Uri) {
        try {
            lastInspection = contentResolver.let { ModelInspector.inspect(it, uri) }
            lastInspection?.let { ins ->
                Logger.info("Tamaño: ${ins.sizeKb} KB")

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
            Logger.error("El modelo aún no está cargado")
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

    private fun startCaptureService(resultCode: Int, data: Intent) {
        Logger.info("Iniciando servicio de captura...")
        
        ScreenCaptureService.setCallbacks(
            onFrame = { bitmap ->
                processFrame(bitmap)
            },
            onError = { error ->
                Logger.error(error)
            },
            onStarted = {
                isCapturing.set(true)
                binding.btnCapture.text = "Detener captura"
                Logger.success("Captura iniciada - Procesando frames...")
            },
            onStopped = {
                isCapturing.set(false)
                binding.btnCapture.text = getString(R.string.capture)
                Logger.info("Captura detenida")
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
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(serviceIntent)
        } catch (e: Exception) {
            Logger.error("Error al detener servicio: ${e.message}")
        }
        
        isCapturing.set(false)
        binding.btnCapture.text = getString(R.string.capture)
    }

    private fun processFrame(bitmap: Bitmap) {
        FpsMeter.tick { fps ->
            Logger.info(fps)
        }

        val uri = modelUri ?: run {
            bitmap.recycle()
            return
        }

        Thread {
            val result = OnnxProcessor.processImage(this, uri, bitmap) { log ->
                mainHandler.post { Logger.info(log) }
            }

            mainHandler.post {
                if (result != null) {
                    Logger.success("${result.className} (${(result.probability * 100).toInt()}%) - ${result.latencyMs}ms")

                    lastCapturedBitmap?.recycle()
                    lastCapturedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    lastDetections = result.allDetections

                    ResultOverlay.clear(binding.overlayContainer)
                    if (result.allDetections.isNotEmpty()) {
                        ResultOverlay.showMultiple(binding.overlayContainer, result.allDetections)
                    } else if (result.probability > 0) {
                        ResultOverlay.show(
                            binding.overlayContainer,
                            result.className,
                            result.probability,
                            result.bbox
                        )
                    }
                }
                
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }.start()
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
        lastCapturedBitmap?.recycle()
        lastCapturedBitmap = null
        OnnxProcessor.closeSession()
    }
}
