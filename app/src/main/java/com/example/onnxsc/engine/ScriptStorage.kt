package com.example.onnxsc.engine

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ScriptStorage(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "script_storage"
        private const val KEY_ACTIVE_SCRIPT = "active_script"
        private const val KEY_SCRIPT_LIST = "script_list"
        private const val KEY_SCRIPT_ENABLED = "script_enabled"
        private const val SCRIPTS_DIR = "scripts"
        
        const val DEFAULT_SCRIPT = """
// Script de ejemplo - se ejecuta por cada detección
// 'prediction' contiene: target, x, y, confidence, className, classId

if (prediction.target && prediction.confidence > 0.5) {
    log("Objetivo detectado: " + prediction.className + " (" + (prediction.confidence * 100).toFixed(1) + "%)");
    
    // Tocar en la posición del objetivo
    tap(prediction.x, prediction.y);
    
    // O usar gamepad
    // gamepad.press("R2");
    // delay(100);
    // gamepad.release("R2");
}
"""
    }
    
    data class ScriptInfo(
        val name: String,
        val filename: String,
        val createdAt: Long,
        val modifiedAt: Long
    )
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scriptsDir: File
        get() {
            val dir = File(context.filesDir, SCRIPTS_DIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    
    fun saveScript(name: String, content: String): Boolean {
        return try {
            val filename = sanitizeFilename(name) + ".js"
            val file = File(scriptsDir, filename)
            file.writeText(content)
            
            val scripts = getScriptList().toMutableList()
            val existingIndex = scripts.indexOfFirst { it.name == name }
            val now = System.currentTimeMillis()
            
            val info = ScriptInfo(
                name = name,
                filename = filename,
                createdAt = if (existingIndex >= 0) scripts[existingIndex].createdAt else now,
                modifiedAt = now
            )
            
            if (existingIndex >= 0) {
                scripts[existingIndex] = info
            } else {
                scripts.add(info)
            }
            
            saveScriptList(scripts)
            true
        } catch (e: Exception) {
            ScriptLogger.error("Error guardando script: ${e.message}", "storage")
            false
        }
    }
    
    fun loadScript(name: String): String? {
        return try {
            val scripts = getScriptList()
            val info = scripts.find { it.name == name } ?: return null
            val file = File(scriptsDir, info.filename)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            ScriptLogger.error("Error cargando script: ${e.message}", "storage")
            null
        }
    }
    
    fun deleteScript(name: String): Boolean {
        return try {
            val scripts = getScriptList().toMutableList()
            val info = scripts.find { it.name == name } ?: return false
            
            val file = File(scriptsDir, info.filename)
            if (file.exists()) file.delete()
            
            scripts.removeAll { it.name == name }
            saveScriptList(scripts)
            
            if (getActiveScriptName() == name) {
                setActiveScript(null)
            }
            
            true
        } catch (e: Exception) {
            ScriptLogger.error("Error eliminando script: ${e.message}", "storage")
            false
        }
    }
    
    fun getScriptList(): List<ScriptInfo> {
        val json = prefs.getString(KEY_SCRIPT_LIST, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ScriptInfo>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun saveScriptList(scripts: List<ScriptInfo>) {
        prefs.edit().putString(KEY_SCRIPT_LIST, gson.toJson(scripts)).apply()
    }
    
    fun setActiveScript(name: String?) {
        prefs.edit().putString(KEY_ACTIVE_SCRIPT, name).apply()
    }
    
    fun getActiveScriptName(): String? {
        return prefs.getString(KEY_ACTIVE_SCRIPT, null)
    }
    
    fun getActiveScript(): String? {
        val name = getActiveScriptName() ?: return null
        return loadScript(name)
    }
    
    fun isScriptingEnabled(): Boolean {
        return prefs.getBoolean(KEY_SCRIPT_ENABLED, false)
    }
    
    fun setScriptingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCRIPT_ENABLED, enabled).apply()
    }
    
    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
    }
    
    fun ensureDefaultScript() {
        if (getScriptList().isEmpty()) {
            saveScript("default", DEFAULT_SCRIPT.trim())
            setActiveScript("default")
        }
    }
}
