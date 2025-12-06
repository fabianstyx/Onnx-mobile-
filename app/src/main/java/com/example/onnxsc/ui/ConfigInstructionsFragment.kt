package com.example.onnxsc.ui

import android.os.Bundle
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.onnxsc.config.ModelConfigManager
import com.example.onnxsc.databinding.FragmentConfigInstructionsBinding

class ConfigInstructionsFragment : Fragment() {

    private var _binding: FragmentConfigInstructionsBinding? = null
    private val binding get() = _binding!!
    
    private var isEditMode = false
    private var instructionsContent = ""
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigInstructionsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        loadConfig()
    }
    
    private fun setupButtons() {
        binding.btnToggleEdit.setOnClickListener {
            toggleEditMode()
        }
        
        binding.btnSaveInstructions.setOnClickListener {
            saveInstructions()
        }
    }
    
    private fun toggleEditMode() {
        isEditMode = !isEditMode
        updateViewMode()
    }
    
    private fun updateViewMode() {
        if (isEditMode) {
            binding.txtInstructionsRendered.visibility = View.GONE
            binding.layoutInstructionsEdit.visibility = View.VISIBLE
            binding.btnSaveInstructions.visibility = View.VISIBLE
            binding.btnToggleEdit.text = "Ver"
            binding.editInstructions.setText(instructionsContent)
        } else {
            binding.txtInstructionsRendered.visibility = View.VISIBLE
            binding.layoutInstructionsEdit.visibility = View.GONE
            binding.btnSaveInstructions.visibility = View.GONE
            binding.btnToggleEdit.text = "Editar"
            renderMarkdown()
        }
    }
    
    fun loadConfig() {
        instructionsContent = ModelConfigManager.loadInstructions(requireContext())
        renderMarkdown()
        updateConfigSummary()
    }
    
    fun collectConfig() {
        if (isEditMode) {
            instructionsContent = binding.editInstructions.text.toString()
        }
    }
    
    private fun saveInstructions() {
        instructionsContent = binding.editInstructions.text.toString()
        
        if (ModelConfigManager.saveInstructions(requireContext(), instructionsContent)) {
            Toast.makeText(requireContext(), "Instrucciones guardadas", Toast.LENGTH_SHORT).show()
            toggleEditMode()
        } else {
            Toast.makeText(requireContext(), "Error al guardar", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun renderMarkdown() {
        val rendered = parseMarkdownToSpanned(instructionsContent)
        binding.txtInstructionsRendered.text = rendered
    }
    
    private fun parseMarkdownToSpanned(markdown: String): Spanned {
        val html = StringBuilder()
        
        val lines = markdown.lines()
        var inList = false
        
        for (line in lines) {
            val trimmed = line.trim()
            
            when {
                trimmed.startsWith("### ") -> {
                    if (inList) {
                        html.append("</ul>")
                        inList = false
                    }
                    html.append("<h4>${trimmed.removePrefix("### ")}</h4>")
                }
                trimmed.startsWith("## ") -> {
                    if (inList) {
                        html.append("</ul>")
                        inList = false
                    }
                    html.append("<h3>${trimmed.removePrefix("## ")}</h3>")
                }
                trimmed.startsWith("# ") -> {
                    if (inList) {
                        html.append("</ul>")
                        inList = false
                    }
                    html.append("<h2>${trimmed.removePrefix("# ")}</h2>")
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    if (!inList) {
                        html.append("<ul>")
                        inList = true
                    }
                    val content = trimmed.removePrefix("- ").removePrefix("* ")
                    html.append("<li>${formatInlineMarkdown(content)}</li>")
                }
                trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    if (!inList) {
                        html.append("<ol>")
                        inList = true
                    }
                    val content = trimmed.replaceFirst(Regex("^\\d+\\.\\s+"), "")
                    html.append("<li>${formatInlineMarkdown(content)}</li>")
                }
                trimmed.isEmpty() -> {
                    if (inList) {
                        html.append("</ul>")
                        inList = false
                    }
                    html.append("<br>")
                }
                else -> {
                    if (inList) {
                        html.append("</ul>")
                        inList = false
                    }
                    html.append("<p>${formatInlineMarkdown(trimmed)}</p>")
                }
            }
        }
        
        if (inList) {
            html.append("</ul>")
        }
        
        return Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_LEGACY)
    }
    
    private fun formatInlineMarkdown(text: String): String {
        var result = text
        
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        result = result.replace(Regex("__(.+?)__"), "<b>$1</b>")
        
        result = result.replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
        result = result.replace(Regex("_(.+?)_"), "<i>$1</i>")
        
        result = result.replace(Regex("`(.+?)`"), "<code>$1</code>")
        
        return result
    }
    
    private fun updateConfigSummary() {
        val summary = ModelConfigManager.getConfigSummary()
        binding.txtConfigSummary.text = summary
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
