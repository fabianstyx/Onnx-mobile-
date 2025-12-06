package com.example.onnxsc.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File

data class ModelConfig(
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
    
    private const val CONFIG_DIR = "model"
    private const val CONFIG_FILE = "config.json"
    private const val INSTRUCTIONS_FILE = "instructions.md"
    private const val BACKUP_SUFFIX = ".backup"
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    private var currentConfig: ModelConfig = ModelConfig()
    private var originalConfig: ModelConfig = ModelConfig()
    private var configChangeListeners = mutableListOf<(ModelConfig) -> Unit>()
    
    fun getCurrentConfig(): ModelConfig = currentConfig.copy()
    
    fun getOriginalConfig(): ModelConfig = originalConfig.copy()
    
    fun updateConfig(config: ModelConfig) {
        currentConfig = config
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
    
    fun getConfigDir(context: Context): File {
        val configDir = File(context.filesDir, CONFIG_DIR)
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        return configDir
    }
    
    fun getConfigFile(context: Context): File {
        return File(getConfigDir(context), CONFIG_FILE)
    }
    
    fun getInstructionsFile(context: Context): File {
        return File(getConfigDir(context), INSTRUCTIONS_FILE)
    }
    
    fun loadConfig(context: Context): ModelConfig {
        val configFile = getConfigFile(context)
        
        return try {
            if (configFile.exists()) {
                val json = configFile.readText()
                val config = gson.fromJson(json, ModelConfig::class.java)
                currentConfig = config
                originalConfig = config.copy()
                config
            } else {
                createDefaultConfig(context)
            }
        } catch (e: Exception) {
            createDefaultConfig(context)
        }
    }
    
    fun saveConfig(context: Context): Boolean {
        return try {
            val configFile = getConfigFile(context)
            val json = gson.toJson(currentConfig)
            configFile.writeText(json)
            originalConfig = currentConfig.copy()
            notifyListeners()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun restoreOriginalConfig(context: Context): Boolean {
        return try {
            currentConfig = originalConfig.copy()
            saveConfig(context)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun createBackup(context: Context): Boolean {
        return try {
            val configFile = getConfigFile(context)
            val backupFile = File(getConfigDir(context), CONFIG_FILE + BACKUP_SUFFIX)
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
    
    fun restoreFromBackup(context: Context): Boolean {
        return try {
            val backupFile = File(getConfigDir(context), CONFIG_FILE + BACKUP_SUFFIX)
            val configFile = getConfigFile(context)
            if (backupFile.exists()) {
                backupFile.copyTo(configFile, overwrite = true)
                loadConfig(context)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun loadInstructions(context: Context): String {
        val instructionsFile = getInstructionsFile(context)
        
        return try {
            if (instructionsFile.exists()) {
                instructionsFile.readText()
            } else {
                createDefaultInstructions(context)
            }
        } catch (e: Exception) {
            getDefaultInstructionsContent()
        }
    }
    
    fun saveInstructions(context: Context, content: String): Boolean {
        return try {
            val instructionsFile = getInstructionsFile(context)
            instructionsFile.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun createDefaultConfig(context: Context): ModelConfig {
        val config = ModelConfig(
            description = "Configuracion por defecto del modelo ONNX",
            labels = getDefaultLabels()
        )
        
        currentConfig = config
        originalConfig = config.copy()
        
        try {
            val configFile = getConfigFile(context)
            val json = gson.toJson(config)
            configFile.writeText(json)
        } catch (e: Exception) {
        }
        
        return config
    }
    
    private fun createDefaultInstructions(context: Context): String {
        val content = getDefaultInstructionsContent()
        
        try {
            val instructionsFile = getInstructionsFile(context)
            instructionsFile.writeText(content)
        } catch (e: Exception) {
        }
        
        return content
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
        return listOf(
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
    }
    
    fun hasUnsavedChanges(): Boolean {
        return currentConfig != originalConfig
    }
    
    fun getConfigSummary(): String {
        return buildString {
            appendLine("=== CONFIGURACION DEL MODELO ===")
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
        }
    }
}
