package com.example.onnxsc.engine

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

data class ConfigValue(
    val raw: String,
    val section: String,
    val key: String
) {
    fun asInt(default: Int = 0): Int = raw.toIntOrNull() ?: default
    fun asFloat(default: Float = 0f): Float = raw.toFloatOrNull() ?: default
    fun asDouble(default: Double = 0.0): Double = raw.toDoubleOrNull() ?: default
    fun asBool(default: Boolean = false): Boolean {
        return when (raw.lowercase().trim()) {
            "true", "1", "yes", "on", "enabled" -> true
            "false", "0", "no", "off", "disabled" -> false
            else -> default
        }
    }
    fun asString(default: String = ""): String = raw.ifBlank { default }
    fun asIntList(separator: String = ","): List<Int> {
        return raw.split(separator).mapNotNull { it.trim().toIntOrNull() }
    }
    fun asFloatList(separator: String = ","): List<Float> {
        return raw.split(separator).mapNotNull { it.trim().toFloatOrNull() }
    }
    fun asStringList(separator: String = ","): List<String> {
        return raw.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

object ConfigEngine {
    
    private const val CONFIG_FILE_NAME = "config.ini"
    private val config = mutableMapOf<String, MutableMap<String, ConfigValue>>()
    private var configFile: File? = null
    private var lastModified: Long = 0
    private var isLoaded = false
    
    private val listeners = mutableListOf<() -> Unit>()
    
    private var appContext: Context? = null
    
    fun init(context: Context): Boolean {
        appContext = context.applicationContext
        configFile = File(context.filesDir, CONFIG_FILE_NAME)
        
        if (!configFile!!.exists()) {
            copyAssetConfigToInternal(context)
        }
        
        return reload()
    }
    
    private fun copyAssetConfigToInternal(context: Context) {
        try {
            context.assets.open(CONFIG_FILE_NAME).use { inputStream ->
                configFile?.outputStream()?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: IOException) {
            configFile?.let { createDefaultConfig(it) }
        }
    }
    
    fun initFromPath(path: String): Boolean {
        configFile = File(path)
        return reload()
    }
    
    fun reload(): Boolean {
        val file = configFile ?: return false
        
        if (!file.exists()) {
            createDefaultConfig(file)
        }
        
        return try {
            parseConfigFile(file)
            lastModified = file.lastModified()
            isLoaded = true
            notifyListeners()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun checkAndReloadIfModified(): Boolean {
        val file = configFile ?: return false
        if (file.lastModified() > lastModified) {
            return reload()
        }
        return false
    }
    
    private fun parseConfigFile(file: File) {
        config.clear()
        var currentSection = "general"
        
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";") -> {
                }
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    currentSection = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                    if (!config.containsKey(currentSection)) {
                        config[currentSection] = mutableMapOf()
                    }
                }
                trimmed.contains("=") -> {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase()
                        val value = parts[1].trim()
                        
                        if (!config.containsKey(currentSection)) {
                            config[currentSection] = mutableMapOf()
                        }
                        config[currentSection]!![key] = ConfigValue(value, currentSection, key)
                    }
                }
            }
        }
    }
    
    private fun createDefaultConfig(file: File) {
        val defaultConfig = """
            # ONNX Screen Capture - Configuration File
            # Modify values and save to apply changes
            # Use reload() or restart app to apply
            
            [general]
            enabled = true
            debug_mode = false
            auto_start = false
            language = es
            
            [detection]
            confidence_threshold = 0.25
            nms_threshold = 0.45
            max_detections = 100
            enabled_classes = 
            
            [regions]
            roi_enabled = false
            roi_x = 0
            roi_y = 0
            roi_width = 1920
            roi_height = 1080
            
            [filters]
            min_width = 10
            min_height = 10
            max_width = 1920
            max_height = 1080
            min_area = 100
            max_area = 2073600
            
            [actions]
            action_enabled = true
            action_mode = single
            action_delay_ms = 100
            tap_duration_ms = 50
            swipe_duration_ms = 300
            long_press_duration_ms = 500
            
            [targeting]
            target_mode = center
            offset_x = 0
            offset_y = 0
            priority_class = -1
            
            [overlay]
            show_boxes = true
            show_labels = true
            show_confidence = true
            box_color = #FF0000
            label_size = 14
            
            [performance]
            frame_skip = 0
            processing_threads = 4
            use_gpu = true
            
            [logging]
            log_detections = false
            log_actions = true
            log_performance = false
        """.trimIndent()
        
        file.writeText(defaultConfig)
    }
    
    fun get(section: String, key: String): ConfigValue? {
        return config[section.lowercase()]?.get(key.lowercase())
    }
    
    fun getInt(section: String, key: String, default: Int = 0): Int {
        return get(section, key)?.asInt(default) ?: default
    }
    
    fun getFloat(section: String, key: String, default: Float = 0f): Float {
        return get(section, key)?.asFloat(default) ?: default
    }
    
    fun getDouble(section: String, key: String, default: Double = 0.0): Double {
        return get(section, key)?.asDouble(default) ?: default
    }
    
    fun getBool(section: String, key: String, default: Boolean = false): Boolean {
        return get(section, key)?.asBool(default) ?: default
    }
    
    fun getString(section: String, key: String, default: String = ""): String {
        return get(section, key)?.asString(default) ?: default
    }
    
    fun getIntList(section: String, key: String): List<Int> {
        return get(section, key)?.asIntList() ?: emptyList()
    }
    
    fun getFloatList(section: String, key: String): List<Float> {
        return get(section, key)?.asFloatList() ?: emptyList()
    }
    
    fun getStringList(section: String, key: String): List<String> {
        return get(section, key)?.asStringList() ?: emptyList()
    }
    
    fun set(section: String, key: String, value: Any) {
        val sec = section.lowercase()
        val k = key.lowercase()
        
        if (!config.containsKey(sec)) {
            config[sec] = mutableMapOf()
        }
        config[sec]!![k] = ConfigValue(value.toString(), sec, k)
    }
    
    fun save(): Boolean {
        val file = configFile ?: return false
        
        return try {
            val builder = StringBuilder()
            builder.appendLine("# ONNX Screen Capture - Configuration File")
            builder.appendLine("# Auto-saved at ${System.currentTimeMillis()}")
            builder.appendLine()
            
            config.forEach { (section, values) ->
                builder.appendLine("[$section]")
                values.forEach { (key, configValue) ->
                    builder.appendLine("$key = ${configValue.raw}")
                }
                builder.appendLine()
            }
            
            file.writeText(builder.toString())
            lastModified = file.lastModified()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getSections(): Set<String> = config.keys.toSet()
    
    fun getKeys(section: String): Set<String> {
        return config[section.lowercase()]?.keys?.toSet() ?: emptySet()
    }
    
    fun getAllValues(section: String): Map<String, ConfigValue> {
        return config[section.lowercase()]?.toMap() ?: emptyMap()
    }
    
    fun hasSection(section: String): Boolean = config.containsKey(section.lowercase())
    
    fun hasKey(section: String, key: String): Boolean {
        return config[section.lowercase()]?.containsKey(key.lowercase()) ?: false
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun getConfigFilePath(): String = configFile?.absolutePath ?: ""
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it() }
    }
    
    fun getConfigSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Config Summary ===")
        config.forEach { (section, values) ->
            sb.appendLine("[$section] (${values.size} keys)")
        }
        return sb.toString()
    }
    
    val isEnabled: Boolean get() = getBool("general", "enabled", true)
    val isDebugMode: Boolean get() = getBool("general", "debug_mode", false)
    val confidenceThreshold: Float get() = getFloat("detection", "confidence_threshold", 0.25f)
    val nmsThreshold: Float get() = getFloat("detection", "nms_threshold", 0.45f)
    val maxDetections: Int get() = getInt("detection", "max_detections", 100)
    val roiEnabled: Boolean get() = getBool("regions", "roi_enabled", false)
    val roiX: Int get() = getInt("regions", "roi_x", 0)
    val roiY: Int get() = getInt("regions", "roi_y", 0)
    val roiWidth: Int get() = getInt("regions", "roi_width", 1920)
    val roiHeight: Int get() = getInt("regions", "roi_height", 1080)
    val actionEnabled: Boolean get() = getBool("actions", "action_enabled", true)
    val actionMode: String get() = getString("actions", "action_mode", "single")
    val actionDelayMs: Int get() = getInt("actions", "action_delay_ms", 100)
    val targetMode: String get() = getString("targeting", "target_mode", "center")
    val offsetX: Int get() = getInt("targeting", "offset_x", 0)
    val offsetY: Int get() = getInt("targeting", "offset_y", 0)
}
