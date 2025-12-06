package com.example.onnxsc.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.onnxsc.config.ModelConfigManager
import com.example.onnxsc.databinding.FragmentConfigLabelsBinding

class ConfigLabelsFragment : Fragment() {

    private var _binding: FragmentConfigLabelsBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigLabelsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        setupTextWatcher()
        loadConfig()
    }
    
    private fun setupButtons() {
        binding.btnLoadCoco.setOnClickListener {
            loadCocoLabels()
        }
        
        binding.btnLoadImagenet.setOnClickListener {
            loadImageNetLabels()
        }
        
        binding.btnClearLabels.setOnClickListener {
            binding.editLabels.setText("")
            updateLabelCount()
        }
    }
    
    private fun setupTextWatcher() {
        binding.editLabels.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateLabelCount()
            }
        })
    }
    
    private fun updateLabelCount() {
        val text = binding.editLabels.text.toString()
        val count = text.lines().filter { it.isNotBlank() }.size
        binding.txtLabelCount.text = "$count clases definidas"
    }
    
    fun loadConfig() {
        val config = ModelConfigManager.getCurrentConfig()
        
        val labelsText = config.labels.joinToString("\n")
        binding.editLabels.setText(labelsText)
        
        config.enabledClasses?.let { classes ->
            binding.editEnabledClasses.setText(classes.joinToString(", "))
        } ?: binding.editEnabledClasses.setText("")
        
        updateLabelCount()
    }
    
    fun collectConfig() {
        val config = ModelConfigManager.getCurrentConfig()
        
        val labelsText = binding.editLabels.text.toString()
        config.labels = labelsText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        val enabledClassesText = binding.editEnabledClasses.text.toString()
        config.enabledClasses = if (enabledClassesText.isBlank()) {
            null
        } else {
            try {
                enabledClassesText.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.toInt() }
            } catch (e: Exception) {
                null
            }
        }
        
        ModelConfigManager.updateConfig(config)
    }
    
    private fun loadCocoLabels() {
        val cocoLabels = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
        binding.editLabels.setText(cocoLabels.joinToString("\n"))
        updateLabelCount()
    }
    
    private fun loadImageNetLabels() {
        val imageNetLabels = (0 until 1000).map { "class_$it" }
        binding.editLabels.setText(imageNetLabels.joinToString("\n"))
        updateLabelCount()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
