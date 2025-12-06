package com.example.onnxsc.engine

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import java.io.File
import java.io.IOException

enum class ConfigParamType {
    SLIDER_INT,
    SLIDER_FLOAT,
    DROPDOWN,
    TOGGLE,
    LABEL,
    IMAGE,
    STRING,
    COLOR,
    INT_LIST,
    FLOAT_LIST,
    STRING_LIST
}

data class ConfigParam(
    val key: String,
    val type: ConfigParamType,
    val value: Any,
    val section: String,
    val displayName: String = key,
    val description: String = "",
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Float = 1f,
    val options: List<String> = emptyList(),
    val imagePath: String = ""
) {
    fun asInt(default: Int = 0): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Float -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
    
    fun asFloat(default: Float = 0f): Float = when (value) {
        is Float -> value
        is Double -> value.toFloat()
        is Int -> value.toFloat()
        is Long -> value.toFloat()
        is String -> value.toFloatOrNull() ?: default
        else -> default
    }
    
    fun asDouble(default: Double = 0.0): Double = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: default
        else -> default
    }
    
    fun asBool(default: Boolean = false): Boolean = when (value) {
        is Boolean -> value
        is Int -> value != 0
        is String -> value.lowercase() in listOf("true", "1", "yes", "on", "enabled")
        else -> default
    }
    
    fun asString(default: String = ""): String = value.toString().ifBlank { default }
    
    @Suppress("UNCHECKED_CAST")
    fun asIntList(separator: String = ","): List<Int> = when (value) {
        is List<*> -> value.mapNotNull { (it as? Number)?.toInt() }
        is String -> value.split(separator).mapNotNull { it.trim().toIntOrNull() }
        else -> emptyList()
    }
    
    @Suppress("UNCHECKED_CAST")
    fun asFloatList(separator: String = ","): List<Float> = when (value) {
        is List<*> -> value.mapNotNull { (it as? Number)?.toFloat() }
        is String -> value.split(separator).mapNotNull { it.trim().toFloatOrNull() }
        else -> emptyList()
    }
    
    fun asStringList(separator: String = ","): List<String> = when (value) {
        is List<*> -> value.map { it.toString() }
        is String -> value.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}

data class ConfigSection(
    val name: String,
    val displayName: String = name,
    val description: String = "",
    val icon: String = "",
    val params: MutableMap<String, ConfigParam> = mutableMapOf()
)

object ConfigEngine {
    
    private const val APP_FOLDER_NAME = "com.onxxs.on"
    private const val CONFIG_FILE_NAME = "config.ini"
    private const val MODELS_FOLDER = "models"
    private const val SCRIPTS_FOLDER = "scripts"
    private const val LOGS_FOLDER = "logs"
    private const val CACHE_FOLDER = "cache"
    
    private val sections = mutableMapOf<String, ConfigSection>()
    private var configFile: File? = null
    private var appDataDir: File? = null
    private var lastModified: Long = 0
    private var isLoaded = false
    private var fileObserver: FileObserver? = null
    
    private val listeners = mutableListOf<() -> Unit>()
    private var appContext: Context? = null
    
    val isEnabled: Boolean get() = getBool("general", "enabled", true)
    val isDebugMode: Boolean get() = getBool("general", "debug_mode", false)
    val autoStart: Boolean get() = getBool("general", "auto_start", false)
    val language: String get() = getString("general", "language", "es")
    
    val confidenceThreshold: Float get() = getFloat("detection", "confidence_threshold", 0.25f)
    val nmsThreshold: Float get() = getFloat("detection", "nms_threshold", 0.45f)
    val maxDetections: Int get() = getInt("detection", "max_detections", 100)
    val enabledClasses: List<Int> get() = getIntList("detection", "enabled_classes")
    
    val roiEnabled: Boolean get() = getBool("regions", "roi_enabled", false)
    val roiX: Int get() = getInt("regions", "roi_x", 0)
    val roiY: Int get() = getInt("regions", "roi_y", 0)
    val roiWidth: Int get() = getInt("regions", "roi_width", 1920)
    val roiHeight: Int get() = getInt("regions", "roi_height", 1080)
    
    val minWidth: Float get() = getFloat("filters", "min_width", 10f)
    val minHeight: Float get() = getFloat("filters", "min_height", 10f)
    val maxWidth: Float get() = getFloat("filters", "max_width", 1920f)
    val maxHeight: Float get() = getFloat("filters", "max_height", 1080f)
    val minArea: Float get() = getFloat("filters", "min_area", 100f)
    val maxArea: Float get() = getFloat("filters", "max_area", 2073600f)
    
    val actionEnabled: Boolean get() = getBool("actions", "action_enabled", true)
    val actionMode: String get() = getString("actions", "action_mode", "single")
    val actionDelayMs: Int get() = getInt("actions", "action_delay_ms", 100)
    val tapDurationMs: Int get() = getInt("actions", "tap_duration_ms", 50)
    val swipeDurationMs: Int get() = getInt("actions", "swipe_duration_ms", 300)
    val longPressDurationMs: Int get() = getInt("actions", "long_press_duration_ms", 500)
    val defaultAction: String get() = getString("actions", "default_action", "tap")
    val swipeDistance: Int get() = getInt("actions", "swipe_distance", 200)
    
    val targetMode: String get() = getString("targeting", "target_mode", "center")
    val offsetX: Int get() = getInt("targeting", "offset_x", 0)
    val offsetY: Int get() = getInt("targeting", "offset_y", 0)
    val priorityMode: String get() = getString("targeting", "priority_mode", "highest_confidence")
    val priorityClass: Int get() = getInt("targeting", "priority_class", -1)
    
    val showBoxes: Boolean get() = getBool("overlay", "show_boxes", true)
    val showLabels: Boolean get() = getBool("overlay", "show_labels", true)
    val showConfidence: Boolean get() = getBool("overlay", "show_confidence", true)
    val boxColor: String get() = getString("overlay", "box_color", "#FF0000")
    val labelSize: Int get() = getInt("overlay", "label_size", 14)
    val boxThickness: Float get() = getFloat("overlay", "box_thickness", 2f)
    
    val frameSkip: Int get() = getInt("performance", "frame_skip", 0)
    val processingThreads: Int get() = getInt("performance", "processing_threads", 4)
    val useGpu: Boolean get() = getBool("performance", "use_gpu", true)
    val useNnapi: Boolean get() = getBool("performance", "use_nnapi", false)
    
    val logDetections: Boolean get() = getBool("logging", "log_detections", false)
    val logActions: Boolean get() = getBool("logging", "log_actions", true)
    val logPerformance: Boolean get() = getBool("logging", "log_performance", false)
    
    fun init(context: Context): Boolean {
        appContext = context.applicationContext
        
        appDataDir = getOrCreateAppDataDir(context)
        if (appDataDir == null) {
            appDataDir = context.filesDir
        }
        
        createAppFolderStructure()
        
        configFile = File(appDataDir, CONFIG_FILE_NAME)
        
        if (!configFile!!.exists()) {
            copyAssetConfigToExternal(context)
        }
        
        setupFileObserver()
        
        return reload()
    }
    
    private fun getOrCreateAppDataDir(context: Context): File? {
        return try {
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val parentDir = externalDir.parentFile?.parentFile?.parentFile?.parentFile
                val appDir = File(parentDir, "Android/data/$APP_FOLDER_NAME")
                if (!appDir.exists()) {
                    appDir.mkdirs()
                }
                if (appDir.exists() && appDir.canWrite()) {
                    appDir
                } else {
                    context.getExternalFilesDir(null) ?: context.filesDir
                }
            } else {
                context.filesDir
            }
        } catch (e: Exception) {
            e.printStackTrace()
            context.filesDir
        }
    }
    
    private fun createAppFolderStructure() {
        val dir = appDataDir ?: return
        
        listOf(MODELS_FOLDER, SCRIPTS_FOLDER, LOGS_FOLDER, CACHE_FOLDER).forEach { folder ->
            val subDir = File(dir, folder)
            if (!subDir.exists()) {
                subDir.mkdirs()
            }
        }
    }
    
    fun getAppDataDir(): File? = appDataDir
    fun getModelsDir(): File? = appDataDir?.let { File(it, MODELS_FOLDER) }
    fun getScriptsDir(): File? = appDataDir?.let { File(it, SCRIPTS_FOLDER) }
    fun getLogsDir(): File? = appDataDir?.let { File(it, LOGS_FOLDER) }
    fun getCacheDir(): File? = appDataDir?.let { File(it, CACHE_FOLDER) }
    
    private fun copyAssetConfigToExternal(context: Context) {
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
    
    private fun setupFileObserver() {
        val file = configFile ?: return
        val parentPath = file.parentFile?.path ?: return
        
        fileObserver?.stopWatching()
        
        fileObserver = object : FileObserver(parentPath, MODIFY or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == CONFIG_FILE_NAME) {
                    checkAndReloadIfModified()
                }
            }
        }
        fileObserver?.startWatching()
    }
    
    fun initFromPath(path: String): Boolean {
        configFile = File(path)
        appDataDir = configFile?.parentFile
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
        sections.clear()
        var currentSection = "general"
        var currentSectionObj = ConfigSection(currentSection)
        sections[currentSection] = currentSectionObj
        
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";") -> {}
                
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    currentSection = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                    if (!sections.containsKey(currentSection)) {
                        currentSectionObj = ConfigSection(currentSection)
                        sections[currentSection] = currentSectionObj
                    } else {
                        currentSectionObj = sections[currentSection]!!
                    }
                }
                
                trimmed.contains("=") -> {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase()
                        val rawValue = parts[1].trim()
                        
                        val param = parseParamValue(key, rawValue, currentSection)
                        currentSectionObj.params[key] = param
                    }
                }
            }
        }
    }
    
    private fun parseParamValue(key: String, rawValue: String, section: String): ConfigParam {
        val metaMatch = Regex("""^@(\w+)(?:\(([^)]*)\))?:(.*)$""").find(rawValue)
        
        return if (metaMatch != null) {
            val typeName = metaMatch.groupValues[1].uppercase()
            val metadata = metaMatch.groupValues[2]
            val actualValue = metaMatch.groupValues[3].trim()
            
            when (typeName) {
                "SLIDER_INT", "SLIDER" -> {
                    val range = parseSliderMetadata(metadata)
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.SLIDER_INT,
                        value = actualValue.toIntOrNull() ?: 0,
                        section = section,
                        min = range.first,
                        max = range.second,
                        step = range.third
                    )
                }
                "SLIDER_FLOAT" -> {
                    val range = parseSliderMetadata(metadata)
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.SLIDER_FLOAT,
                        value = actualValue.toFloatOrNull() ?: 0f,
                        section = section,
                        min = range.first,
                        max = range.second,
                        step = range.third
                    )
                }
                "DROPDOWN", "SELECT" -> {
                    val options = metadata.split("|").map { it.trim() }
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.DROPDOWN,
                        value = actualValue,
                        section = section,
                        options = options
                    )
                }
                "TOGGLE", "BOOL", "ONOFF" -> {
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.TOGGLE,
                        value = actualValue.lowercase() in listOf("true", "1", "yes", "on"),
                        section = section
                    )
                }
                "LABEL", "TEXT" -> {
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.LABEL,
                        value = actualValue,
                        section = section
                    )
                }
                "IMAGE", "IMG" -> {
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.IMAGE,
                        value = "",
                        section = section,
                        imagePath = actualValue
                    )
                }
                "COLOR" -> {
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.COLOR,
                        value = actualValue,
                        section = section
                    )
                }
                else -> {
                    ConfigParam(
                        key = key,
                        type = ConfigParamType.STRING,
                        value = actualValue,
                        section = section
                    )
                }
            }
        } else {
            val (type, value) = inferTypeFromValue(rawValue)
            ConfigParam(
                key = key,
                type = type,
                value = value,
                section = section
            )
        }
    }
    
    private fun parseSliderMetadata(metadata: String): Triple<Float, Float, Float> {
        val parts = metadata.split(",").map { it.trim() }
        val min = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
        val max = parts.getOrNull(1)?.toFloatOrNull() ?: 100f
        val step = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        return Triple(min, max, step)
    }
    
    private fun inferTypeFromValue(rawValue: String): Pair<ConfigParamType, Any> {
        val lower = rawValue.lowercase()
        
        if (lower in listOf("true", "false", "yes", "no", "on", "off", "enabled", "disabled")) {
            return Pair(ConfigParamType.TOGGLE, lower in listOf("true", "yes", "on", "enabled"))
        }
        
        rawValue.toIntOrNull()?.let {
            return Pair(ConfigParamType.SLIDER_INT, it)
        }
        
        rawValue.toFloatOrNull()?.let {
            return Pair(ConfigParamType.SLIDER_FLOAT, it)
        }
        
        if (rawValue.startsWith("#") && rawValue.length in 7..9) {
            return Pair(ConfigParamType.COLOR, rawValue)
        }
        
        if (rawValue.contains(",")) {
            val parts = rawValue.split(",").map { it.trim() }
            if (parts.all { it.toIntOrNull() != null }) {
                return Pair(ConfigParamType.INT_LIST, parts.mapNotNull { it.toIntOrNull() })
            }
            if (parts.all { it.toFloatOrNull() != null }) {
                return Pair(ConfigParamType.FLOAT_LIST, parts.mapNotNull { it.toFloatOrNull() })
            }
            return Pair(ConfigParamType.STRING_LIST, parts)
        }
        
        return Pair(ConfigParamType.STRING, rawValue)
    }
    
    private fun createDefaultConfig(file: File) {
        val defaultConfig = """
# ONNX Screen Capture - Mobile Configuration
# Format: key = value  OR  key = @TYPE(metadata):value
# Types: SLIDER_INT, SLIDER_FLOAT, DROPDOWN, TOGGLE, LABEL, IMAGE, COLOR

[general]
enabled = @TOGGLE:true
debug_mode = @TOGGLE:false
auto_start = @TOGGLE:false
language = @DROPDOWN(es|en|pt|fr|de):es
app_name = @LABEL:ONNX Screen Capture v1.0

[detection]
confidence_threshold = @SLIDER_FLOAT(0,1,0.01):0.25
nms_threshold = @SLIDER_FLOAT(0,1,0.01):0.45
max_detections = @SLIDER_INT(1,500,1):100
enabled_classes = 

[regions]
roi_enabled = @TOGGLE:false
roi_x = @SLIDER_INT(0,3840,1):0
roi_y = @SLIDER_INT(0,2160,1):0
roi_width = @SLIDER_INT(100,3840,1):1920
roi_height = @SLIDER_INT(100,2160,1):1080

[filters]
min_width = @SLIDER_FLOAT(0,500,1):10
min_height = @SLIDER_FLOAT(0,500,1):10
max_width = @SLIDER_FLOAT(100,3840,1):1920
max_height = @SLIDER_FLOAT(100,2160,1):1080
min_area = @SLIDER_FLOAT(0,100000,100):100
max_area = @SLIDER_FLOAT(1000,10000000,1000):2073600

[actions]
action_enabled = @TOGGLE:true
action_mode = @DROPDOWN(single|all|batch):single
default_action = @DROPDOWN(tap|double_tap|long_press|swipe|none):tap
action_delay_ms = @SLIDER_INT(0,2000,10):100
tap_duration_ms = @SLIDER_INT(10,500,5):50
swipe_duration_ms = @SLIDER_INT(50,1000,10):300
long_press_duration_ms = @SLIDER_INT(100,2000,50):500
swipe_distance = @SLIDER_INT(50,1000,10):200
batch_size = @SLIDER_INT(1,20,1):5
class_actions = 

[targeting]
target_mode = @DROPDOWN(center|top_left|top_right|bottom_left|bottom_right|random):center
offset_x = @SLIDER_INT(-500,500,1):0
offset_y = @SLIDER_INT(-500,500,1):0
priority_mode = @DROPDOWN(highest_confidence|lowest_confidence|largest_area|smallest_area|closest_to_center|first_detected|by_class):highest_confidence
priority_class = @SLIDER_INT(-1,100,1):-1

[overlay]
show_boxes = @TOGGLE:true
show_labels = @TOGGLE:true
show_confidence = @TOGGLE:true
box_color = @COLOR:#FF0000
label_size = @SLIDER_INT(8,32,1):14
box_thickness = @SLIDER_FLOAT(1,10,0.5):2

[performance]
frame_skip = @SLIDER_INT(0,10,1):0
processing_threads = @SLIDER_INT(1,8,1):4
use_gpu = @TOGGLE:true
use_nnapi = @TOGGLE:false

[logging]
log_detections = @TOGGLE:false
log_actions = @TOGGLE:true
log_performance = @TOGGLE:false
        """.trimIndent()
        
        file.writeText(defaultConfig)
    }
    
    fun get(section: String, key: String): ConfigParam? {
        return sections[section.lowercase()]?.params?.get(key.lowercase())
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
        
        if (!sections.containsKey(sec)) {
            sections[sec] = ConfigSection(sec)
        }
        
        val existingParam = sections[sec]?.params?.get(k)
        val newParam = if (existingParam != null) {
            existingParam.copy(value = value)
        } else {
            val (type, _) = inferTypeFromValue(value.toString())
            ConfigParam(key = k, type = type, value = value, section = sec)
        }
        
        sections[sec]?.params?.set(k, newParam)
    }
    
    fun save(): Boolean {
        val file = configFile ?: return false
        
        return try {
            val builder = StringBuilder()
            builder.appendLine("# ONNX Screen Capture - Mobile Configuration")
            builder.appendLine("# Auto-saved at ${System.currentTimeMillis()}")
            builder.appendLine()
            
            sections.forEach { (sectionName, section) ->
                builder.appendLine("[$sectionName]")
                section.params.forEach { (key, param) ->
                    val valueStr = formatParamForSave(param)
                    builder.appendLine("$key = $valueStr")
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
    
    private fun formatParamForSave(param: ConfigParam): String {
        return when (param.type) {
            ConfigParamType.SLIDER_INT -> {
                "@SLIDER_INT(${param.min.toInt()},${param.max.toInt()},${param.step.toInt()}):${param.asInt()}"
            }
            ConfigParamType.SLIDER_FLOAT -> {
                "@SLIDER_FLOAT(${param.min},${param.max},${param.step}):${param.asFloat()}"
            }
            ConfigParamType.DROPDOWN -> {
                "@DROPDOWN(${param.options.joinToString("|")}):${param.asString()}"
            }
            ConfigParamType.TOGGLE -> {
                "@TOGGLE:${param.asBool()}"
            }
            ConfigParamType.LABEL -> {
                "@LABEL:${param.asString()}"
            }
            ConfigParamType.IMAGE -> {
                "@IMAGE:${param.imagePath}"
            }
            ConfigParamType.COLOR -> {
                "@COLOR:${param.asString()}"
            }
            else -> param.value.toString()
        }
    }
    
    fun getSections(): Set<String> = sections.keys.toSet()
    
    fun getSection(name: String): ConfigSection? = sections[name.lowercase()]
    
    fun getKeys(section: String): Set<String> {
        return sections[section.lowercase()]?.params?.keys?.toSet() ?: emptySet()
    }
    
    fun getAllParams(section: String): Map<String, ConfigParam> {
        return sections[section.lowercase()]?.params?.toMap() ?: emptyMap()
    }
    
    fun getParamsByType(type: ConfigParamType): List<ConfigParam> {
        return sections.values.flatMap { section ->
            section.params.values.filter { it.type == type }
        }
    }
    
    fun hasSection(section: String): Boolean = sections.containsKey(section.lowercase())
    
    fun hasKey(section: String, key: String): Boolean {
        return sections[section.lowercase()]?.params?.containsKey(key.lowercase()) ?: false
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
        sb.appendLine("App Data Dir: ${appDataDir?.absolutePath}")
        sb.appendLine("Config File: ${configFile?.absolutePath}")
        sb.appendLine()
        sections.forEach { (section, data) ->
            sb.appendLine("[$section] (${data.params.size} params)")
            data.params.forEach { (key, param) ->
                sb.appendLine("  $key [${param.type}] = ${param.value}")
            }
        }
        return sb.toString()
    }
    
    fun stopFileObserver() {
        fileObserver?.stopWatching()
        fileObserver = null
    }
}
