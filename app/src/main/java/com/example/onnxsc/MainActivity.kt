package com.example.onnxsc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.example.onnxsc.databinding.ActivityMainBinding

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null
    private var lastInspection: Inspection? = null

    /* -------- SELECTOR DE MODELO -------- */
    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                modelUri = it
                val name = it.lastPathSegment ?: "desconocido"
                Logger.success("Modelo cargado: $name")
                inspectModel(it)
            } ?: Logger.error("No se eligió archivo")
        }

    /* -------- CAPTURA DE PANTALLA -------- */
    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Logger.info("Permiso de captura concedido")
                // Aquí iría el MediaProjection real
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
        binding.btnCapture.setOnClickListener { requestScreenCapture() }
        binding.btnClearConsole.setOnClickListener { Logger.clear() }
    }

    /* -------- LÓGICA DE MODELO -------- */
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

    /* -------- SOLICITAR CAPTURA -------- */
    private fun requestScreenCapture() {
        if (modelUri == null) {
            Logger.error("No hay modelo cargado")
            return
        }
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mpManager.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    /* -------- PROCESAR CAPTURA -------- */
    private fun processScreenCapture(data: Intent) {
        Logger.info("Iniciando captura real...")
        // Aquí obtendrías el MediaProjection y capturarías frames
        // Por ahora simulamos:
        Logger.success("Captura iniciada (mock)")
    }
}
