package com.example.onnxsc.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.example.onnxsc.R
import com.example.onnxsc.config.ModelConfig
import com.example.onnxsc.config.ModelConfigManager
import com.example.onnxsc.databinding.FragmentConfigGeneralBinding

class ConfigGeneralFragment : Fragment() {

    private var _binding: FragmentConfigGeneralBinding? = null
    private val binding get() = _binding!!
    
    private val modelTypes = listOf("onnx", "tflite", "custom")
    private val outputFormats = listOf("auto", "yolov5", "yolov8", "rtdetr", "ssd", "classification")
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigGeneralBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDropdowns()
        setupSliders()
        loadConfig()
    }
    
    private fun setupDropdowns() {
        val modelTypeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            modelTypes
        )
        binding.spinnerModelType.setAdapter(modelTypeAdapter)
        
        val outputFormatAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            outputFormats
        )
        binding.spinnerOutputFormat.setAdapter(outputFormatAdapter)
    }
    
    private fun setupSliders() {
        binding.sliderConfidence.addOnChangeListener { _, value, _ ->
            binding.txtConfidenceValue.text = "${value.toInt()}%"
        }
        
        binding.sliderNms.addOnChangeListener { _, value, _ ->
            binding.txtNmsValue.text = "${value.toInt()}%"
        }
        
        binding.sliderThreads.addOnChangeListener { _, value, _ ->
            binding.txtThreadsValue.text = "${value.toInt()}"
        }
    }
    
    fun loadConfig() {
        val config = ModelConfigManager.getCurrentConfig()
        
        binding.spinnerModelType.setText(config.modelType, false)
        binding.spinnerOutputFormat.setText(config.outputFormat, false)
        
        binding.editInputWidth.setText(config.inputWidth.toString())
        binding.editInputHeight.setText(config.inputHeight.toString())
        binding.editInputChannels.setText(config.inputChannels.toString())
        
        binding.sliderConfidence.value = (config.confidenceThreshold * 100).coerceIn(1f, 99f)
        binding.txtConfidenceValue.text = "${(config.confidenceThreshold * 100).toInt()}%"
        
        binding.sliderNms.value = (config.nmsThreshold * 100).coerceIn(1f, 99f)
        binding.txtNmsValue.text = "${(config.nmsThreshold * 100).toInt()}%"
        
        binding.editMaxDetections.setText(config.maxDetections.toString())
        
        binding.switchGpu.isChecked = config.useGpu
        binding.switchNnapi.isChecked = config.useNnapi
        binding.sliderThreads.value = config.numThreads.toFloat().coerceIn(1f, 8f)
        binding.txtThreadsValue.text = "${config.numThreads}"
        
        binding.switchNormalize.isChecked = config.normalizeInput
        binding.switchSwapRB.isChecked = config.preprocessing.swapRB
        binding.switchLetterbox.isChecked = config.preprocessing.letterbox
        
        binding.editDescription.setText(config.description)
        binding.editVersion.setText(config.version)
        binding.editAuthor.setText(config.author)
    }
    
    fun collectConfig() {
        val config = ModelConfigManager.getCurrentConfig()
        
        config.modelType = binding.spinnerModelType.text.toString().ifEmpty { "onnx" }
        config.outputFormat = binding.spinnerOutputFormat.text.toString().ifEmpty { "auto" }
        
        config.inputWidth = binding.editInputWidth.text.toString().toIntOrNull() ?: 640
        config.inputHeight = binding.editInputHeight.text.toString().toIntOrNull() ?: 640
        config.inputChannels = binding.editInputChannels.text.toString().toIntOrNull() ?: 3
        
        config.confidenceThreshold = binding.sliderConfidence.value / 100f
        config.nmsThreshold = binding.sliderNms.value / 100f
        config.maxDetections = binding.editMaxDetections.text.toString().toIntOrNull() ?: 100
        
        config.useGpu = binding.switchGpu.isChecked
        config.useNnapi = binding.switchNnapi.isChecked
        config.numThreads = binding.sliderThreads.value.toInt()
        
        config.normalizeInput = binding.switchNormalize.isChecked
        config.preprocessing.swapRB = binding.switchSwapRB.isChecked
        config.preprocessing.letterbox = binding.switchLetterbox.isChecked
        
        config.description = binding.editDescription.text.toString()
        config.version = binding.editVersion.text.toString()
        config.author = binding.editAuthor.text.toString()
        
        ModelConfigManager.updateConfig(config)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
