package com.example.onnxsc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PostProcessingSettings(
    var confidenceThreshold: Float = 0.25f,
    var nmsThreshold: Float = 0.45f,
    var maxDetections: Int = 100,
    var enabledClasses: List<Int>? = null,
    var classNames: List<String>? = null,
    var modelName: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("confidence_threshold", confidenceThreshold.toDouble())
            put("nms_threshold", nmsThreshold.toDouble())
            put("max_detections", maxDetections)
            put("model_name", modelName)
            
            enabledClasses?.let { classes ->
                put("enabled_classes", JSONArray(classes))
            }
            
            classNames?.let { names ->
                put("class_names", JSONArray(names))
            }
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): PostProcessingSettings {
            return PostProcessingSettings(
                confidenceThreshold = json.optDouble("confidence_threshold", 0.25).toFloat(),
                nmsThreshold = json.optDouble("nms_threshold", 0.45).toFloat(),
                maxDetections = json.optInt("max_detections", 100),
                modelName = json.optString("model_name", ""),
                enabledClasses = json.optJSONArray("enabled_classes")?.let { arr ->
                    (0 until arr.length()).map { arr.getInt(it) }
                },
                classNames = json.optJSONArray("class_names")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            )
        }
    }
}

object PostProcessingConfig {
    
    private const val CONFIG_DIR = "postprocess_configs"
    private const val DEFAULT_CONFIG_FILE = "default_config.json"
    
    private var currentSettings: PostProcessingSettings = PostProcessingSettings()
    private var settingsChangeListeners = mutableListOf<(PostProcessingSettings) -> Unit>()
    
    fun getCurrentSettings(): PostProcessingSettings = currentSettings.copy()
    
    fun updateSettings(settings: PostProcessingSettings) {
        currentSettings = settings
        notifyListeners()
    }
    
    fun updateConfidenceThreshold(value: Float) {
        currentSettings.confidenceThreshold = value.coerceIn(0.01f, 0.99f)
        notifyListeners()
    }
    
    fun updateNmsThreshold(value: Float) {
        currentSettings.nmsThreshold = value.coerceIn(0.01f, 0.99f)
        notifyListeners()
    }
    
    fun updateMaxDetections(value: Int) {
        currentSettings.maxDetections = value.coerceIn(1, 500)
        notifyListeners()
    }
    
    fun updateEnabledClasses(classes: List<Int>?) {
        currentSettings.enabledClasses = classes
        notifyListeners()
    }
    
    fun updateClassNames(names: List<String>?) {
        currentSettings.classNames = names
        notifyListeners()
    }
    
    fun addSettingsChangeListener(listener: (PostProcessingSettings) -> Unit) {
        settingsChangeListeners.add(listener)
    }
    
    fun removeSettingsChangeListener(listener: (PostProcessingSettings) -> Unit) {
        settingsChangeListeners.remove(listener)
    }
    
    private fun notifyListeners() {
        val settingsCopy = currentSettings.copy()
        settingsChangeListeners.forEach { it(settingsCopy) }
    }
    
    fun saveConfig(context: Context, modelName: String): Boolean {
        return try {
            val configDir = File(context.filesDir, CONFIG_DIR)
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            
            val safeFileName = modelName.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json"
            val configFile = File(configDir, safeFileName)
            
            currentSettings.modelName = modelName
            val json = currentSettings.toJson()
            
            configFile.writeText(json.toString(2))
            Logger.success("Configuracion guardada: $safeFileName")
            true
        } catch (e: Exception) {
            Logger.error("Error guardando configuracion: ${e.message}")
            false
        }
    }
    
    fun loadConfig(context: Context, modelName: String): Boolean {
        return try {
            val configDir = File(context.filesDir, CONFIG_DIR)
            val safeFileName = modelName.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json"
            val configFile = File(configDir, safeFileName)
            
            if (!configFile.exists()) {
                Logger.info("No hay configuracion guardada para: $modelName")
                currentSettings = PostProcessingSettings(modelName = modelName)
                return false
            }
            
            val json = JSONObject(configFile.readText())
            currentSettings = PostProcessingSettings.fromJson(json)
            Logger.success("Configuracion cargada: $safeFileName")
            notifyListeners()
            true
        } catch (e: Exception) {
            Logger.error("Error cargando configuracion: ${e.message}")
            currentSettings = PostProcessingSettings(modelName = modelName)
            false
        }
    }
    
    fun deleteConfig(context: Context, modelName: String): Boolean {
        return try {
            val configDir = File(context.filesDir, CONFIG_DIR)
            val safeFileName = modelName.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json"
            val configFile = File(configDir, safeFileName)
            
            if (configFile.exists()) {
                configFile.delete()
                Logger.info("Configuracion eliminada: $safeFileName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.error("Error eliminando configuracion: ${e.message}")
            false
        }
    }
    
    fun listConfigs(context: Context): List<String> {
        return try {
            val configDir = File(context.filesDir, CONFIG_DIR)
            if (!configDir.exists()) return emptyList()
            
            configDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun getConfigSummary(): String {
        return buildString {
            appendLine("=== CONFIGURACION POST-PROCESO ===")
            appendLine()
            appendLine("Umbral de Confianza: ${(currentSettings.confidenceThreshold * 100).toInt()}%")
            appendLine("Umbral NMS (IoU): ${(currentSettings.nmsThreshold * 100).toInt()}%")
            appendLine("Max Detecciones: ${currentSettings.maxDetections}")
            appendLine()
            
            currentSettings.enabledClasses?.let { classes ->
                appendLine("Clases Habilitadas: ${classes.size}")
                appendLine("  IDs: ${classes.take(20).joinToString(", ")}")
                if (classes.size > 20) {
                    appendLine("  ... y ${classes.size - 20} mas")
                }
            } ?: appendLine("Clases Habilitadas: Todas")
            
            appendLine()
            currentSettings.classNames?.let { names ->
                appendLine("Nombres de Clases Personalizados: ${names.size}")
                names.take(10).forEachIndexed { idx, name ->
                    appendLine("  [$idx] $name")
                }
                if (names.size > 10) {
                    appendLine("  ... y ${names.size - 10} mas")
                }
            } ?: appendLine("Nombres de Clases: Por defecto")
        }
    }
    
    fun resetToDefaults() {
        currentSettings = PostProcessingSettings()
        notifyListeners()
        Logger.info("Configuracion restablecida a valores por defecto")
    }
}
