package com.example.onnxsc.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File

data class ModelConfig(
    @SerializedName("model_name")
    var modelName: String = "",
    
    @SerializedName("model_type")
    var modelType: String = "onnx",
    
    @SerializedName("model_path")
    var modelPath: String = "",
    
    @SerializedName("weights_path")
    var weightsPath: String? = null,
    
    @SerializedName("input_width")
    var inputWidth: Int = 640,
    
    @SerializedName("input_height")
    var inputHeight: Int = 640,
    
    @SerializedName("input_channels")
    var inputChannels: Int = 3,
    
    @SerializedName("confidence_threshold")
    var confidenceThreshold: Float = 0.25f,
    
    @SerializedName("nms_threshold")
    var nmsThreshold: Float = 0.45f,
    
    @SerializedName("max_detections")
    var maxDetections: Int = 100,
    
    @SerializedName("normalize_input")
    var normalizeInput: Boolean = true,
    
    @SerializedName("use_gpu")
    var useGpu: Boolean = false,
    
    @SerializedName("use_nnapi")
    var useNnapi: Boolean = false,
    
    @SerializedName("num_threads")
    var numThreads: Int = 4,
    
    @SerializedName("output_format")
    var outputFormat: String = "auto",
    
    @SerializedName("labels")
    var labels: List<String> = emptyList(),
    
    @SerializedName("enabled_classes")
    var enabledClasses: List<Int>? = null,
    
    @SerializedName("preprocessing")
    var preprocessing: PreprocessingConfig = PreprocessingConfig(),
    
    @SerializedName("postprocessing")
    var postprocessing: PostprocessingConfig = PostprocessingConfig(),
    
    @SerializedName("custom_params")
    var customParams: Map<String, Any> = emptyMap(),
    
    @SerializedName("description")
    var description: String = "",
    
    @SerializedName("instructions")
    var instructions: String = "",
    
    @SerializedName("version")
    var version: String = "1.0",
    
    @SerializedName("author")
    var author: String = ""
)

data class PreprocessingConfig(
    @SerializedName("mean")
    var mean: List<Float> = listOf(0f, 0f, 0f),
    
    @SerializedName("std")
    var std: List<Float> = listOf(1f, 1f, 1f),
    
    @SerializedName("scale")
    var scale: Float = 1f / 255f,
    
    @SerializedName("swap_rb")
    var swapRB: Boolean = false,
    
    @SerializedName("pad_to_square")
    var padToSquare: Boolean = true,
    
    @SerializedName("letterbox")
    var letterbox: Boolean = true
)

data class PostprocessingConfig(
    @SerializedName("apply_nms")
    var applyNms: Boolean = true,
    
    @SerializedName("apply_softmax")
    var applySoftmax: Boolean = false,
    
    @SerializedName("scale_boxes")
    var scaleBoxes: Boolean = true,
    
    @SerializedName("clip_boxes")
    var clipBoxes: Boolean = true,
    
    @SerializedName("box_format")
    var boxFormat: String = "xyxy"
)

object ModelConfigManager {
    
    private const val CONFIG_DIR = "model_configs"
    private const val BACKUP_SUFFIX = ".backup"
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    private var currentModelName: String = ""
    private var currentConfig: ModelConfig = ModelConfig()
    private var originalConfig: ModelConfig = ModelConfig()
    private var configChangeListeners = mutableListOf<(ModelConfig) -> Unit>()
    
    fun getCurrentModelName(): String = currentModelName
    
    fun getCurrentConfig(): ModelConfig = currentConfig.copy()
    
    fun getOriginalConfig(): ModelConfig = originalConfig.copy()
    
    fun updateConfig(config: ModelConfig) {
        currentConfig = config
        notifyListeners()
    }
    
    fun updateConfidenceThreshold(value: Float) {
        currentConfig.confidenceThreshold = value.coerceIn(0.01f, 0.99f)
        notifyListeners()
    }
    
    fun updateNmsThreshold(value: Float) {
        currentConfig.nmsThreshold = value.coerceIn(0.01f, 0.99f)
        notifyListeners()
    }
    
    fun updateMaxDetections(value: Int) {
        currentConfig.maxDetections = value.coerceIn(1, 500)
        notifyListeners()
    }
    
    fun updateEnabledClasses(classes: List<Int>?) {
        currentConfig.enabledClasses = classes
        notifyListeners()
    }
    
    fun addConfigChangeListener(listener: (ModelConfig) -> Unit) {
        configChangeListeners.add(listener)
    }
    
    fun removeConfigChangeListener(listener: (ModelConfig) -> Unit) {
        configChangeListeners.remove(listener)
    }
    
    private fun notifyListeners() {
        val configCopy = currentConfig.copy()
        configChangeListeners.forEach { it(configCopy) }
    }
    
    private fun getConfigDir(context: Context): File {
        val configDir = File(context.filesDir, CONFIG_DIR)
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        return configDir
    }
    
    private fun getSafeFileName(modelName: String): String {
        return modelName.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json"
    }
    
    private fun getConfigFile(context: Context, modelName: String): File {
        return File(getConfigDir(context), getSafeFileName(modelName))
    }
    
    fun loadConfig(context: Context, modelName: String): Boolean {
        currentModelName = modelName
        val configFile = getConfigFile(context, modelName)
        
        return try {
            if (configFile.exists()) {
                val json = configFile.readText()
                val config = gson.fromJson(json, ModelConfig::class.java)
                currentConfig = config
                originalConfig = config.copy()
                notifyListeners()
                true
            } else {
                currentConfig = createDefaultConfig(modelName)
                originalConfig = currentConfig.copy()
                notifyListeners()
                false
            }
        } catch (e: Exception) {
            currentConfig = createDefaultConfig(modelName)
            originalConfig = currentConfig.copy()
            notifyListeners()
            false
        }
    }
    
    fun saveConfig(context: Context): Boolean {
        if (currentModelName.isEmpty()) return false
        
        return try {
            val configFile = getConfigFile(context, currentModelName)
            currentConfig.modelName = currentModelName
            val json = gson.toJson(currentConfig)
            configFile.writeText(json)
            originalConfig = currentConfig.copy()
            notifyListeners()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun saveConfig(context: Context, modelName: String): Boolean {
        currentModelName = modelName
        return saveConfig(context)
    }
    
    fun restoreOriginalConfig(): Boolean {
        return try {
            currentConfig = originalConfig.copy()
            notifyListeners()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun createBackup(context: Context, modelName: String): Boolean {
        return try {
            val configFile = getConfigFile(context, modelName)
            val backupFile = File(getConfigDir(context), getSafeFileName(modelName) + BACKUP_SUFFIX)
            if (configFile.exists()) {
                configFile.copyTo(backupFile, overwrite = true)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun restoreFromBackup(context: Context, modelName: String): Boolean {
        return try {
            val backupFile = File(getConfigDir(context), getSafeFileName(modelName) + BACKUP_SUFFIX)
            val configFile = getConfigFile(context, modelName)
            if (backupFile.exists()) {
                backupFile.copyTo(configFile, overwrite = true)
                loadConfig(context, modelName)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun deleteConfig(context: Context, modelName: String): Boolean {
        return try {
            val configFile = getConfigFile(context, modelName)
            if (configFile.exists()) {
                configFile.delete()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun listConfigs(context: Context): List<String> {
        return try {
            val configDir = getConfigDir(context)
            configDir.listFiles()
                ?.filter { it.extension == "json" && !it.name.endsWith(BACKUP_SUFFIX) }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun createDefaultConfig(modelName: String): ModelConfig {
        return ModelConfig(
            modelName = modelName,
            description = "Configuracion para $modelName",
            labels = getDefaultLabels(),
            instructions = getDefaultInstructionsContent()
        )
    }
    
    fun resetToDefaults() {
        currentConfig = createDefaultConfig(currentModelName)
        notifyListeners()
    }
    
    private fun getDefaultInstructionsContent(): String {
        return """
# Instrucciones del Modelo

## Configuracion Basica

Este archivo contiene las instrucciones para configurar y usar el modelo ONNX.

### Parametros de Entrada
- **Resolucion**: Define el tamano de la imagen de entrada (ancho x alto)
- **Canales**: Numero de canales de color (3 para RGB)
- **Normalizacion**: Activa/desactiva la normalizacion de valores

### Umbrales de Deteccion
- **Confianza**: Umbral minimo de confianza para detectar objetos (0.0 - 1.0)
- **NMS**: Umbral de supresion no maxima para eliminar duplicados (0.0 - 1.0)
- **Max Detecciones**: Numero maximo de objetos a detectar

### Dispositivo de Ejecucion
- **CPU**: Ejecucion en el procesador principal
- **GPU**: Aceleracion por hardware grafico (si disponible)
- **NNAPI**: Android Neural Networks API (optimizado para dispositivos moviles)

## Formato de Salida

El modelo puede producir diferentes formatos de salida:
- **YOLOv5/v7**: [batch, num_detections, 5+num_classes]
- **YOLOv8/v11**: [batch, 4+num_classes, num_detections]
- **RT-DETR**: Formato normalizado con boxes y scores separados
- **SSD**: Multiples salidas para boxes y scores

## Consejos de Rendimiento

1. Reduce la resolucion de entrada para mayor velocidad
2. Activa NNAPI en dispositivos compatibles
3. Ajusta el numero de hilos segun los nucleos del CPU
4. Usa filtros de clase para procesar solo objetos relevantes
        """.trimIndent()
    }
    
    private fun getDefaultLabels(): List<String> {
        return emptyList()
    }
    
    fun generateGenericLabels(numClasses: Int): List<String> {
        return (0 until numClasses).map { "Clase $it" }
    }
    
    fun hasUnsavedChanges(): Boolean {
        return currentConfig != originalConfig
    }
    
    fun getConfigSummary(): String {
        return buildString {
            appendLine("=== CONFIGURACION: $currentModelName ===")
            appendLine()
            appendLine("Tipo: ${currentConfig.modelType}")
            appendLine("Resolucion: ${currentConfig.inputWidth}x${currentConfig.inputHeight}")
            appendLine("Confianza: ${(currentConfig.confidenceThreshold * 100).toInt()}%")
            appendLine("NMS: ${(currentConfig.nmsThreshold * 100).toInt()}%")
            appendLine("Max Detecciones: ${currentConfig.maxDetections}")
            appendLine("GPU: ${if (currentConfig.useGpu) "Si" else "No"}")
            appendLine("NNAPI: ${if (currentConfig.useNnapi) "Si" else "No"}")
            appendLine("Hilos: ${currentConfig.numThreads}")
            appendLine("Labels: ${currentConfig.labels.size} clases")
            
            currentConfig.enabledClasses?.let { classes ->
                appendLine()
                appendLine("Clases Habilitadas: ${classes.size}")
                appendLine("  IDs: ${classes.take(20).joinToString(", ")}")
                if (classes.size > 20) {
                    appendLine("  ... y ${classes.size - 20} mas")
                }
            } ?: appendLine("Clases Habilitadas: Todas")
        }
    }
}
