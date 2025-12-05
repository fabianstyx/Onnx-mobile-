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

enum class TensorLayout { NCHW, NHWC, UNKNOWN }

object OnnxProcessor {

    private var ortEnv: OrtEnvironment? = null
    private var currentSession: OrtSession? = null
    private var currentModelUri: String? = null
    private var inputName: String? = null
    private var inputShape: LongArray? = null
    private var inputLayout: TensorLayout = TensorLayout.NCHW
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
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(2)
                }

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

                val nodeInfo = firstInput.value.info
                if (nodeInfo !is TensorInfo) {
                    onLog("Error: Input no es un tensor")
                    closeSessionInternal()
                    return@synchronized false
                }

                val tensorInfo = nodeInfo as TensorInfo
                
                val supportedTypes = listOf(OnnxJavaType.FLOAT, OnnxJavaType.DOUBLE)
                if (tensorInfo.type !in supportedTypes) {
                    onLog("Error: Tipo de input no soportado: ${tensorInfo.type}")
                    onLog("   Solo se soportan modelos con inputs FLOAT o DOUBLE")
                    onLog("   Considera convertir el modelo a float32")
                    closeSessionInternal()
                    return@synchronized false
                }
                
                if (tensorInfo.type == OnnxJavaType.DOUBLE) {
                    onLog("Nota: El modelo usa DOUBLE, se convertirá desde float")
                }

                inputShape = tensorInfo.shape
                inputLayout = detectLayout(inputShape)

                val outputNames = currentSession!!.outputNames.joinToString(", ")

                onLog("Modelo cargado: ${modelUri.lastPathSegment ?: "modelo"}")
                onLog("Input: $inputName, shape: ${inputShape?.contentToString() ?: "desconocido"}")
                onLog("Layout detectado: $inputLayout")
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

    private fun detectLayout(shape: LongArray?): TensorLayout {
        if (shape == null || shape.size != 4) return TensorLayout.UNKNOWN
        
        val dim1 = shape[1]
        val dim3 = shape[3]
        
        return when {
            dim1 in 1..4 && dim3 > 4 -> TensorLayout.NCHW
            dim3 in 1..4 && dim1 > 4 -> TensorLayout.NHWC
            dim1 == 3L -> TensorLayout.NCHW
            dim3 == 3L -> TensorLayout.NHWC
            else -> TensorLayout.NCHW
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

                val (height, width, channels) = getInputDimensions()

                onLog("Procesando ${width}x${height} ($inputLayout)")
                val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)

                val floatArray = when (inputLayout) {
                    TensorLayout.NCHW -> bitmapToNCHW(resized, channels, height, width)
                    TensorLayout.NHWC -> bitmapToNHWC(resized, channels, height, width)
                    TensorLayout.UNKNOWN -> bitmapToNCHW(resized, channels, height, width)
                }

                if (resized != bitmap) {
                    resized.recycle()
                }

                val shape = when (inputLayout) {
                    TensorLayout.NCHW -> longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
                    TensorLayout.NHWC -> longArrayOf(1, height.toLong(), width.toLong(), channels.toLong())
                    TensorLayout.UNKNOWN -> longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
                }

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
                onLog("Error ONNX: ${e.message}")
                null
            } catch (e: Exception) {
                onLog("Error en inferencia: ${e.message}")
                null
            }
        }
    }

    private fun getInputDimensions(): Triple<Int, Int, Int> {
        val shape = inputShape ?: return Triple(224, 224, 3)
        
        return when (inputLayout) {
            TensorLayout.NCHW -> {
                val c = getDim(shape, 1, 3)
                val h = getDim(shape, 2, 224)
                val w = getDim(shape, 3, 224)
                Triple(h, w, c)
            }
            TensorLayout.NHWC -> {
                val h = getDim(shape, 1, 224)
                val w = getDim(shape, 2, 224)
                val c = getDim(shape, 3, 3)
                Triple(h, w, c)
            }
            TensorLayout.UNKNOWN -> Triple(224, 224, 3)
        }
    }

    private fun getDim(shape: LongArray, index: Int, default: Int): Int {
        val dim = shape.getOrNull(index)?.toInt() ?: default
        return if (dim > 0) dim else default
    }

    private fun bitmapToNCHW(bitmap: Bitmap, channels: Int, height: Int, width: Int): FloatArray {
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

    private fun bitmapToNHWC(bitmap: Bitmap, channels: Int, height: Int, width: Int): FloatArray {
        val floatArray = FloatArray(height * width * channels)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = pixels[y * width + x]
                for (c in 0 until channels) {
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
            onLog("Outputs: ${outputNames.joinToString(", ")}")

            val firstOutput = outputs[0]
            val tensor = firstOutput?.value as? OnnxTensor
            if (tensor == null) {
                onLog("Error: Output no es un tensor válido")
                return null
            }

            val info = tensor.info
            val shape = info.shape

            onLog("Shape salida: ${shape.contentToString()}")

            val rawData = try {
                tensor.floatBuffer
            } catch (e: Exception) {
                onLog("Error: Output no es float buffer")
                return null
            }
            
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
                className = "Raw (${rawArray.size} vals)",
                probability = rawArray.maxOrNull() ?: 0f,
                bbox = null,
                rawOutput = rawArray.take(50).toFloatArray(),
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
        val maxProb = probabilities.getOrElse(maxIdx) { 0f }

        onLog("Clasificación: clase $maxIdx de $numClasses (${(maxProb * 100).toInt()}%)")

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
                        (cx - w / 2).coerceIn(0f, imgWidth.toFloat()),
                        (cy - h / 2).coerceIn(0f, imgHeight.toFloat()),
                        (cx + w / 2).coerceIn(0f, imgWidth.toFloat()),
                        (cy + h / 2).coerceIn(0f, imgHeight.toFloat())
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
                        rawOutput = floatArrayOf(),
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
        } catch (e: Exception) { }
        currentSession = null
        currentModelUri = null
        inputName = null
        inputShape = null
        inputLayout = TensorLayout.NCHW
    }

    fun isModelLoaded(): Boolean = synchronized(lock) { currentSession != null }
}
