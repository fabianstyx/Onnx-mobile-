package com.example.onnxsc

import ai.onnxruntime.*
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.math.min

object ModelInspector {

    data class TensorMetadata(
        val name: String,
        val shape: String,
        val dataType: String,
        val isInput: Boolean
    )
    
    data class EmbeddedModelConfig(
        val confidenceThreshold: Float? = null,
        val nmsThreshold: Float? = null,
        val maxDetections: Int? = null,
        val classNames: List<String>? = null,
        val inputSize: Int? = null,
        val description: String = "",
        val author: String = "",
        val version: String = ""
    ) {
        fun hasCustomConfig(): Boolean {
            return confidenceThreshold != null || 
                   nmsThreshold != null || 
                   classNames != null ||
                   maxDetections != null
        }
        
        fun toSummary(): String {
            val parts = mutableListOf<String>()
            confidenceThreshold?.let { parts.add("Conf: ${(it * 100).toInt()}%") }
            nmsThreshold?.let { parts.add("NMS: ${(it * 100).toInt()}%") }
            maxDetections?.let { parts.add("Max: $it") }
            classNames?.let { parts.add("${it.size} clases") }
            inputSize?.let { parts.add("Input: ${it}x${it}") }
            return if (parts.isEmpty()) "Sin config embebida" else parts.joinToString(" | ")
        }
    }

    data class DetailedInspection(
        val modelName: String,
        val sizeKb: Long,
        val inputs: List<TensorMetadata>,
        val outputs: List<TensorMetadata>,
        val operators: List<String>,
        val opsetVersion: Long,
        val producerName: String,
        val producerVersion: String,
        val graphName: String,
        val description: String,
        val hasExternalWeights: Boolean,
        val hasJsOperators: Boolean,
        val hasNodeOps: Boolean
    ) {
        fun toFormattedString(): String {
            val sb = StringBuilder()
            
            sb.appendLine("=== INFORMACION DEL MODELO ===")
            sb.appendLine()
            sb.appendLine("Nombre: $modelName")
            sb.appendLine("Tamano: ${sizeKb} KB")
            if (producerName.isNotEmpty()) {
                sb.appendLine("Productor: $producerName $producerVersion")
            }
            if (graphName.isNotEmpty()) {
                sb.appendLine("Grafo: $graphName")
            }
            if (opsetVersion > 0) {
                sb.appendLine("Opset Version: $opsetVersion")
            }
            if (description.isNotEmpty()) {
                sb.appendLine("Descripcion: $description")
            }
            
            sb.appendLine()
            sb.appendLine("=== ENTRADAS (INPUTS) ===")
            if (inputs.isEmpty()) {
                sb.appendLine("  No se pudieron leer las entradas")
            } else {
                inputs.forEach { input ->
                    sb.appendLine("  - ${input.name}")
                    sb.appendLine("    Shape: ${input.shape}")
                    sb.appendLine("    Tipo: ${input.dataType}")
                }
            }
            
            sb.appendLine()
            sb.appendLine("=== SALIDAS (OUTPUTS) ===")
            if (outputs.isEmpty()) {
                sb.appendLine("  No se pudieron leer las salidas")
            } else {
                outputs.forEach { output ->
                    sb.appendLine("  - ${output.name}")
                    sb.appendLine("    Shape: ${output.shape}")
                    sb.appendLine("    Tipo: ${output.dataType}")
                }
            }
            
            sb.appendLine()
            sb.appendLine("=== OPERADORES DETECTADOS ===")
            if (operators.isEmpty()) {
                sb.appendLine("  No se detectaron operadores")
            } else {
                operators.take(50).forEach { op ->
                    sb.appendLine("  - $op")
                }
                if (operators.size > 50) {
                    sb.appendLine("  ... y ${operators.size - 50} operadores mas")
                }
            }
            
            sb.appendLine()
            sb.appendLine("=== DEPENDENCIAS ===")
            val deps = mutableListOf<String>()
            if (hasExternalWeights) deps.add("Pesos externos")
            if (hasJsOperators) deps.add("Operadores JS")
            if (hasNodeOps) deps.add("Operadores ML")
            if (deps.isEmpty()) {
                sb.appendLine("  Sin dependencias especiales")
            } else {
                deps.forEach { dep ->
                    sb.appendLine("  - $dep")
                }
            }
            
            return sb.toString()
        }
    }

    fun inspectDetailed(context: Context, contentResolver: ContentResolver, uri: Uri): DetailedInspection {
        var ortEnv: OrtEnvironment? = null
        var session: OrtSession? = null
        
        return try {
            val modelName = uri.lastPathSegment?.substringAfterLast("/") ?: "modelo.onnx"
            
            val fileDescriptor = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            }
            val sizeKb = (fileDescriptor?.statSize ?: 0L) / 1024
            fileDescriptor?.close()
            
            val inputStream = contentResolver.openInputStream(uri)
                ?: return createEmptyInspection(modelName, sizeKb)
            
            val allBytes = inputStream.use { it.readBytes() }
            val headerSize = min(16384, allBytes.size)
            val headerBytes = allBytes.copyOfRange(0, headerSize)
            val header = try {
                String(headerBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
            
            val hasExternalWeights = header.contains("external_data") || 
                                     header.contains("data_location") ||
                                     header.contains("external")
            val hasJsOperators = header.contains("com.microsoft.contrib")
            val hasNodeOps = header.contains("ai.onnx.contrib") || header.contains("ai.onnx.ml")
            
            val headerOps = mutableListOf<String>()
            val commonOps = listOf(
                "Conv", "BatchNormalization", "Relu", "ReLU", "LeakyRelu", 
                "MaxPool", "AveragePool", "GlobalAveragePool", "Softmax", 
                "Gemm", "MatMul", "Add", "Mul", "Sub", "Div",
                "Sigmoid", "Tanh", "Concat", "Reshape", "Transpose",
                "Flatten", "Unsqueeze", "Squeeze", "Pad", "Resize",
                "Upsample", "Split", "Slice", "Gather", "Cast",
                "ReduceMean", "ReduceSum", "Clip", "Shape", "Constant",
                "Identity", "Dropout", "Attention", "LayerNormalization"
            )
            commonOps.forEach { op ->
                if (header.contains(op)) headerOps.add(op)
            }
            
            val inputs = mutableListOf<TensorMetadata>()
            val outputs = mutableListOf<TensorMetadata>()
            var opsetVersion = 0L
            var producerName = ""
            var producerVersion = ""
            var graphName = ""
            var description = ""
            val sessionOps = mutableListOf<String>()
            
            try {
                val modelFile = File(context.cacheDir, "inspect_temp.onnx")
                modelFile.writeBytes(allBytes)
                
                ortEnv = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                }
                session = ortEnv.createSession(modelFile.absolutePath, sessionOptions)
                
                session.inputInfo.forEach { (name, info) ->
                    val tensorInfo = info.info as? TensorInfo
                    if (tensorInfo != null) {
                        inputs.add(TensorMetadata(
                            name = name,
                            shape = tensorInfo.shape.contentToString(),
                            dataType = tensorInfo.type.toString(),
                            isInput = true
                        ))
                    } else {
                        inputs.add(TensorMetadata(
                            name = name,
                            shape = "desconocido",
                            dataType = info.info.javaClass.simpleName,
                            isInput = true
                        ))
                    }
                }
                
                session.outputInfo.forEach { (name, info) ->
                    val tensorInfo = info.info as? TensorInfo
                    if (tensorInfo != null) {
                        outputs.add(TensorMetadata(
                            name = name,
                            shape = tensorInfo.shape.contentToString(),
                            dataType = tensorInfo.type.toString(),
                            isInput = false
                        ))
                    } else {
                        outputs.add(TensorMetadata(
                            name = name,
                            shape = "desconocido",
                            dataType = info.info.javaClass.simpleName,
                            isInput = false
                        ))
                    }
                }
                
                try {
                    val metadata = session.metadata
                    producerName = metadata.producerName ?: ""
                    graphName = metadata.graphName ?: ""
                    description = metadata.description ?: ""
                    producerVersion = metadata.version.toString()
                } catch (e: Exception) {
                }
                
                modelFile.delete()
                
            } catch (e: Exception) {
                Logger.warn("No se pudo cargar sesion para inspeccion: ${e.message}")
            }
            
            val allOps = (headerOps + sessionOps).distinct().sorted()
            
            DetailedInspection(
                modelName = modelName,
                sizeKb = sizeKb,
                inputs = inputs,
                outputs = outputs,
                operators = allOps,
                opsetVersion = opsetVersion,
                producerName = producerName,
                producerVersion = producerVersion,
                graphName = graphName,
                description = description,
                hasExternalWeights = hasExternalWeights,
                hasJsOperators = hasJsOperators,
                hasNodeOps = hasNodeOps
            )
            
        } catch (e: Exception) {
            Logger.error("Error en inspeccion detallada: ${e.message}")
            createEmptyInspection(uri.lastPathSegment ?: "modelo", 0)
        } finally {
            try { session?.close() } catch (e: Exception) {}
        }
    }
    
    private fun createEmptyInspection(name: String, sizeKb: Long): DetailedInspection {
        return DetailedInspection(
            modelName = name,
            sizeKb = sizeKb,
            inputs = emptyList(),
            outputs = emptyList(),
            operators = emptyList(),
            opsetVersion = 0,
            producerName = "",
            producerVersion = "",
            graphName = "",
            description = "",
            hasExternalWeights = false,
            hasJsOperators = false,
            hasNodeOps = false
        )
    }
    
    fun extractEmbeddedConfig(context: Context, contentResolver: ContentResolver, uri: Uri): EmbeddedModelConfig {
        var ortEnv: OrtEnvironment? = null
        var session: OrtSession? = null
        
        return try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: return EmbeddedModelConfig()
            
            val allBytes = inputStream.use { it.readBytes() }
            
            val headerSize = min(32768, allBytes.size)
            val header = try {
                String(allBytes.copyOfRange(0, headerSize), Charsets.UTF_8)
            } catch (e: Exception) { "" }
            
            var confThreshold: Float? = null
            var nmsThreshold: Float? = null
            var maxDet: Int? = null
            var classNames: List<String>? = null
            var inputSize: Int? = null
            var desc = ""
            var author = ""
            var version = ""
            
            val confPatterns = listOf(
                Regex("""conf[_-]?thresh[old]*["\s:=]+([0-9.]+)""", RegexOption.IGNORE_CASE),
                Regex("""confidence[_-]?threshold["\s:=]+([0-9.]+)""", RegexOption.IGNORE_CASE),
                Regex("""score[_-]?thresh[old]*["\s:=]+([0-9.]+)""", RegexOption.IGNORE_CASE),
                Regex("""threshold["\s:=]+([0-9.]+)""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in confPatterns) {
                val match = pattern.find(header)
                if (match != null) {
                    val value = match.groupValues[1].toFloatOrNull()
                    if (value != null && value in 0.0f..1.0f) {
                        confThreshold = value
                        break
                    }
                }
            }
            
            val nmsPatterns = listOf(
                Regex("""nms[_-]?thresh[old]*["\s:=]+([0-9.]+)""", RegexOption.IGNORE_CASE),
                Regex("""iou[_-]?thresh[old]*["\s:=]+([0-9.]+)""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in nmsPatterns) {
                val match = pattern.find(header)
                if (match != null) {
                    val value = match.groupValues[1].toFloatOrNull()
                    if (value != null && value in 0.0f..1.0f) {
                        nmsThreshold = value
                        break
                    }
                }
            }
            
            val maxDetPattern = Regex("""max[_-]?det[ections]*["\s:=]+(\d+)""", RegexOption.IGNORE_CASE)
            maxDetPattern.find(header)?.let {
                val value = it.groupValues[1].toIntOrNull()
                if (value != null && value in 1..1000) {
                    maxDet = value
                }
            }
            
            val inputSizePattern = Regex("""imgsz["\s:=]+(\d+)""", RegexOption.IGNORE_CASE)
            inputSizePattern.find(header)?.let {
                val value = it.groupValues[1].toIntOrNull()
                if (value != null && value in 64..2048) {
                    inputSize = value
                }
            }
            
            val namesPattern = Regex("""names["\s:]*\{([^}]+)\}""", RegexOption.IGNORE_CASE)
            namesPattern.find(header)?.let { match ->
                val namesContent = match.groupValues[1]
                val nameEntries = mutableListOf<Pair<Int, String>>()
                
                val entryPattern = Regex("""(\d+)["\s:]+['"]?([^'",\}]+)['"]?""")
                entryPattern.findAll(namesContent).forEach { entry ->
                    val idx = entry.groupValues[1].toIntOrNull()
                    val name = entry.groupValues[2].trim()
                    if (idx != null && name.isNotEmpty()) {
                        nameEntries.add(idx to name)
                    }
                }
                
                if (nameEntries.isNotEmpty()) {
                    val sortedNames = nameEntries.sortedBy { it.first }
                    val maxIdx = sortedNames.maxOfOrNull { it.first } ?: 0
                    val namesList = MutableList(maxIdx + 1) { "Clase $it" }
                    sortedNames.forEach { (idx, name) -> 
                        if (idx < namesList.size) namesList[idx] = name 
                    }
                    classNames = namesList
                }
            }
            
            try {
                val modelFile = File(context.cacheDir, "config_temp.onnx")
                modelFile.writeBytes(allBytes)
                
                ortEnv = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                }
                session = ortEnv.createSession(modelFile.absolutePath, sessionOptions)
                
                val metadata = session.metadata
                desc = metadata.description ?: ""
                
                val customMeta = metadata.customMetadata
                customMeta?.forEach { (key, value) ->
                    when (key.lowercase()) {
                        "conf_threshold", "confidence_threshold", "conf" -> {
                            value.toFloatOrNull()?.let { 
                                if (it in 0.0f..1.0f) confThreshold = it 
                            }
                        }
                        "nms_threshold", "iou_threshold", "nms", "iou" -> {
                            value.toFloatOrNull()?.let { 
                                if (it in 0.0f..1.0f) nmsThreshold = it 
                            }
                        }
                        "max_detections", "max_det" -> {
                            value.toIntOrNull()?.let {
                                if (it in 1..1000) maxDet = it
                            }
                        }
                        "author", "created_by" -> author = value
                        "version" -> version = value
                        "imgsz", "input_size" -> {
                            value.toIntOrNull()?.let {
                                if (it in 64..2048) inputSize = it
                            }
                        }
                    }
                }
                
                modelFile.delete()
                
            } catch (e: Exception) {
                Logger.warn("No se pudo leer metadata ONNX: ${e.message}")
            }
            
            EmbeddedModelConfig(
                confidenceThreshold = confThreshold,
                nmsThreshold = nmsThreshold,
                maxDetections = maxDet,
                classNames = classNames,
                inputSize = inputSize,
                description = desc,
                author = author,
                version = version
            )
            
        } catch (e: Exception) {
            Logger.error("Error extrayendo config embebida: ${e.message}")
            EmbeddedModelConfig()
        } finally {
            try { session?.close() } catch (e: Exception) {}
        }
    }

    fun inspect(contentResolver: ContentResolver, uri: Uri): Inspection {
        return try {
            val inputStream = contentResolver.openInputStream(uri) 
                ?: return Inspection(
                    hasJsOperators = false,
                    hasNodeOps = false,
                    hasExternalWeights = false,
                    sizeKb = 0,
                    modelName = "desconocido",
                    detectedOps = emptyList()
                )

            val allBytes = inputStream.use { it.readBytes() }
            val headerSize = min(8192, allBytes.size)
            val bytes = allBytes.copyOfRange(0, headerSize)

            val header = try {
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }

            val fileDescriptor = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            }
            val sizeKb = (fileDescriptor?.statSize ?: allBytes.size.toLong()) / 1024
            fileDescriptor?.close()

            val modelName = uri.lastPathSegment?.substringAfterLast("/") ?: "modelo"

            val detectedOps = mutableListOf<String>()
            if (header.contains("Conv")) detectedOps.add("Conv")
            if (header.contains("BatchNorm")) detectedOps.add("BatchNorm")
            if (header.contains("Relu") || header.contains("ReLU")) detectedOps.add("ReLU")
            if (header.contains("MaxPool")) detectedOps.add("MaxPool")
            if (header.contains("Softmax")) detectedOps.add("Softmax")
            if (header.contains("Gemm") || header.contains("MatMul")) detectedOps.add("MatMul")

            Inspection(
                hasJsOperators = header.contains("com.microsoft.contrib"),
                hasNodeOps = header.contains("ai.onnx.contrib") || header.contains("ai.onnx.ml"),
                hasExternalWeights = header.contains("external_data") || 
                                     header.contains("data_location") ||
                                     header.contains("external"),
                sizeKb = sizeKb,
                modelName = modelName,
                detectedOps = detectedOps
            )
        } catch (e: Exception) {
            Logger.error("Error al inspeccionar modelo: ${e.message}")
            Inspection(
                hasJsOperators = false,
                hasNodeOps = false,
                hasExternalWeights = false,
                sizeKb = 0,
                modelName = "error",
                detectedOps = emptyList()
            )
        }
    }

    data class Inspection(
        val hasJsOperators: Boolean,
        val hasNodeOps: Boolean,
        val hasExternalWeights: Boolean,
        val sizeKb: Long,
        val modelName: String = "",
        val detectedOps: List<String> = emptyList()
    ) {
        fun getSummary(): String {
            val deps = mutableListOf<String>()
            if (hasExternalWeights) deps.add("pesos externos")
            if (hasJsOperators) deps.add("ops JS")
            if (hasNodeOps) deps.add("ops ML")
            
            return if (deps.isEmpty()) {
                "Sin dependencias especiales"
            } else {
                "Requiere: ${deps.joinToString(", ")}"
            }
        }
    }
}
