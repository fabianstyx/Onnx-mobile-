package com.example.onnxsc

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import java.io.File
import java.nio.FloatBuffer

data class InferenceResult(
    val className: String,
    val probability: Float,
    val bbox: RectF?,
    val rawOutput: FloatArray,
    val latencyMs: Long
)

object OnnxProcessor {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var currentSession: OrtSession? = null
    private var currentModelUri: String? = null
    private var inputName: String? = null
    private var inputShape: LongArray? = null

    fun loadModel(context: Context, modelUri: Uri, onLog: (String) -> Unit): Boolean {
        return try {
            val uriString = modelUri.toString()
            
            if (currentModelUri == uriString && currentSession != null) {
                onLog("Modelo ya cargado, reutilizando sesión")
                return true
            }
            
            currentSession?.close()
            currentSession = null
            currentModelUri = null
            
            val modelFile = File(context.filesDir, "model.onnx")
            context.contentResolver.openInputStream(modelUri)?.use { input ->
                modelFile.outputStream().use { out -> input.copyTo(out) }
            }
            
            currentSession = ortEnv.createSession(modelFile.absolutePath)
            currentModelUri = uriString
            
            val inputInfo = currentSession!!.inputInfo
            val firstInput = inputInfo.entries.first()
            inputName = firstInput.key
            
            val tensorInfo = firstInput.value.info as? TensorInfo
            inputShape = tensorInfo?.shape
            
            onLog("Modelo cargado: ${modelFile.name}")
            onLog("Input: $inputName, shape: ${inputShape?.contentToString()}")
            onLog("Outputs: ${currentSession!!.outputNames.joinToString(", ")}")
            true
        } catch (e: Exception) {
            onLog("Error al cargar modelo: ${e.message}")
            false
        }
    }

    fun processImage(
        context: Context,
        modelUri: Uri,
        bitmap: Bitmap,
        onLog: (String) -> Unit
    ): InferenceResult? {
        val startTime = System.currentTimeMillis()
        
        return try {
            if (currentSession == null) {
                if (!loadModel(context, modelUri, onLog)) {
                    return null
                }
            }
            
            val session = currentSession ?: return null
            val name = inputName ?: session.inputNames.iterator().next()
            
            val height = inputShape?.getOrNull(2)?.toInt() ?: 224
            val width = inputShape?.getOrNull(3)?.toInt() ?: 224
            val channels = inputShape?.getOrNull(1)?.toInt() ?: 3
            
            onLog("Redimensionando a ${width}x${height}")
            val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
            
            val floatArray = FloatArray(channels * height * width)
            var idx = 0
            
            for (c in 0 until channels) {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val px = resized.getPixel(x, y)
                        val value = when (c) {
                            0 -> ((px shr 16) and 0xFF) / 255.0f
                            1 -> ((px shr 8) and 0xFF) / 255.0f
                            2 -> (px and 0xFF) / 255.0f
                            else -> 0f
                        }
                        floatArray[idx++] = value
                    }
                }
            }
            
            val shape = longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatArray), shape)
            
            onLog("Ejecutando inferencia...")
            val outputs = session.run(mapOf(name to inputTensor))
            inputTensor.close()
            
            val latencyMs = System.currentTimeMillis() - startTime
            
            val result = parseOutput(outputs, bitmap.width, bitmap.height, onLog)
            result?.copy(latencyMs = latencyMs) ?: InferenceResult(
                className = "Sin resultado",
                probability = 0f,
                bbox = null,
                rawOutput = floatArrayOf(),
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            onLog("Error en inferencia: ${e.message}")
            null
        }
    }

    private fun parseOutput(
        outputs: OrtSession.Result,
        imgWidth: Int,
        imgHeight: Int,
        onLog: (String) -> Unit
    ): InferenceResult? {
        return try {
            val outputNames = outputs.map { it.key }
            onLog("Outputs recibidos: ${outputNames.joinToString(", ")}")
            
            val firstOutput = outputs[0]
            val tensor = firstOutput.value as? OnnxTensor ?: return null
            val info = tensor.info
            val shape = info.shape
            
            onLog("Shape de salida: ${shape.contentToString()}")
            
            val rawData = tensor.floatBuffer
            val rawArray = FloatArray(rawData.remaining())
            rawData.get(rawArray)
            
            if (shape.size == 2 && shape[0] == 1L) {
                val numClasses = shape[1].toInt()
                val maxIdx = rawArray.indices.maxByOrNull { rawArray[it] } ?: 0
                val maxProb = softmax(rawArray)[maxIdx]
                
                onLog("Clasificación: clase $maxIdx de $numClasses con ${(maxProb * 100).toInt()}%")
                
                return InferenceResult(
                    className = "Clase $maxIdx",
                    probability = maxProb,
                    bbox = null,
                    rawOutput = rawArray,
                    latencyMs = 0
                )
            }
            
            if (shape.size >= 3) {
                val detections = parseDetections(rawArray, shape, imgWidth, imgHeight, onLog)
                if (detections != null) {
                    return detections
                }
            }
            
            InferenceResult(
                className = "Output raw",
                probability = rawArray.maxOrNull() ?: 0f,
                bbox = null,
                rawOutput = rawArray,
                latencyMs = 0
            )
        } catch (e: Exception) {
            onLog("Error parseando output: ${e.message}")
            null
        }
    }

    private fun parseDetections(
        data: FloatArray,
        shape: LongArray,
        imgWidth: Int,
        imgHeight: Int,
        onLog: (String) -> Unit
    ): InferenceResult? {
        return try {
            if (shape.size == 3 && shape[2] >= 5) {
                val numDetections = shape[1].toInt()
                val numFields = shape[2].toInt()
                
                var bestIdx = -1
                var bestConf = 0f
                
                for (i in 0 until numDetections) {
                    val baseIdx = i * numFields
                    val conf = if (numFields > 4) data[baseIdx + 4] else 0f
                    if (conf > bestConf && conf > 0.25f) {
                        bestConf = conf
                        bestIdx = i
                    }
                }
                
                if (bestIdx >= 0) {
                    val baseIdx = bestIdx * numFields
                    val cx = data[baseIdx] * imgWidth
                    val cy = data[baseIdx + 1] * imgHeight
                    val w = data[baseIdx + 2] * imgWidth
                    val h = data[baseIdx + 3] * imgHeight
                    
                    val bbox = RectF(
                        cx - w / 2,
                        cy - h / 2,
                        cx + w / 2,
                        cy + h / 2
                    )
                    
                    var classIdx = 0
                    if (numFields > 5) {
                        var maxClassProb = 0f
                        for (c in 5 until numFields) {
                            if (data[baseIdx + c] > maxClassProb) {
                                maxClassProb = data[baseIdx + c]
                                classIdx = c - 5
                            }
                        }
                    }
                    
                    onLog("Detección: clase $classIdx, conf ${(bestConf * 100).toInt()}%")
                    
                    return InferenceResult(
                        className = "Clase $classIdx",
                        probability = bestConf,
                        bbox = bbox,
                        rawOutput = data,
                        latencyMs = 0
                    )
                }
            }
            null
        } catch (e: Exception) {
            onLog("Error en detecciones: ${e.message}")
            null
        }
    }

    private fun softmax(input: FloatArray): FloatArray {
        val max = input.maxOrNull() ?: 0f
        val exps = input.map { kotlin.math.exp((it - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return exps.map { it / sum }.toFloatArray()
    }

    fun closeSession() {
        currentSession?.close()
        currentSession = null
        currentModelUri = null
        inputName = null
        inputShape = null
    }
}
