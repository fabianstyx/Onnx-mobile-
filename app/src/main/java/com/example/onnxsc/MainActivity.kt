package com.example.onnxsc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.example.onnxsc.databinding.ActivityMainBinding

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null
    private var lastInspection: Inspection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ONNXSC)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Logger.init(binding.txtConsole)
        Logger.info("App iniciada")

        binding.btnPickModel.setOnClickListener { pickModel() }
        binding.btnCapture.setOnClickListener { startCapture() }
        binding.btnClearConsole.setOnClickListener { Logger.clear() }
    }

    private val pickModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            modelUri = it
            val name = modelUri?.lastPathSegment ?: "desconocido"
            Logger.success("Modelo cargado: $name")

            // Inspeccionar dependencias
            lastInspection = contentResolver?.let {
                ModelInspector.inspect(it, modelUri!!)
            }
            lastInspection?.let { inspect ->
                Logger.info("Tamaño aprox: ${inspect.sizeKb} KB")
                DependencyInstaller.checkAndInstall(this, inspect) { log ->
                    Logger.info(log)
                }

                // Si hay pesos externos, copiarlos
                if (inspect.hasExternalWeights) {
                    ExternalWeightsManager.copyExternalWeights(
                        this,
                        contentResolver!!,
                        modelUri!!,
                        { log -> Logger.info(log) }
                    )
                }

                // Si requiere Node.js, instalarlo
                if (inspect.hasJsOperators || inspect.hasNodeOps) {
                    NodeJsManager.ensureNodeJsInstalled(this) { log ->
                        Logger.info(log)
                    }
                }
            }
        } ?: Logger.error("No se eligió ningún archivo")
    }

    private fun pickModel() {
        Logger.info("Seleccionando modelo...")
        pickModelLauncher.launch(arrayOf("*/*"))
    }

    private fun startCapture() {
        if (modelUri == null) {
            Logger.error("No hay modelo cargado")
            return
        }
        Logger.info("Iniciando captura de pantalla...")
        ScreenCaptureManager.startCapture(this) {
            // Aquí irá el procesamiento con ONNX
            Logger.success("Captura finalizada")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ScreenCaptureManager.handleActivityResult(requestCode, resultCode, data)
    }
}
