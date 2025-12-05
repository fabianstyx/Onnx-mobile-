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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as InferenceResult
        return className == other.className &&
                probability == other.probability &&
                bbox == other.bbox &&
                rawOutput.contentEquals(other.rawOutput) &&
                latencyMs == other.latencyMs
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + probability.hashCode()
        result = 31 * result + (bbox?.hashCode() ?: 0)
        result = 31 * result + rawOutput.contentHashCode()
        result = 31 * result + latencyMs.hashCode()
        return result
    }
}

object OnnxProcessor {

    private var ortEnv: OrtEnvironment? = null
    private var currentSession: OrtSession? = null
    private var currentModelUri: String? = null
    private var inputName: String? = null
    private var inputShape: LongArray? = null
    private val lock = Any()

    private fun getEnvironment(): OrtEnvironment {
        if (ortEnv == null) {
            ortEnv = OrtEnvironment.getEnvironment()
        }
        return ortEnv!!
    }

    fun loadModel(context: Context, modelUri: Uri, onLog: (String) -> Unit): Boolean {
        return synchronized(lock) {
            try {
                val uriString = modelUri.toString()

                if (currentModelUri == uriString && currentSession != null) {
                    onLog("Modelo ya cargado, reutilizando sesión")
                    return@synchronized true
                }

                closeSessionInternal()

                val modelFile = File(context.filesDir, "model.onnx")
                context.contentResolver.openInputStream(modelUri)?.use { input ->
                    modelFile.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    onLog("Error: No se pudo abrir el archivo del modelo")
                    return@synchronized false
                }

                val env = getEnvironment()
                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)

                currentSession = env.createSession(modelFile.absolutePath, sessionOptions)
                currentModelUri = uriString

                val inputInfo = currentSession!!.inputInfo
                if (inputInfo.isEmpty()) {
                    onLog("Error: El modelo no tiene entradas definidas")
                    closeSessionInternal()
                    return@synchronized false
                }

                val firstInput = inputInfo.entries.first()
                inputName = firstInput.key

                val tensorInfo = firstInput.value.info as? TensorInfo
                inputShape = tensorInfo?.shape

                val outputNames = currentSession!!.outputNames.joinToString(", ")

                onLog("Modelo cargado: ${modelUri.lastPathSegment ?: "modelo"}")
                onLog("Input: $inputName, shape: ${inputShape?.contentToString() ?: "desconocido"}")
                onLog("Outputs: $outputNames")
                true
            } catch (e: OrtException) {
                onLog("Error ONNX Runtime: ${e.message}")
                closeSessionInternal()
                false
            } catch (e: Exception) {
                onLog("Error al cargar modelo: ${e.message}")
                closeSessionInternal()
                false
            }
        }
    }

    fun processImage(
        context: Context,
        modelUri: Uri,
        bitmap: Bitmap,
        onLog: (String) -> Unit
    ): InferenceResult? {
        val startTime = System.currentTimeMillis()

        return synchronized(lock) {
            try {
                if (currentSession == null) {
                    if (!loadModel(context, modelUri, onLog)) {
                        return@synchronized null
                    }
                }

                val session = currentSession ?: return@synchronized null
                val name = inputName ?: session.inputNames.iterator().next()

                val height = getInputDimension(2, 224)
                val width = getInputDimension(3, 224)
                val channels = getInputDimension(1, 3)

                onLog("Redimensionando a ${width}x${height}")
                val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)

                val floatArray = bitmapToFloatArray(resized, channels, height, width)
                if (resized != bitmap) {
                    resized.recycle()
                }

                val shape = longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
                val env = getEnvironment()
                val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), shape)

                onLog("Ejecutando inferencia...")
                val outputs = session.run(mapOf(name to inputTensor))
                inputTensor.close()

                val latencyMs = System.currentTimeMillis() - startTime

                val result = parseOutput(outputs, bitmap.width, bitmap.height, onLog)
                outputs.close()

                result?.copy(latencyMs = latencyMs) ?: InferenceResult(
                    className = "Sin resultado",
                    probability = 0f,
                    bbox = null,
                    rawOutput = floatArrayOf(),
                    latencyMs = latencyMs
                )
            } catch (e: OrtException) {
                onLog("Error ONNX en inferencia: ${e.message}")
                null
            } catch (e: Exception) {
                onLog("Error en inferencia: ${e.message}")
                null
            }
        }
    }

    private fun getInputDimension(index: Int, default: Int): Int {
        val dim = inputShape?.getOrNull(index)?.toInt() ?: default
        return if (dim > 0) dim else default
    }

    private fun bitmapToFloatArray(bitmap: Bitmap, channels: Int, height: Int, width: Int): FloatArray {
        val floatArray = FloatArray(channels * height * width)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var idx = 0
        for (c in 0 until channels) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = pixels[y * width + x]
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
        return floatArray
    }

    private fun parseOutput(
        outputs: OrtSession.Result,
        imgWidth: Int,
        imgHeight: Int,
        onLog: (String) -> Unit
    ): InferenceResult? {
        return try {
            if (outputs.size() == 0) {
                onLog("Error: No hay outputs en el resultado")
                return null
            }

            val outputNames = outputs.mapNotNull { it.key }
            onLog("Outputs recibidos: ${outputNames.joinToString(", ")}")

            val firstOutput = outputs[0]
            val tensor = firstOutput?.value as? OnnxTensor
            if (tensor == null) {
                onLog("Error: Output no es un tensor válido")
                return null
            }

            val info = tensor.info
            val shape = info.shape

            onLog("Shape de salida: ${shape.contentToString()}")

            val rawData = tensor.floatBuffer
            val rawArray = FloatArray(rawData.remaining())
            rawData.get(rawArray)

            if (shape.size == 2 && shape[0] == 1L) {
                return parseClassification(rawArray, shape, onLog)
            }

            if (shape.size >= 3) {
                val detections = parseDetections(rawArray, shape, imgWidth, imgHeight, onLog)
                if (detections != null) {
                    return detections
                }
            }

            if (shape.size == 4) {
                return parseSegmentation(rawArray, shape, onLog)
            }

            InferenceResult(
                className = "Output raw (${rawArray.size} valores)",
                probability = rawArray.maxOrNull() ?: 0f,
                bbox = null,
                rawOutput = rawArray.take(100).toFloatArray(),
                latencyMs = 0
            )
        } catch (e: Exception) {
            onLog("Error parseando output: ${e.message}")
            null
        }
    }

    private fun parseClassification(
        rawArray: FloatArray,
        shape: LongArray,
        onLog: (String) -> Unit
    ): InferenceResult {
        val numClasses = shape[1].toInt()
        val maxIdx = rawArray.indices.maxByOrNull { rawArray[it] } ?: 0
        val probabilities = softmax(rawArray)
        val maxProb = probabilities[maxIdx]

        onLog("Clasificación: clase $maxIdx de $numClasses con ${(maxProb * 100).toInt()}%")

        return InferenceResult(
            className = "Clase $maxIdx",
            probability = maxProb,
            bbox = null,
            rawOutput = probabilities,
            latencyMs = 0
        )
    }

    private fun parseSegmentation(
        rawArray: FloatArray,
        shape: LongArray,
        onLog: (String) -> Unit
    ): InferenceResult {
        val numClasses = shape[1].toInt()
        onLog("Segmentación: $numClasses clases")

        return InferenceResult(
            className = "Segmentación ($numClasses clases)",
            probability = 1f,
            bbox = null,
            rawOutput = floatArrayOf(),
            latencyMs = 0
        )
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
                    if (baseIdx + 4 >= data.size) break

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
                        (cx - w / 2).coerceAtLeast(0f),
                        (cy - h / 2).coerceAtLeast(0f),
                        (cx + w / 2).coerceAtMost(imgWidth.toFloat()),
                        (cy + h / 2).coerceAtMost(imgHeight.toFloat())
                    )

                    var classIdx = 0
                    if (numFields > 5) {
                        var maxClassProb = 0f
                        for (c in 5 until numFields) {
                            if (baseIdx + c < data.size && data[baseIdx + c] > maxClassProb) {
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
                        rawOutput = data.take(100).toFloatArray(),
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
        if (input.isEmpty()) return floatArrayOf()
        val max = input.maxOrNull() ?: 0f
        val exps = input.map { kotlin.math.exp((it - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return if (sum > 0) exps.map { it / sum }.toFloatArray() else input
    }

    fun closeSession() {
        synchronized(lock) {
            closeSessionInternal()
        }
    }

    private fun closeSessionInternal() {
        try {
            currentSession?.close()
        } catch (e: Exception) {
            // Ignorar errores al cerrar
        }
        currentSession = null
        currentModelUri = null
        inputName = null
        inputShape = null
    }

    fun isModelLoaded(): Boolean = synchronized(lock) { currentSession != null }
}
