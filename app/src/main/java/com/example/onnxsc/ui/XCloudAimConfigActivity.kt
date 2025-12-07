package com.example.onnxsc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.onnxsc.Logger
import com.example.onnxsc.R
import com.example.onnxsc.databinding.ActivityXcloudAimConfigBinding
import com.example.onnxsc.engine.ConfigEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class MoveNetModel(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val inputSize: Int,
    val estimatedFps: String,
    val description: String
)

class XCloudAimConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXcloudAimConfigBinding
    
    private val aimPoints = listOf(
        "nose", "left_eye", "right_eye", "left_ear", "right_ear",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_hip", "right_hip",
        "left_knee", "right_knee", "left_ankle", "right_ankle"
    )
    
    private val availableModels = listOf(
        MoveNetModel(
            displayName = "Lightning (Rapido)",
            fileName = "movenet_singlepose_lightning.onnx",
            downloadUrl = "https://huggingface.co/Xenova/movenet-singlepose-lightning/resolve/main/onnx/model.onnx",
            inputSize = 192,
            estimatedFps = "25-35 FPS",
            description = "Modelo rapido, ideal para tiempo real"
        ),
        MoveNetModel(
            displayName = "Thunder (Preciso)",
            fileName = "movenet_singlepose_thunder.onnx",
            downloadUrl = "https://huggingface.co/Xenova/movenet-singlepose-thunder/resolve/main/onnx/model.onnx",
            inputSize = 256,
            estimatedFps = "10-20 FPS",
            description = "Modelo preciso, mejor deteccion"
        )
    )
    
    private var selectedModelIndex = 0
    private var isDownloading = false
    
    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, XCloudAimConfigActivity::class.java)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXcloudAimConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ConfigEngine.init(this)
        
        setupToolbar()
        setupAimPointDropdown()
        setupModelDropdown()
        loadCurrentConfig()
        setupButtons()
        updateModelStatus()
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }
    
    private fun setupAimPointDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, aimPoints)
        binding.dropdownAimPoint.setAdapter(adapter)
    }
    
    private fun setupModelDropdown() {
        val modelNames = availableModels.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modelNames)
        binding.dropdownModel.setAdapter(adapter)
        
        val savedModel = ConfigEngine.getString("xcloud_aim", "model_type", "lightning")
        selectedModelIndex = if (savedModel == "thunder") 1 else 0
        binding.dropdownModel.setText(availableModels[selectedModelIndex].displayName, false)
        
        binding.dropdownModel.setOnItemClickListener { _, _, position, _ ->
            selectedModelIndex = position
            updateModelInfo()
            updateModelStatus()
        }
        
        updateModelInfo()
    }
    
    private fun updateModelInfo() {
        val model = availableModels[selectedModelIndex]
        binding.txtModelInfo.text = buildString {
            append("Input: ${model.inputSize}x${model.inputSize}\n")
            append("FPS estimado: ${model.estimatedFps}\n")
            append(model.description)
        }
    }
    
    private fun getModelDirectory(): File {
        val onnxDir = File(Environment.getExternalStorageDirectory(), "ONNX")
        if (!onnxDir.exists()) {
            onnxDir.mkdirs()
        }
        return onnxDir
    }
    
    private fun getModelFile(model: MoveNetModel): File {
        return File(getModelDirectory(), model.fileName)
    }
    
    private fun findModelPath(model: MoveNetModel): String? {
        val possiblePaths = listOf(
            File(getModelDirectory(), model.fileName),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), model.fileName),
            File("/sdcard/ONNX/${model.fileName}"),
            File("/sdcard/Download/${model.fileName}"),
            File("/storage/emulated/0/ONNX/${model.fileName}"),
            File("/storage/emulated/0/Download/${model.fileName}")
        )
        
        for (file in possiblePaths) {
            if (file.exists() && file.canRead() && file.length() > 1000) {
                return file.absolutePath
            }
        }
        return null
    }
    
    private fun updateModelStatus() {
        val model = availableModels[selectedModelIndex]
        val foundPath = findModelPath(model)
        
        if (foundPath != null) {
            val file = File(foundPath)
            val sizeMb = file.length() / (1024.0 * 1024.0)
            binding.txtModelStatus.text = "Modelo listo (%.1f MB)\n$foundPath".format(sizeMb)
            binding.txtModelStatus.setBackgroundResource(R.drawable.bg_model_status_ok)
            binding.btnDownloadModel.text = "Modelo descargado"
            binding.btnDownloadModel.isEnabled = false
        } else {
            binding.txtModelStatus.text = "Modelo no encontrado\nPresiona descargar para obtenerlo"
            binding.txtModelStatus.setBackgroundResource(R.drawable.bg_model_status_error)
            binding.btnDownloadModel.text = "Descargar ${model.displayName}"
            binding.btnDownloadModel.isEnabled = true
        }
    }
    
    private fun downloadModel() {
        if (isDownloading) return
        
        val model = availableModels[selectedModelIndex]
        isDownloading = true
        
        binding.btnDownloadModel.isEnabled = false
        binding.btnDownloadModel.text = "Descargando..."
        binding.progressDownload.visibility = View.VISIBLE
        binding.progressDownload.isIndeterminate = false
        binding.progressDownload.progress = 0
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val targetFile = getModelFile(model)
                val url = URL(model.downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.connect()
                
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${connection.responseCode}")
                }
                
                val fileLength = connection.contentLength
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(targetFile)
                
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    
                    if (fileLength > 0) {
                        val progress = (totalRead * 100 / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            binding.progressDownload.progress = progress
                        }
                    }
                }
                
                outputStream.close()
                inputStream.close()
                connection.disconnect()
                
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    binding.progressDownload.visibility = View.GONE
                    Toast.makeText(this@XCloudAimConfigActivity, "Modelo descargado correctamente", Toast.LENGTH_SHORT).show()
                    Logger.success("XCloud Aim: Modelo ${model.displayName} descargado")
                    updateModelStatus()
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    binding.progressDownload.visibility = View.GONE
                    binding.btnDownloadModel.isEnabled = true
                    binding.btnDownloadModel.text = "Reintentar descarga"
                    Toast.makeText(this@XCloudAimConfigActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    Logger.error("XCloud Aim: Error descargando modelo - ${e.message}")
                }
            }
        }
    }
    
    private fun loadCurrentConfig() {
        binding.switchEnable.isChecked = ConfigEngine.getBool("xcloud_aim", "enable", true)
        binding.switchDetection.isChecked = ConfigEngine.getBool("xcloud_aim", "detection_enabled", true)
        binding.switchAlwaysOn.isChecked = ConfigEngine.getBool("xcloud_aim", "always_on_enabled", true)
        
        binding.switchSkeleton.isChecked = ConfigEngine.getBool("xcloud_aim", "skeleton_enabled", true)
        binding.switchFov.isChecked = ConfigEngine.getBool("xcloud_aim", "fov_circle_enabled", true)
        binding.switchTracers.isChecked = ConfigEngine.getBool("xcloud_aim", "tracers_enabled", true)
        binding.switchCrosshair.isChecked = ConfigEngine.getBool("xcloud_aim", "crosshair_enabled", true)
        
        val fovRaw = ConfigEngine.getInt("xcloud_aim", "fov_radius", 140).toFloat()
        val fovRounded = (kotlin.math.round((fovRaw - 50f) / 10f) * 10f + 50f).coerceIn(50f, 400f)
        binding.sliderFovRadius.value = fovRounded
        
        val speedRaw = ConfigEngine.getFloat("xcloud_aim", "aim_speed_percent", 45f)
        val speedRounded = (kotlin.math.round((speedRaw - 10f) / 5f) * 5f + 10f).coerceIn(10f, 100f)
        binding.sliderAimSpeed.value = speedRounded
        
        val smoothRaw = ConfigEngine.getFloat("xcloud_aim", "smoothing_factor", 0.20f) * 100
        val smoothRounded = (kotlin.math.round(smoothRaw / 5f) * 5f).coerceIn(0f, 100f)
        binding.sliderSmoothing.value = smoothRounded
        
        val currentAimPoint = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
        binding.dropdownAimPoint.setText(currentAimPoint, false)
        
        binding.switchPrediction.isChecked = ConfigEngine.getBool("xcloud_aim", "prediction_enabled", true)
        
        val latencyRaw = ConfigEngine.getInt("xcloud_aim", "latency_compensation", 75).toFloat()
        val latencyRounded = (kotlin.math.round(latencyRaw / 5f) * 5f).coerceIn(0f, 200f)
        binding.sliderLatency.value = latencyRounded
        
        binding.switchAutoShoot.isChecked = ConfigEngine.getBool("xcloud_aim", "auto_shoot", false)
        binding.switchBurstMode.isChecked = ConfigEngine.getBool("xcloud_aim", "burst_mode", false)
    }
    
    private fun setupButtons() {
        binding.btnApply.setOnClickListener {
            applyConfig()
        }
        
        binding.btnReset.setOnClickListener {
            confirmReset()
        }
        
        binding.btnDownloadModel.setOnClickListener {
            downloadModel()
        }
    }
    
    private fun applyConfig() {
        ConfigEngine.setBool("xcloud_aim", "enable", binding.switchEnable.isChecked)
        ConfigEngine.setBool("xcloud_aim", "detection_enabled", binding.switchDetection.isChecked)
        ConfigEngine.setBool("xcloud_aim", "always_on_enabled", binding.switchAlwaysOn.isChecked)
        
        ConfigEngine.setBool("xcloud_aim", "skeleton_enabled", binding.switchSkeleton.isChecked)
        ConfigEngine.setBool("xcloud_aim", "fov_circle_enabled", binding.switchFov.isChecked)
        ConfigEngine.setBool("xcloud_aim", "tracers_enabled", binding.switchTracers.isChecked)
        ConfigEngine.setBool("xcloud_aim", "crosshair_enabled", binding.switchCrosshair.isChecked)
        
        ConfigEngine.setInt("xcloud_aim", "fov_radius", binding.sliderFovRadius.value.toInt())
        ConfigEngine.setFloat("xcloud_aim", "aim_speed_percent", binding.sliderAimSpeed.value)
        ConfigEngine.setFloat("xcloud_aim", "smoothing_factor", binding.sliderSmoothing.value / 100f)
        
        val selectedAimPoint = binding.dropdownAimPoint.text.toString()
        if (selectedAimPoint.isNotEmpty()) {
            ConfigEngine.setString("xcloud_aim", "aim_point", selectedAimPoint)
        }
        
        ConfigEngine.setBool("xcloud_aim", "prediction_enabled", binding.switchPrediction.isChecked)
        ConfigEngine.setInt("xcloud_aim", "latency_compensation", binding.sliderLatency.value.toInt())
        
        ConfigEngine.setBool("xcloud_aim", "auto_shoot", binding.switchAutoShoot.isChecked)
        ConfigEngine.setBool("xcloud_aim", "burst_mode", binding.switchBurstMode.isChecked)
        
        val modelType = if (selectedModelIndex == 1) "thunder" else "lightning"
        ConfigEngine.setString("xcloud_aim", "model_type", modelType)
        ConfigEngine.setString("xcloud_aim", "model_file", availableModels[selectedModelIndex].fileName)
        
        Toast.makeText(this, "Configuracion aplicada", Toast.LENGTH_SHORT).show()
        Logger.success("XCloud Aim: Configuracion actualizada (modelo: $modelType)")
    }
    
    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Resetear Configuracion")
            .setMessage("Se restauraran los valores por defecto. Continuar?")
            .setPositiveButton("Resetear") { _, _ ->
                resetToDefaults()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun resetToDefaults() {
        binding.switchEnable.isChecked = true
        binding.switchDetection.isChecked = true
        binding.switchAlwaysOn.isChecked = true
        
        binding.switchSkeleton.isChecked = true
        binding.switchFov.isChecked = true
        binding.switchTracers.isChecked = true
        binding.switchCrosshair.isChecked = true
        
        binding.sliderFovRadius.value = 140f
        binding.sliderAimSpeed.value = 45f
        binding.sliderSmoothing.value = 20f
        
        binding.dropdownAimPoint.setText("nose", false)
        
        binding.switchPrediction.isChecked = true
        binding.sliderLatency.value = 75f
        
        binding.switchAutoShoot.isChecked = false
        binding.switchBurstMode.isChecked = false
        
        selectedModelIndex = 0
        binding.dropdownModel.setText(availableModels[0].displayName, false)
        updateModelInfo()
        updateModelStatus()
        
        Toast.makeText(this, "Valores reseteados", Toast.LENGTH_SHORT).show()
    }
}
