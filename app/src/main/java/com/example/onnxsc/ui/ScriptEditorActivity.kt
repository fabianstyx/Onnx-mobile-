package com.example.onnxsc.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.onnxsc.R
import com.example.onnxsc.databinding.ActivityScriptEditorBinding
import com.example.onnxsc.engine.Prediction
import com.example.onnxsc.engine.ScriptLogger
import com.example.onnxsc.engine.ScriptRuntime
import com.example.onnxsc.engine.ScriptStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScriptEditorActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var scriptStorage: ScriptStorage
    private lateinit var scriptRuntime: ScriptRuntime
    
    private var currentScriptName: String? = null
    private var hasUnsavedChanges = false
    private var isLogsExpanded = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        scriptStorage = ScriptStorage(this)
        scriptRuntime = ScriptRuntime(this)
        
        setupToolbar()
        setupEditor()
        setupButtons()
        setupLogs()
        
        scriptStorage.ensureDefaultScript()
        loadScriptList()
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
    
    private fun setupEditor() {
        binding.editScript.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                hasUnsavedChanges = true
                updateSaveButtonState()
            }
        })
        
        binding.spinnerScripts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val scriptName = parent?.getItemAtPosition(position) as? String ?: return
                loadScript(scriptName)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        binding.btnNewScript.setOnClickListener { showNewScriptDialog() }
        binding.btnDeleteScript.setOnClickListener { confirmDeleteScript() }
    }
    
    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveCurrentScript() }
        binding.btnTest.setOnClickListener { testScript() }
        binding.btnActivate.setOnClickListener { toggleActivation() }
        
        updateActivateButton()
    }
    
    private fun setupLogs() {
        binding.btnClearLogs.setOnClickListener {
            ScriptLogger.clear()
            binding.txtLogs.text = ""
        }
        
        binding.btnToggleLogs.setOnClickListener {
            isLogsExpanded = !isLogsExpanded
            val height = if (isLogsExpanded) 200 else 48
            binding.logsContainer.layoutParams.height = (height * resources.displayMetrics.density).toInt()
            binding.logsContainer.requestLayout()
            binding.logsScroll.visibility = if (isLogsExpanded) View.VISIBLE else View.GONE
        }
        
        ScriptLogger.logs.observe(this) { logs ->
            binding.txtLogs.text = logs.takeLast(100).joinToString("\n") { it.formatted() }
            binding.logsScroll.post {
                binding.logsScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    private fun loadScriptList() {
        val scripts = scriptStorage.getScriptList()
        val names = scripts.map { it.name }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerScripts.adapter = adapter
        
        val activeScript = scriptStorage.getActiveScriptName()
        if (activeScript != null) {
            val index = names.indexOf(activeScript)
            if (index >= 0) {
                binding.spinnerScripts.setSelection(index)
            }
        }
    }
    
    private fun loadScript(name: String) {
        if (hasUnsavedChanges && currentScriptName != null && currentScriptName != name) {
            AlertDialog.Builder(this)
                .setTitle("Cambios sin guardar")
                .setMessage("¿Descartar cambios en '$currentScriptName'?")
                .setPositiveButton("Descartar") { _, _ ->
                    doLoadScript(name)
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    val index = (binding.spinnerScripts.adapter as? ArrayAdapter<*>)
                        ?.let { adapter ->
                            (0 until adapter.count).firstOrNull { adapter.getItem(it) == currentScriptName }
                        }
                    if (index != null) {
                        binding.spinnerScripts.setSelection(index)
                    }
                }
                .show()
        } else {
            doLoadScript(name)
        }
    }
    
    private fun doLoadScript(name: String) {
        val content = scriptStorage.loadScript(name)
        if (content != null) {
            currentScriptName = name
            binding.editScript.setText(content)
            hasUnsavedChanges = false
            updateSaveButtonState()
            updateActivateButton()
            ScriptLogger.info("Script '$name' cargado", "editor")
        }
    }
    
    private fun saveCurrentScript() {
        val name = currentScriptName ?: return
        val content = binding.editScript.text.toString()
        
        if (scriptStorage.saveScript(name, content)) {
            hasUnsavedChanges = false
            updateSaveButtonState()
            Toast.makeText(this, "Script guardado", Toast.LENGTH_SHORT).show()
            ScriptLogger.info("Script '$name' guardado", "editor")
        } else {
            Toast.makeText(this, "Error al guardar", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testScript() {
        val code = binding.editScript.text.toString()
        
        ScriptLogger.info("=== INICIANDO PRUEBA ===", "test")
        
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val testPrediction = Prediction(
                    target = true,
                    x = 540f,
                    y = 960f,
                    confidence = 0.85f,
                    className = "enemy",
                    classId = 0,
                    width = 100f,
                    height = 150f
                )
                scriptRuntime.testScript(code, testPrediction)
            }
            
            ScriptLogger.info("Resultado:\n$result", "test")
            ScriptLogger.info("=== FIN DE PRUEBA ===", "test")
        }
    }
    
    private fun toggleActivation() {
        val name = currentScriptName ?: return
        val isActive = scriptStorage.getActiveScriptName() == name && scriptStorage.isScriptingEnabled()
        
        if (isActive) {
            scriptStorage.setScriptingEnabled(false)
            ScriptLogger.info("Script desactivado", "editor")
            Toast.makeText(this, "Script desactivado", Toast.LENGTH_SHORT).show()
        } else {
            if (hasUnsavedChanges) {
                saveCurrentScript()
            }
            scriptStorage.setActiveScript(name)
            scriptStorage.setScriptingEnabled(true)
            ScriptLogger.info("Script '$name' activado", "editor")
            Toast.makeText(this, "Script '$name' activado", Toast.LENGTH_SHORT).show()
        }
        
        updateActivateButton()
    }
    
    private fun updateActivateButton() {
        val name = currentScriptName
        val isActive = name != null && 
                       scriptStorage.getActiveScriptName() == name && 
                       scriptStorage.isScriptingEnabled()
        
        binding.btnActivate.text = if (isActive) "Desactivar" else "Activar"
        binding.btnActivate.setBackgroundColor(
            if (isActive) getColor(android.R.color.holo_red_dark)
            else getColor(android.R.color.holo_purple)
        )
    }
    
    private fun updateSaveButtonState() {
        binding.btnSave.alpha = if (hasUnsavedChanges) 1f else 0.5f
    }
    
    private fun showNewScriptDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_script, null)
        val editName = dialogView.findViewById<EditText>(R.id.editScriptName)
        
        AlertDialog.Builder(this)
            .setTitle("Nuevo Script")
            .setView(dialogView)
            .setPositiveButton("Crear") { _, _ ->
                val name = editName.text.toString().trim()
                if (name.isNotEmpty()) {
                    createNewScript(name)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun createNewScript(name: String) {
        val defaultContent = ScriptStorage.DEFAULT_SCRIPT.trim()
        
        if (scriptStorage.saveScript(name, defaultContent)) {
            loadScriptList()
            val adapter = binding.spinnerScripts.adapter as? ArrayAdapter<*>
            val index = (0 until (adapter?.count ?: 0)).firstOrNull { 
                adapter?.getItem(it) == name 
            }
            if (index != null) {
                binding.spinnerScripts.setSelection(index)
            }
            ScriptLogger.info("Script '$name' creado", "editor")
        }
    }
    
    private fun confirmDeleteScript() {
        val name = currentScriptName ?: return
        
        AlertDialog.Builder(this)
            .setTitle("Eliminar Script")
            .setMessage("¿Eliminar '$name'? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                if (scriptStorage.deleteScript(name)) {
                    ScriptLogger.info("Script '$name' eliminado", "editor")
                    loadScriptList()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scriptRuntime.stop()
    }
}
