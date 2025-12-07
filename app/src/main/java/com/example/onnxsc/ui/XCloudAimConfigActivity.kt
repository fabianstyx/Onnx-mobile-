package com.example.onnxsc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.onnxsc.Logger
import com.example.onnxsc.R
import com.example.onnxsc.databinding.ActivityXcloudAimConfigBinding
import com.example.onnxsc.engine.ConfigEngine
import java.io.File

class XCloudAimConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXcloudAimConfigBinding
    
    private val aimPoints = listOf(
        "nose", "left_eye", "right_eye", "left_ear", "right_ear",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_hip", "right_hip",
        "left_knee", "right_knee", "left_ankle", "right_ankle"
    )
    
    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, XCloudAimConfigActivity::class.java)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXcloudAimConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Ensure ConfigEngine is initialized before using it
        ConfigEngine.init(this)
        
        setupToolbar()
        setupAimPointDropdown()
        loadCurrentConfig()
        setupButtons()
        checkModelStatus()
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
    
    private fun loadCurrentConfig() {
        binding.switchEnable.isChecked = ConfigEngine.getBool("xcloud_aim", "enable", true)
        binding.switchDetection.isChecked = ConfigEngine.getBool("xcloud_aim", "detection_enabled", true)
        binding.switchAlwaysOn.isChecked = ConfigEngine.getBool("xcloud_aim", "always_on_enabled", true)
        
        binding.switchSkeleton.isChecked = ConfigEngine.getBool("xcloud_aim", "skeleton_enabled", true)
        binding.switchFov.isChecked = ConfigEngine.getBool("xcloud_aim", "fov_circle_enabled", true)
        binding.switchTracers.isChecked = ConfigEngine.getBool("xcloud_aim", "tracers_enabled", true)
        binding.switchCrosshair.isChecked = ConfigEngine.getBool("xcloud_aim", "crosshair_enabled", true)
        
        // Round FOV to nearest valid step (stepSize=10, valueFrom=50)
        val fovRaw = ConfigEngine.getInt("xcloud_aim", "fov_radius", 140).toFloat()
        val fovRounded = (kotlin.math.round((fovRaw - 50f) / 10f) * 10f + 50f).coerceIn(50f, 400f)
        binding.sliderFovRadius.value = fovRounded
        
        // Round aim speed to nearest valid step (stepSize=5, valueFrom=10)
        val speedRaw = ConfigEngine.getFloat("xcloud_aim", "aim_speed_percent", 45f)
        val speedRounded = (kotlin.math.round((speedRaw - 10f) / 5f) * 5f + 10f).coerceIn(10f, 100f)
        binding.sliderAimSpeed.value = speedRounded
        
        // Round smoothing to nearest valid step (stepSize=5, valueFrom=0)
        val smoothRaw = ConfigEngine.getFloat("xcloud_aim", "smoothing_factor", 0.20f) * 100
        val smoothRounded = (kotlin.math.round(smoothRaw / 5f) * 5f).coerceIn(0f, 100f)
        binding.sliderSmoothing.value = smoothRounded
        
        val currentAimPoint = ConfigEngine.getString("xcloud_aim", "aim_point", "nose")
        binding.dropdownAimPoint.setText(currentAimPoint, false)
        
        binding.switchPrediction.isChecked = ConfigEngine.getBool("xcloud_aim", "prediction_enabled", true)
        
        // Round latency to nearest valid step (stepSize=5, valueFrom=0)
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
        
        Toast.makeText(this, "Configuracion aplicada", Toast.LENGTH_SHORT).show()
        Logger.success("XCloud Aim: Configuracion actualizada")
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
        
        binding.sliderFovRadius.value = 140f  // Must be valueFrom(50) + multiple of stepSize(10)
        binding.sliderAimSpeed.value = 45f    // Must be valueFrom(10) + multiple of stepSize(5)
        binding.sliderSmoothing.value = 20f   // Must be valueFrom(0) + multiple of stepSize(5)
        
        binding.dropdownAimPoint.setText("nose", false)
        
        binding.switchPrediction.isChecked = true
        binding.sliderLatency.value = 75f     // Must be valueFrom(0) + multiple of stepSize(5)
        
        binding.switchAutoShoot.isChecked = false
        binding.switchBurstMode.isChecked = false
        
        Toast.makeText(this, "Valores reseteados", Toast.LENGTH_SHORT).show()
    }
    
    private fun checkModelStatus() {
        val modelName = "movenet_singlepose_lightning.onnx"
        val possiblePaths = listOf(
            "/sdcard/ONNX/$modelName",
            "/sdcard/Download/$modelName",
            "/storage/emulated/0/ONNX/$modelName",
            "/storage/emulated/0/Download/$modelName",
            "/sdcard/Documents/$modelName",
            "/storage/emulated/0/Documents/$modelName"
        )
        
        var foundPath: String? = null
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                foundPath = path
                break
            }
        }
        
        if (foundPath != null) {
            binding.txtModelStatus.text = "Modelo encontrado:\n$foundPath"
            binding.txtModelStatus.setBackgroundResource(R.drawable.bg_model_status_ok)
        } else {
            binding.txtModelStatus.text = "Modelo NO encontrado"
            binding.txtModelStatus.setBackgroundResource(R.drawable.bg_model_status_error)
        }
    }
}
