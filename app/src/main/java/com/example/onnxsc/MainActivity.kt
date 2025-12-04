package com.example.onnxsc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.example.onnxsc.databinding.ActivityMainBinding

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private var modelUri: Uri? = null

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

    private fun pickModel() {
        Logger.info("Seleccionando modelo...")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, 123)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 123 && resultCode == RESULT_OK) {
            modelUri = data?.data
            val name = modelUri?.lastPathSegment ?: "desconocido"
            Logger.success("Modelo cargado: $name")
        } else {
            Logger.error("No se eligió ningún archivo")
        }
    }

    private fun startCapture() {
        if (modelUri == null) {
            Logger.error("No hay modelo cargado")
            return
        }
        Logger.info("Iniciando captura de pantalla...")
        // Aquí irá MediaProjection + ONNX
        Logger.success("Captura finalizada (mock)")
    }
}
