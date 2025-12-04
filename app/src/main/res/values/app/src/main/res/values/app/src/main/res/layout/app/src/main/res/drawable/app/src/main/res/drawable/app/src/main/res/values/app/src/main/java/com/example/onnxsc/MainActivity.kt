package com.example.onnxsc

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import ai.onnxruntime.*
import com.example.onnxsc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var ortSession: OrtSession? = null

    private val pickOnnx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadModel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // inflar layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // preferencias para tema
        prefs = getSharedPreferences("config", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark", false)
        setThemeMode(isDark)
        binding.themeSwitch.isChecked = isDark

        // listeners
        binding.themeSwitch.setOnCheckedChangeListener { _, checked ->
            setThemeMode(checked)
            prefs.edit().putBoolean("dark", checked).apply()
        }

        binding.btnLoad.setOnClickListener { pickOnnx.launch(arrayOf("*/*")) }
        binding.btnCapture.setOnClickListener { startScreenCapture() }
    }

    private fun setThemeMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun loadModel(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { inp ->
            val bytes = inp.readBytes()
            ortSession = OrtEnvironment.getEnvironment().createSession(bytes)
            binding.resultTxt.text = "Modelo cargado: ${ortSession?.inputNames}"
        }
    }

    private fun startScreenCapture() {
        // TODO: ImageReader + VirtualDisplay
        binding.resultTxt.text = "Captura solicitada – implementar frames"
    }
}
