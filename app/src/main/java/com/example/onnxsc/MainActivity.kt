package com.example.onnxsc

import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.onnxsc.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null
    private var lastInspection: ModelInspector.Inspection? = null
    private val modelSwitcher: ModelSwitcher = ModelSwitcher(this)
    private val handler = Handler(Looper.getMainLooper())

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                loadNewModel(it)
            } ?: Logger.error("No se eligió archivo")
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Logger.info("Permiso de captura concedido")
                processScreenCapture(result.data!!)
            } else {
                Logger.error("Permiso de captura DENEGADO")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ONNXSC)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Logger.init(binding.txtConsole)
        Logger.info("App iniciada - ONNX Screen v1.0")

        binding.btnPickModel.setOnClickListener { 
            pickModelLauncher.launch(arrayOf("*/*")) 
        }

        binding.btnChangeModel.setOnClickListener {
            ResultOverlay.clear(binding.overlayContainer)
            OnnxProcessor.closeSession()
            modelSwitcher.pick { newUri: Uri ->
                loadNewModel(newUri)
            }
        }

        binding.btnCapture.setOnClickListener { requestScreenCapture() }
        binding.btnClearConsole.setOnClickListener { Logger.clear() }
    }

    private fun loadNewModel(uri: Uri) {
        modelUri = uri
        val modelName: String = uri.lastPathSegment ?: "desconocido"
        Logger.success("Modelo seleccionado: $modelName")
        
        binding.txtModel.text = modelName
        
        ResultOverlay.clear(binding.overlayContainer)
        
        inspectModel(uri)
        
        OnnxProcessor.loadModel(this, uri) { log -> 
            handler.post { Logger.info(log) }
        }
    }

    private fun inspectModel(uri: Uri) {
        lastInspection = contentResolver?.let { ModelInspector.inspect(it, uri) }
        lastInspection?.let { ins ->
            Logger.info("Tamaño: ${ins.sizeKb} KB")
            DependencyInstaller.checkAndInstall(this, ins) { log -> Logger.info(log) }
            if (ins.hasExternalWeights) {
                ExternalWeightsManager.copyExternalWeights(this, contentResolver!!, uri) { log -> Logger.info(log) }
            }
            if (ins.hasJsOperators || ins.hasNodeOps) {
                NodeJsManager.ensureNodeJsInstalled(this) { log -> Logger.info(log) }
            }
        }
    }

    private fun requestScreenCapture() {
        if (modelUri == null) {
            Logger.error("Primero selecciona un modelo ONNX")
            return
        }
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpManager.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    private fun processScreenCapture(data: Intent) {
        Logger.info("Iniciando captura de pantalla...")
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(RESULT_OK, data) ?: run {
            Logger.error("No se pudo obtener MediaProjection")
            return
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        Logger.info("Resolución: ${width}x${height}")
        val imageReader = android.media.ImageReader.newInstance(
            width, height, 
            android.graphics.PixelFormat.RGBA_8888, 
            2
        )

        val virtualDisplay = projection.createVirtualDisplay(
            "screenCap", width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        Logger.info("Esperando frame...")
        
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
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
                
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                
                image.close()
                virtualDisplay.release()
                projection.stop()
                imageReader.setOnImageAvailableListener(null, null)

                Logger.info("Frame capturado: ${croppedBitmap.width}x${croppedBitmap.height}")
                
                GallerySaver.save(croppedBitmap, "capture_${System.currentTimeMillis()}.png") { log -> 
                    handler.post { Logger.info(log) }
                }

                FpsMeter.tick { fps -> 
                    handler.post { Logger.info(fps) }
                }

                modelUri?.let { uri ->
                    Thread {
                        val result = OnnxProcessor.processImage(this, uri, croppedBitmap) { log -> 
                            handler.post { Logger.info(log) }
                        }
                        
                        handler.post {
                            if (result != null) {
                                Logger.success("Inferencia: ${result.className} (${(result.probability * 100).toInt()}%) - ${result.latencyMs}ms")
                                
                                ResultOverlay.clear(binding.overlayContainer)
                                ResultOverlay.show(
                                    binding.overlayContainer,
                                    result.className,
                                    result.probability,
                                    result.bbox
                                )
                            } else {
                                Logger.error("Falló la inferencia")
                            }
                        }
                    }.start()
                }
                
            } catch (e: Exception) {
                Logger.error("Error procesando frame: ${e.message}")
                image.close()
                virtualDisplay.release()
                projection.stop()
            }
        }, handler)
    }

    override fun onDestroy() {
        super.onDestroy()
        OnnxProcessor.closeSession()
    }
}
