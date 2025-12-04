package com.example.onnxsc

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher // ✅ Importación añadida
import com.example.onnxsc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var screenCaptureManager: ScreenCaptureManager

    // Corregida la referencia a registerForActivityResult
    private val captureLauncher: ActivityResultLauncher<String> = 
        registerForActivityResult(ScreenCaptureManager.ScreenCaptureContract()) { result ->
            // Lógica de manejo de resultados
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicialización del manager
        screenCaptureManager = ScreenCaptureManager(this, captureLauncher)
        
        binding.btnCapture.setOnClickListener {
            screenCaptureManager.startCapture()
        }
    }
}
