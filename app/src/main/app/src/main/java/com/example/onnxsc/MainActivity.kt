package com.example.onnxsc
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.onnxruntime.*
class MainActivity : AppCompatActivity() {
    private lateinit var ortEnv: OrtEnvironment
    private var session: OrtSession? = null
    private val pickOnnx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadModel(it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ortEnv = OrtEnvironment.getEnvironment()
        val btnPick = Button(this).apply { text = "Seleccionar .onnx" }
        val btnCap  = Button(this).apply { text = "Capturar pantalla" }
        val tv      = TextView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btnPick)
            addView(btnCap)
            addView(tv)
        }
        setContentView(root)
        btnPick.setOnClickListener {
            pickOnnx.launch(arrayOf("*/*"))
        }
        btnCap.setOnClickListener {
            startActivityForResult(
                (getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager)
                    .createScreenCaptureIntent(), 22)
        }
    }
    private fun loadModel(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { inp ->
            val modelBytes = inp.readBytes()
            session = ortEnv.createSession(modelBytes)
            Toast.makeText(this, "Modelo cargado: ${session?.inputNames}", Toast.LENGTH_LONG).show()
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 22 && resultCode == Activity.RESULT_OK) {
            // Aquí obtendrías la Surface/SurfaceTexture para captura
            // Por brevedad mostramos un toast
            Toast.makeText(this, "Captura consentida – implementar ImageReader", Toast.LENGTH_LONG).show()
        }
    }
}
