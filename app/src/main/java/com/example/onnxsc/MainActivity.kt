package com.example.onnxsc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.onnxsc.databinding.ActivityMainBinding
import ai.onnxruntime.OnnxTensor

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null
    private var lastInspection: ModelInspector.Inspection? = null
    private val modelSwitcher = ModelSwitcher(this)

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                modelUri = it
                val name = it.lastPathSegment ?: "desconocido"
                Logger.success("Modelo cargado: $name")
                inspectModel(it)
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
        Logger.info("App iniciada")

        binding.btnPickModel.setOnClickListener { pickModelLauncher.launch(arrayOf("*/*")) }
        binding.btnChangeModel.setOnClickListener {
            modelSwitcher.pick { newUri ->
                modelUri = newUri
                val name = newUri.lastPathSegment ?: "desconocido"
                Logger.success("Modelo cambiado: $name")
                inspectModel(newUri)
            }
        }
        binding.btnCapture.setOnClickListener { requestScreenCapture() }
        binding.btnClearConsole.setOnClickListener { Logger.clear() }
    }

    private fun inspectModel(uri: Uri) {
        lastInspection = contentResolver?.let { ModelInspector.inspect(it, uri) }
        lastInspection?.let { ins ->
            Logger.info("Tamaño aprox: ${ins.sizeKb} KB")
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
            Logger.error("No hay modelo cargado")
            return
        }
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpManager.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    private fun processScreenCapture(data: Intent) {
        Logger.info("Iniciando captura real...")
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(RESULT_OK, data) ?: run {
            Logger.error("No se pudo obtener MediaProjection")
            return
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        Logger.info("Creando ImageReader $width x $height")
        val imageReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 1)

        val virtualDisplay = projection.createVirtualDisplay(
            "screenCap", width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        Logger.info("Esperando frame...")
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            Logger.info("Frame capturado")
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
            image.close()
            virtualDisplay.release()
            projection.stop()

            Logger.info("Bitmap listo: ${bitmap.width} x ${bitmap.height}")
            GallerySaver.save(bitmap, "capture_${System.currentTimeMillis()}.png") { log -> Logger.info(log) }

            FpsMeter.tick { fps -> Logger.info(fps) }

            modelUri?.let { uri ->
                Logger.info("Ejecutando modelo ONNX...")
                val result = OnnxProcessor.processImage(this, uri, bitmap) { log -> Logger.info(log) }
                if (result != null) {
                    val clazz = "ClaseEjemplo" // <- aquí parsea tu tensor real
                    val prob = 0.95f // <- aquí obtén la probabilidad real
                    val bbox = RectF(50f, 50f, 200f, 200f) // <- aquí obtén la bbox real
                    ResultOverlay.show(binding.root, clazz, prob, bbox)
                    Logger.success("Inferencia terminada")
                    result.close()
                } else {
                    Logger.error("Falló la inferencia")
                }
            }
            imageReader.setOnImageAvailableListener(null, null)
        }, null)
    }
}
