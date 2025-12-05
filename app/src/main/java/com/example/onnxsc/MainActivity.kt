package com.example.onnxsc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.onnxsc.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null
    private var lastInspection: ModelInspector.Inspection? = null
    private lateinit var modelSwitcher: ModelSwitcher
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

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
            if (result.resultCode == RESULT_OK && result.data != null) {
                Logger.info("Permiso de captura concedido")
                startScreenCapture(result.data!!)
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

        checkPermissions()
        setupButtons()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
            stopCapture()
            ResultOverlay.clear(binding.overlayContainer)
            OnnxProcessor.closeSession()
            FpsMeter.reset()
            modelSwitcher.pick { newUri: Uri ->
                loadNewModel(newUri)
            }
        }

        binding.btnCapture.setOnClickListener { 
            if (isCapturing) {
                stopCapture()
            } else {
                requestScreenCapture()
            }
        }
        
        binding.btnClearConsole.setOnClickListener { Logger.clear() }
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
                    NodeJsManager.ensureNodeJsInstalled(this) { log -> Logger.info(log) }
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

    private fun startScreenCapture(data: Intent) {
        try {
            Logger.info("Iniciando captura de pantalla...")
            
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(RESULT_OK, data)
            
            if (mediaProjection == null) {
                Logger.error("No se pudo obtener MediaProjection")
                return
            }

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            Logger.info("Resolución: ${width}x${height} @ ${density}dpi")

            captureThread = HandlerThread("ScreenCapture").apply { start() }
            captureHandler = Handler(captureThread!!.looper)

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "screenCap",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, null
            )

            if (virtualDisplay == null) {
                Logger.error("No se pudo crear VirtualDisplay")
                stopCapture()
                return
            }

            isCapturing = true
            binding.btnCapture.text = "Detener captura"
            Logger.success("Captura iniciada - Esperando frames...")

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturing) return@setOnImageAvailableListener
                
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                
                if (image == null) return@setOnImageAvailableListener

                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val croppedBitmap = if (bitmap.width > width) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                            bitmap.recycle()
                        }
                    } else {
                        bitmap
                    }

                    image.close()

                    processFrame(croppedBitmap)
                    
                } catch (e: Exception) {
                    mainHandler.post { Logger.error("Error procesando frame: ${e.message}") }
                    try { image.close() } catch (_: Exception) {}
                }
            }, captureHandler)

        } catch (e: Exception) {
            Logger.error("Error al iniciar captura: ${e.message}")
            stopCapture()
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        FpsMeter.tick { fps ->
            mainHandler.post { Logger.info(fps) }
        }

        val uri = modelUri ?: return
        
        val result = OnnxProcessor.processImage(this, uri, bitmap) { log ->
            mainHandler.post { Logger.info(log) }
        }

        mainHandler.post {
            if (result != null) {
                Logger.success("Inferencia: ${result.className} (${(result.probability * 100).toInt()}%) - ${result.latencyMs}ms")

                ResultOverlay.clear(binding.overlayContainer)
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

    private fun stopCapture() {
        isCapturing = false
        
        mainHandler.post {
            binding.btnCapture.text = getString(R.string.capture)
        }

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Logger.warn("Error al cerrar ImageReader: ${e.message}")
        }

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Logger.warn("Error al liberar VirtualDisplay: ${e.message}")
        }

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Logger.warn("Error al detener MediaProjection: ${e.message}")
        }

        try {
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
        } catch (e: Exception) {
            Logger.warn("Error al detener thread: ${e.message}")
        }

        Logger.info("Captura detenida")
    }

    fun saveCurrentFrame(bitmap: Bitmap) {
        val filename = "capture_${System.currentTimeMillis()}.png"
        GallerySaver.save(this, bitmap, filename) { log ->
            mainHandler.post { Logger.info(log) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        OnnxProcessor.closeSession()
    }
}
