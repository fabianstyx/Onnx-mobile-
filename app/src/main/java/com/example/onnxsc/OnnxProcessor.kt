package com.example.onnxsc

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

data class InferenceResult(
    val className: String,
    val probability: Float,
    val bbox: RectF?,
    val rawOutput: FloatArray,
    val latencyMs: Long,
    val allDetections: List<Detection> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as InferenceResult
        return className == other.className &&
                probability == other.probability &&
                bbox == other.bbox &&
                rawOutput.contentEquals(other.rawOutput) &&
                latencyMs == other.latencyMs &&
                allDetections == other.allDetections
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + probability.hashCode()
        result = 31 * result + (bbox?.hashCode() ?: 0)
        result = 31 * result + rawOutput.contentHashCode()
        result = 31 * result + latencyMs.hashCode()
        result = 31 * result + allDetections.hashCode()
        return result
    }
}

enum class TensorLayout { NCHW, NHWC, UNKNOWN }

data class ModelInfo(
    val inputShape: LongArray?,
    val inputLayout: TensorLayout,
    val outputFormat: OutputFormat,
    val outputShape: LongArray?,
    val numClasses: Int,
    val isLoaded: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelInfo
        return inputShape?.contentEquals(other.inputShape) ?: (other.inputShape == null) &&
                inputLayout == other.inputLayout &&
                outputFormat == other.outputFormat &&
                outputShape?.contentEquals(other.outputShape) ?: (other.outputShape == null) &&
                numClasses == other.numClasses &&
                isLoaded == other.isLoaded
    }

    override fun hashCode(): Int {
        var result = inputShape?.contentHashCode() ?: 0
        result = 31 * result + inputLayout.hashCode()
        result = 31 * result + outputFormat.hashCode()
        result = 31 * result + (outputShape?.contentHashCode() ?: 0)
        result = 31 * result + numClasses
        result = 31 * result + isLoaded.hashCode()
        return result
    }
}

object OnnxProcessor {

    private var ortEnv: OrtEnvironment? = null
    private var currentSession: OrtSession? = null
    private var currentModelUri: String? = null
    private var inputName: String? = null
    private var inputShape: LongArray? = null
    private var inputLayout: TensorLayout = TensorLayout.NCHW
    private var inputType: OnnxJavaType = OnnxJavaType.FLOAT
    private var cachedOutputShape: LongArray? = null
    private var cachedOutputFormat: OutputFormat = OutputFormat.UNKNOWN
    private var cachedNumClasses: Int = 0
    private val lock = Any()

    private fun flattenArray(arr: Any): FloatArray {
        val result = mutableListOf<Float>()
        flattenArrayRecursive(arr, result)
        return result.toFloatArray()
    }

    private fun flattenArrayRecursive(arr: Any, result: MutableList<Float>) {
        when (arr) {
            is FloatArray -> result.addAll(arr.toList())
            is DoubleArray -> arr.forEach { result.add(it.toFloat()) }
            is IntArray -> arr.forEach { result.add(it.toFloat()) }
            is LongArray -> arr.forEach { result.add(it.toFloat()) }
            is ByteArray -> arr.forEach { result.add(it.toFloat()) }
            is ShortArray -> arr.forEach { result.add(it.toFloat()) }
            is Float -> result.add(arr)
            is Double -> result.add(arr.toFloat())
            is Number -> result.add(arr.toFloat())
            is Array<*> -> {
                for (item in arr) {
                    if (item != null) flattenArrayRecursive(item, result)
                }
            }
        }
    }

    private fun inferArrayShape(arr: Any): LongArray {
        return inferShapeRecursive(arr)
    }

    private fun inferShapeRecursive(arr: Any): LongArray {
        return when (arr) {
            is FloatArray -> longArrayOf(arr.size.toLong())
            is DoubleArray -> longArrayOf(arr.size.toLong())
            is IntArray -> longArrayOf(arr.size.toLong())
            is LongArray -> longArrayOf(arr.size.toLong())
            is ByteArray -> longArrayOf(arr.size.toLong())
            is ShortArray -> longArrayOf(arr.size.toLong())
            is Array<*> -> {
                val first = arr.firstOrNull()
                if (first != null) {
                    val innerShape = inferShapeRecursive(first)
                    longArrayOf(arr.size.toLong()) + innerShape
                } else {
                    longArrayOf(arr.size.toLong())
                }
            }
            else -> longArrayOf()
        }
    }

    private fun isArrayType(obj: Any?): Boolean {
        return obj is FloatArray || obj is DoubleArray || obj is IntArray ||
               obj is LongArray || obj is ByteArray || obj is ShortArray ||
               obj is Array<*>
    }

    private fun getEnvironment(): OrtEnvironment {
        if (ortEnv == null) {
            ortEnv = OrtEnvironment.getEnvironment()
        }
        return ortEnv!!
    }

    private fun createTensorForType(
        env: OrtEnvironment,
        floatData: FloatArray,
        shape: LongArray,
        type: OnnxJavaType
    ): OnnxTensor {
        val typeStr = type.toString().uppercase()
        val isFloat16 = typeStr.contains("FLOAT16") || typeStr.contains("FP16")
        
        return when {
            isFloat16 -> {
                val fp16Data = ShortArray(floatData.size)
                for (i in floatData.indices) {
                    fp16Data[i] = floatToFloat16(floatData[i])
                }
                val buffer = ByteBuffer.allocateDirect(fp16Data.size * 2)
                    .order(ByteOrder.nativeOrder())
                buffer.asShortBuffer().put(fp16Data)
                buffer.rewind()
                OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.FLOAT16)
            }
            type == OnnxJavaType.DOUBLE -> {
                val doubleData = DoubleArray(floatData.size) { floatData[it].toDouble() }
                val buffer = java.nio.DoubleBuffer.wrap(doubleData)
                OnnxTensor.createTensor(env, buffer, shape)
            }
            type == OnnxJavaType.INT8 -> {
                val int8Data = ByteArray(floatData.size) { (floatData[it] * 255).toInt().coerceIn(0, 255).toByte() }
                val buffer = ByteBuffer.wrap(int8Data)
                OnnxTensor.createTensor(env, buffer, shape)
            }
            type == OnnxJavaType.UINT8 -> {
                val uint8Data = ByteArray(floatData.size) { (floatData[it] * 255).toInt().coerceIn(0, 255).toByte() }
                val buffer = ByteBuffer.wrap(uint8Data)
                OnnxTensor.createTensor(env, buffer, shape)
            }
            type == OnnxJavaType.INT32 -> {
                val int32Data = IntArray(floatData.size) { floatData[it].toInt() }
                val buffer = java.nio.IntBuffer.wrap(int32Data)
                OnnxTensor.createTensor(env, buffer, shape)
            }
            type == OnnxJavaType.INT64 -> {
                val int64Data = LongArray(floatData.size) { floatData[it].toLong() }
                val buffer = java.nio.LongBuffer.wrap(int64Data)
                OnnxTensor.createTensor(env, buffer, shape)
            }
            type == OnnxJavaType.INT16 -> {
                val int16Data = ShortArray(floatData.size) { floatData[it].toInt().toShort() }
                val buffer = ByteBuffer.allocateDirect(int16Data.size * 2)
                    .order(ByteOrder.nativeOrder())
                buffer.asShortBuffer().put(int16Data)
                buffer.rewind()
                OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.INT16)
            }
            else -> {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData), shape)
            }
        }
    }
    
    private fun floatToFloat16(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 31) and 0x1
        val exp = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7FFFFF
        
        return when {
            exp == 0 -> (sign shl 15).toShort()
            exp == 0xFF -> {
                if (mantissa != 0) {
                    ((sign shl 15) or 0x7C00 or (mantissa ushr 13)).toShort()
                } else {
                    ((sign shl 15) or 0x7C00).toShort()
                }
            }
            else -> {
                val newExp = exp - 127 + 15
                when {
                    newExp >= 31 -> ((sign shl 15) or 0x7C00).toShort()
                    newExp <= 0 -> {
                        if (newExp < -10) {
                            (sign shl 15).toShort()
                        } else {
                            val mant = (mantissa or 0x800000) ushr (1 - newExp)
                            ((sign shl 15) or (mant ushr 13)).toShort()
                        }
                    }
                    else -> ((sign shl 15) or (newExp shl 10) or (mantissa ushr 13)).toShort()
                }
            }
        }
    }
    
    private fun float16ToFloat(value: Short): Float {
        val bits = value.toInt() and 0xFFFF
        val sign = (bits ushr 15) and 0x1
        val exp = (bits ushr 10) and 0x1F
        val mantissa = bits and 0x3FF
        
        return when {
            exp == 0 -> {
                if (mantissa == 0) {
                    if (sign == 1) -0.0f else 0.0f
                } else {
                    val f = mantissa.toFloat() / 1024.0f
                    if (sign == 1) -f * (1.0f / 16384.0f) else f * (1.0f / 16384.0f)
                }
            }
            exp == 31 -> {
                if (mantissa != 0) Float.NaN
                else if (sign == 1) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
            }
            else -> {
                val newExp = exp - 15 + 127
                val floatBits = (sign shl 31) or (newExp shl 23) or (mantissa shl 13)
                java.lang.Float.intBitsToFloat(floatBits)
            }
        }
    }
    
    private fun extractTensorData(tensor: OnnxTensor): FloatArray? {
        val info = tensor.info
        val typeStr = info.type.toString().uppercase()
        val isFloat16 = typeStr.contains("FLOAT16") || typeStr.contains("FP16")
        
        return try {
            when {
                isFloat16 -> {
                    val buffer = tensor.byteBuffer
                    buffer.order(ByteOrder.nativeOrder())
                    val shortBuffer = buffer.asShortBuffer()
                    val fp16Data = ShortArray(shortBuffer.remaining())
                    shortBuffer.get(fp16Data)
                    FloatArray(fp16Data.size) { float16ToFloat(fp16Data[it]) }
                }
                info.type == OnnxJavaType.FLOAT -> {
                    val buf = tensor.floatBuffer
                    val arr = FloatArray(buf.remaining())
                    buf.get(arr)
                    arr
                }
                info.type == OnnxJavaType.DOUBLE -> {
                    val buf = tensor.doubleBuffer
                    val arr = FloatArray(buf.remaining())
                    for (i in 0 until buf.remaining()) {
                        arr[i] = buf.get(i).toFloat()
                    }
                    arr
                }
                info.type == OnnxJavaType.INT32 -> {
                    val buf = tensor.intBuffer
                    val arr = FloatArray(buf.remaining())
                    for (i in 0 until buf.remaining()) {
                        arr[i] = buf.get(i).toFloat()
                    }
                    arr
                }
                info.type == OnnxJavaType.INT64 -> {
                    val buf = tensor.longBuffer
                    val arr = FloatArray(buf.remaining())
                    for (i in 0 until buf.remaining()) {
                        arr[i] = buf.get(i).toFloat()
                    }
                    arr
                }
                info.type == OnnxJavaType.INT8 || info.type == OnnxJavaType.UINT8 -> {
                    val buf = tensor.byteBuffer
                    val arr = FloatArray(buf.remaining())
                    for (i in 0 until buf.remaining()) {
                        arr[i] = (buf.get(i).toInt() and 0xFF).toFloat() / 255.0f
                    }
                    arr
                }
                info.type == OnnxJavaType.INT16 -> {
                    val buf = tensor.shortBuffer
                    val arr = FloatArray(buf.remaining())
                    for (i in 0 until buf.remaining()) {
                        arr[i] = buf.get(i).toFloat()
                    }
                    arr
                }
                else -> {
                    tensor.floatBuffer.let { buf ->
                        val arr = FloatArray(buf.remaining())
                        buf.get(arr)
                        arr
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
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
                
                var useNnapi = true
                var session: OrtSession? = null
                
                while (session == null) {
                    val sessionOptions = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        setIntraOpNumThreads(4)
                        setInterOpNumThreads(2)
                        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)
                        
                        if (useNnapi) {
                            try {
                                addNnapi()
                                onLog("Intentando NNAPI - aceleración con NPU...")
                            } catch (e: Exception) {
                                onLog("NNAPI no disponible en este dispositivo")
                                useNnapi = false
                            }
                        }
                    }
                    
                    try {
                        session = env.createSession(modelFile.absolutePath, sessionOptions)
                        if (useNnapi) {
                            onLog("NNAPI habilitado - usando NPU del Snapdragon")
                        } else {
                            onLog("Usando CPU optimizado (multi-thread)")
                        }
                    } catch (e: OrtException) {
                        if (useNnapi && (e.message?.contains("NNAPI") == true || 
                                         e.message?.contains("nnapi") == true ||
                                         e.message?.contains("AddNnapi") == true ||
                                         e.message?.contains("op_builder") == true)) {
                            onLog("NNAPI incompatible con este modelo, cambiando a CPU...")
                            useNnapi = false
                            sessionOptions.close()
                        } else {
                            throw e
                        }
                    }
                }
                
                currentSession = session
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
                
                val supportedTypes = listOf(
                    OnnxJavaType.FLOAT, 
                    OnnxJavaType.DOUBLE,
                    OnnxJavaType.INT8,
                    OnnxJavaType.INT16,
                    OnnxJavaType.INT32,
                    OnnxJavaType.INT64,
                    OnnxJavaType.UINT8
                )
                
                val tensorType = tensorInfo.type
                val isFloat16 = tensorType.toString().contains("FLOAT16") || 
                                tensorType.toString().contains("float16") ||
                                tensorType.toString().contains("FP16")
                
                if (!isFloat16 && tensorType !in supportedTypes) {
                    onLog("Error: Tipo de input no soportado: ${tensorType}")
                    onLog("   Tipos soportados: FLOAT, FLOAT16, DOUBLE, INT8, INT16, INT32, INT64, UINT8")
                    closeSessionInternal()
                    return@synchronized false
                }
                
                inputType = tensorType
                
                when {
                    isFloat16 -> onLog("Nota: El modelo usa FLOAT16 (FP16), conversion automatica")
                    tensorType == OnnxJavaType.DOUBLE -> onLog("Nota: El modelo usa DOUBLE, conversion automatica")
                    tensorType == OnnxJavaType.INT8 -> onLog("Nota: El modelo usa INT8, conversion automatica")
                    tensorType == OnnxJavaType.UINT8 -> onLog("Nota: El modelo usa UINT8, conversion automatica")
                    tensorType == OnnxJavaType.INT32 -> onLog("Nota: El modelo usa INT32, conversion automatica")
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
        var resized: Bitmap? = null
        var inputTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null

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
                resized = Bitmap.createScaledBitmap(bitmap, width, height, true)

                val floatArray = when (inputLayout) {
                    TensorLayout.NCHW -> bitmapToNCHW(resized!!, channels, height, width)
                    TensorLayout.NHWC -> bitmapToNHWC(resized!!, channels, height, width)
                    TensorLayout.UNKNOWN -> bitmapToNCHW(resized!!, channels, height, width)
                }

                if (resized != bitmap && resized != null) {
                    resized!!.recycle()
                    resized = null
                }

                val shape = when (inputLayout) {
                    TensorLayout.NCHW -> longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
                    TensorLayout.NHWC -> longArrayOf(1, height.toLong(), width.toLong(), channels.toLong())
                    TensorLayout.UNKNOWN -> longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
                }

                val env = getEnvironment()
                inputTensor = createTensorForType(env, floatArray, shape, inputType)

                onLog("Ejecutando inferencia...")
                outputs = session.run(mapOf(name to inputTensor))
                
                try { inputTensor?.close() } catch (e: Exception) { }
                inputTensor = null

                val latencyMs = System.currentTimeMillis() - startTime

                val result = parseOutput(outputs!!, bitmap.width, bitmap.height, width, height, onLog)
                
                try { outputs?.close() } catch (e: Exception) { }
                outputs = null

                result?.copy(latencyMs = latencyMs) ?: InferenceResult(
                    className = "Sin resultado",
                    probability = 0f,
                    bbox = null,
                    rawOutput = floatArrayOf(),
                    latencyMs = latencyMs
                )
            } catch (e: OutOfMemoryError) {
                onLog("Error: Memoria insuficiente")
                System.gc()
                null
            } catch (e: OrtException) {
                onLog("Error ONNX: ${e.message}")
                null
            } catch (e: Exception) {
                onLog("Error en inferencia: ${e.message}")
                null
            } finally {
                try { 
                    if (resized != null && resized != bitmap && !resized!!.isRecycled) {
                        resized!!.recycle() 
                    }
                } catch (e: Exception) { }
                try { inputTensor?.close() } catch (e: Exception) { }
                try { outputs?.close() } catch (e: Exception) { }
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

    fun processImageWithConfig(
        context: Context,
        modelUri: Uri,
        bitmap: Bitmap,
        settings: PostProcessingSettings,
        onLog: (String) -> Unit
    ): InferenceResult? {
        val startTime = System.currentTimeMillis()
        var resized: Bitmap? = null
        var inputTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null

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

                resized = Bitmap.createScaledBitmap(bitmap, width, height, true)

                val floatArray = when (inputLayout) {
                    TensorLayout.NCHW -> bitmapToNCHW(resized!!, channels, height, width)
                    TensorLayout.NHWC -> bitmapToNHWC(resized!!, channels, height, width)
                    TensorLayout.UNKNOWN -> bitmapToNCHW(resized!!, channels, height, width)
                }

                if (resized != bitmap && resized != null) {
                    resized!!.recycle()
                    resized = null
                }

                val shape = when (inputLayout) {
                    TensorLayout.NCHW -> longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
                    TensorLayout.NHWC -> longArrayOf(1, height.toLong(), width.toLong(), channels.toLong())
                    TensorLayout.UNKNOWN -> longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
                }

                val env = getEnvironment()
                inputTensor = createTensorForType(env, floatArray, shape, inputType)

                outputs = session.run(mapOf(name to inputTensor))
                
                try { inputTensor?.close() } catch (e: Exception) { }
                inputTensor = null

                val latencyMs = System.currentTimeMillis() - startTime

                val result = parseOutputWithConfig(
                    outputs!!, 
                    bitmap.width, 
                    bitmap.height, 
                    width, 
                    height, 
                    settings,
                    onLog
                )
                
                try { outputs?.close() } catch (e: Exception) { }
                outputs = null

                result?.copy(latencyMs = latencyMs) ?: InferenceResult(
                    className = "Sin resultado",
                    probability = 0f,
                    bbox = null,
                    rawOutput = floatArrayOf(),
                    latencyMs = latencyMs
                )
            } catch (e: OutOfMemoryError) {
                onLog("Error: Memoria insuficiente")
                System.gc()
                null
            } catch (e: OrtException) {
                onLog("Error ONNX: ${e.message}")
                null
            } catch (e: Exception) {
                onLog("Error en inferencia: ${e.message}")
                null
            } finally {
                try { 
                    if (resized != null && resized != bitmap && !resized!!.isRecycled) {
                        resized!!.recycle() 
                    }
                } catch (e: Exception) { }
                try { inputTensor?.close() } catch (e: Exception) { }
                try { outputs?.close() } catch (e: Exception) { }
            }
        }
    }

    private fun parseOutput(
        outputs: OrtSession.Result,
        imgWidth: Int,
        imgHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        onLog: (String) -> Unit
    ): InferenceResult? {
        return try {
            if (outputs.size() == 0) {
                onLog("Error: No hay outputs en el resultado")
                return null
            }

            val outputNames = outputs.mapNotNull { it.key }
            
            val firstOutput = outputs[0]
            val outputValue = firstOutput?.value
            
            val (rawArray, shape) = when (outputValue) {
                is OnnxTensor -> {
                    val info = outputValue.info
                    val arr = extractTensorData(outputValue)
                    if (arr == null) {
                        onLog("Error: No se puede leer tensor (tipo: ${info.type})")
                        return null
                    }
                    Pair(arr, info.shape)
                }
                is OnnxSequence -> {
                    val values = outputValue.value as? List<*>
                    if (values.isNullOrEmpty()) {
                        onLog("Error: Secuencia vacía")
                        return null
                    }
                    
                    val firstTensor = values.firstOrNull { it is OnnxTensor } as? OnnxTensor
                    if (firstTensor != null) {
                        val tensorShape = firstTensor.info.shape
                        val allData = mutableListOf<Float>()
                        var tensorCount = 0
                        
                        for (item in values) {
                            if (item is OnnxTensor) {
                                val tensorData = extractTensorData(item)
                                if (tensorData != null) {
                                    allData.addAll(tensorData.toList())
                                    tensorCount++
                                }
                            }
                        }
                        
                        if (allData.isEmpty()) {
                            onLog("Error: No se pudo extraer datos de tensores")
                            return null
                        }
                        
                        val finalShape = if (tensorCount == 1) {
                            tensorShape
                        } else {
                            val elementsPerTensor = allData.size / tensorCount
                            when {
                                tensorShape.size == 3 -> longArrayOf(tensorCount.toLong(), tensorShape[1], tensorShape[2])
                                tensorShape.size == 2 -> longArrayOf(tensorCount.toLong(), tensorShape[0], tensorShape[1])
                                else -> longArrayOf(1L, tensorCount.toLong(), elementsPerTensor.toLong())
                            }
                        }
                        Pair(allData.toFloatArray(), finalShape)
                    } else {
                        val floats = mutableListOf<Float>()
                        for (item in values) {
                            when (item) {
                                is FloatArray -> floats.addAll(item.toList())
                                is Array<*> -> {
                                    for (subItem in item) {
                                        when (subItem) {
                                            is Float -> floats.add(subItem)
                                            is Number -> floats.add(subItem.toFloat())
                                        }
                                    }
                                }
                                is Number -> floats.add(item.toFloat())
                                is Map<*, *> -> {
                                    for ((_, v) in item) {
                                        when (v) {
                                            is Float -> floats.add(v)
                                            is Number -> floats.add(v.toFloat())
                                        }
                                    }
                                }
                            }
                        }
                        if (floats.isEmpty()) {
                            onLog("Error: No se pudo extraer datos de secuencia")
                            return null
                        }
                        Pair(floats.toFloatArray(), longArrayOf(1L, floats.size.toLong()))
                    }
                }
                is OnnxMap -> {
                    val mapValue = outputValue.value as? Map<*, *>
                    if (mapValue.isNullOrEmpty()) {
                        onLog("Error: Mapa vacío")
                        return null
                    }
                    val floats = mutableListOf<Float>()
                    for ((_, v) in mapValue) {
                        when (v) {
                            is Float -> floats.add(v)
                            is Number -> floats.add(v.toFloat())
                        }
                    }
                    Pair(floats.toFloatArray(), longArrayOf(1L, floats.size.toLong()))
                }
                else -> {
                    if (outputValue != null && isArrayType(outputValue)) {
                        val flatData = flattenArray(outputValue)
                        val shape = inferArrayShape(outputValue)
                        if (flatData.isEmpty()) {
                            onLog("Error: Array vacío")
                            return null
                        }
                        Pair(flatData, shape)
                    } else {
                        onLog("Error: Tipo de output no soportado: ${outputValue?.javaClass?.simpleName ?: "null"}")
                        return null
                    }
                }
            }

            var secondOutputData: FloatArray? = null
            var secondOutputShape: LongArray? = null
            if (outputs.size() >= 2) {
                try {
                    val secondValue = outputs[1]?.value
                    when (secondValue) {
                        is OnnxTensor -> {
                            secondOutputShape = secondValue.info.shape
                            secondOutputData = extractTensorData(secondValue)
                        }
                        is OnnxSequence -> {
                            val values = secondValue.value as? List<*>
                            if (!values.isNullOrEmpty()) {
                                val firstTensor = values.firstOrNull { it is OnnxTensor } as? OnnxTensor
                                if (firstTensor != null) {
                                    val tensorShape = firstTensor.info.shape
                                    val allData = mutableListOf<Float>()
                                    var tensorCount = 0
                                    
                                    for (item in values) {
                                        if (item is OnnxTensor) {
                                            val tensorData = extractTensorData(item)
                                            if (tensorData != null) {
                                                allData.addAll(tensorData.toList())
                                                tensorCount++
                                            }
                                        }
                                    }
                                    
                                    if (allData.isNotEmpty()) {
                                        secondOutputData = allData.toFloatArray()
                                        secondOutputShape = if (tensorCount == 1) {
                                            tensorShape
                                        } else {
                                            val elementsPerTensor = allData.size / tensorCount
                                            when {
                                                tensorShape.size == 3 -> longArrayOf(tensorCount.toLong(), tensorShape[1], tensorShape[2])
                                                tensorShape.size == 2 -> longArrayOf(tensorCount.toLong(), tensorShape[0], tensorShape[1])
                                                else -> longArrayOf(1L, tensorCount.toLong(), elementsPerTensor.toLong())
                                            }
                                        }
                                    }
                                } else {
                                    val floats = mutableListOf<Float>()
                                    values.forEach { item ->
                                        when (item) {
                                            is Number -> floats.add(item.toFloat())
                                            is FloatArray -> floats.addAll(item.toList())
                                        }
                                    }
                                    if (floats.isNotEmpty()) {
                                        secondOutputData = floats.toFloatArray()
                                        secondOutputShape = longArrayOf(1L, floats.size.toLong())
                                    }
                                }
                            }
                        }
                        else -> {
                            if (secondValue != null && isArrayType(secondValue)) {
                                secondOutputData = flattenArray(secondValue)
                                secondOutputShape = inferArrayShape(secondValue)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }

            val format = PostProcessor.detectOutputFormat(shape, outputs.size())
            onLog("Formato detectado: $format")

            cachedOutputShape = shape.copyOf()
            cachedOutputFormat = format
            cachedNumClasses = PostProcessor.getNumClassesFromShape(shape, format, secondOutputShape)

            val detections = if (format == OutputFormat.SSD && secondOutputData != null) {
                PostProcessor.processSSDMultiOutput(
                    boxes = rawArray,
                    boxesShape = shape,
                    scores = secondOutputData,
                    scoresShape = secondOutputShape ?: longArrayOf(),
                    imgWidth = imgWidth,
                    imgHeight = imgHeight
                )
            } else {
                PostProcessor.processOutput(
                    data = rawArray,
                    shape = shape,
                    format = format,
                    imgWidth = imgWidth,
                    imgHeight = imgHeight,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    classNames = null
                )
            }

            if (detections.isEmpty()) {
                onLog("Sin detecciones con confianza suficiente")
                return InferenceResult(
                    className = "Sin detección",
                    probability = 0f,
                    bbox = null,
                    rawOutput = floatArrayOf(),
                    latencyMs = 0,
                    allDetections = emptyList()
                )
            }

            val best = detections.first()
            onLog("${format.name}: ${best.className} (${(best.confidence * 100).toInt()}%)")
            if (detections.size > 1) {
                onLog("Total detecciones: ${detections.size}")
            }

            InferenceResult(
                className = best.className,
                probability = best.confidence,
                bbox = if (best.bbox.width() > 0 && best.bbox.height() > 0) best.bbox else null,
                rawOutput = floatArrayOf(),
                latencyMs = 0,
                allDetections = detections
            )
        } catch (e: Exception) {
            onLog("Error parseando output: ${e.message}")
            null
        }
    }

    private fun parseOutputWithConfig(
        outputs: OrtSession.Result,
        imgWidth: Int,
        imgHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        settings: PostProcessingSettings,
        onLog: (String) -> Unit
    ): InferenceResult? {
        return try {
            if (outputs.size() == 0) {
                onLog("Error: No hay outputs en el resultado")
                return null
            }

            val firstOutput = outputs[0]
            val outputValue = firstOutput?.value
            
            val (rawArray, shape) = when (outputValue) {
                is OnnxTensor -> {
                    val info = outputValue.info
                    val arr = extractTensorData(outputValue)
                    if (arr == null) {
                        onLog("Error: No se puede leer tensor (tipo: ${info.type})")
                        return null
                    }
                    Pair(arr, info.shape)
                }
                is OnnxSequence -> {
                    val values = outputValue.value as? List<*>
                    if (values.isNullOrEmpty()) {
                        onLog("Error: Secuencia vacia")
                        return null
                    }
                    
                    val firstTensor = values.firstOrNull { it is OnnxTensor } as? OnnxTensor
                    if (firstTensor != null) {
                        val tensorShape = firstTensor.info.shape
                        val allData = mutableListOf<Float>()
                        var tensorCount = 0
                        
                        for (item in values) {
                            if (item is OnnxTensor) {
                                val tensorData = extractTensorData(item)
                                if (tensorData != null) {
                                    allData.addAll(tensorData.toList())
                                    tensorCount++
                                }
                            }
                        }
                        
                        if (allData.isEmpty()) {
                            onLog("Error: No se pudo extraer datos de tensores")
                            return null
                        }
                        
                        val finalShape = if (tensorCount == 1) {
                            tensorShape
                        } else {
                            val elementsPerTensor = allData.size / tensorCount
                            when {
                                tensorShape.size == 3 -> longArrayOf(tensorCount.toLong(), tensorShape[1], tensorShape[2])
                                tensorShape.size == 2 -> longArrayOf(tensorCount.toLong(), tensorShape[0], tensorShape[1])
                                else -> longArrayOf(1L, tensorCount.toLong(), elementsPerTensor.toLong())
                            }
                        }
                        Pair(allData.toFloatArray(), finalShape)
                    } else {
                        val floats = mutableListOf<Float>()
                        for (item in values) {
                            when (item) {
                                is FloatArray -> floats.addAll(item.toList())
                                is Number -> floats.add(item.toFloat())
                                is Map<*, *> -> {
                                    for ((_, v) in item) {
                                        when (v) {
                                            is Float -> floats.add(v)
                                            is Number -> floats.add(v.toFloat())
                                        }
                                    }
                                }
                            }
                        }
                        if (floats.isEmpty()) {
                            onLog("Error: No se pudo extraer datos de secuencia")
                            return null
                        }
                        Pair(floats.toFloatArray(), longArrayOf(1L, floats.size.toLong()))
                    }
                }
                is OnnxMap -> {
                    val mapValue = outputValue.value as? Map<*, *>
                    if (mapValue.isNullOrEmpty()) {
                        onLog("Error: Mapa vacio")
                        return null
                    }
                    val floats = mutableListOf<Float>()
                    for ((_, v) in mapValue) {
                        when (v) {
                            is Float -> floats.add(v)
                            is Number -> floats.add(v.toFloat())
                        }
                    }
                    Pair(floats.toFloatArray(), longArrayOf(1L, floats.size.toLong()))
                }
                else -> {
                    if (outputValue != null && isArrayType(outputValue)) {
                        val flatData = flattenArray(outputValue)
                        val inferredShape = inferArrayShape(outputValue)
                        if (flatData.isEmpty()) {
                            onLog("Error: Array vacio")
                            return null
                        }
                        Pair(flatData, inferredShape)
                    } else {
                        onLog("Error: Tipo de output no soportado: ${outputValue?.javaClass?.simpleName ?: "null"}")
                        return null
                    }
                }
            }

            var secondOutputData: FloatArray? = null
            var secondOutputShape: LongArray? = null
            if (outputs.size() >= 2) {
                try {
                    val secondValue = outputs[1]?.value
                    when (secondValue) {
                        is OnnxTensor -> {
                            secondOutputShape = secondValue.info.shape
                            secondOutputData = extractTensorData(secondValue)
                        }
                    }
                } catch (e: Exception) { }
            }

            val format = PostProcessor.detectOutputFormat(shape, outputs.size())

            cachedOutputShape = shape.copyOf()
            cachedOutputFormat = format
            cachedNumClasses = PostProcessor.getNumClassesFromShape(shape, format, secondOutputShape)

            val detections = if (format == OutputFormat.SSD && secondOutputData != null) {
                PostProcessor.processSSDMultiOutput(
                    boxes = rawArray,
                    boxesShape = shape,
                    scores = secondOutputData,
                    scoresShape = secondOutputShape ?: longArrayOf(),
                    imgWidth = imgWidth,
                    imgHeight = imgHeight,
                    confThreshold = settings.confidenceThreshold,
                    iouThreshold = settings.nmsThreshold,
                    classNames = settings.classNames
                )
            } else {
                PostProcessor.processOutput(
                    data = rawArray,
                    shape = shape,
                    format = format,
                    imgWidth = imgWidth,
                    imgHeight = imgHeight,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    confThreshold = settings.confidenceThreshold,
                    iouThreshold = settings.nmsThreshold,
                    classNames = settings.classNames
                )
            }

            val filteredDetections = if (settings.enabledClasses != null) {
                detections.filter { it.classId in settings.enabledClasses!! }
            } else {
                detections
            }.take(settings.maxDetections)

            if (filteredDetections.isEmpty()) {
                return InferenceResult(
                    className = "Sin deteccion",
                    probability = 0f,
                    bbox = null,
                    rawOutput = floatArrayOf(),
                    latencyMs = 0,
                    allDetections = emptyList()
                )
            }

            val best = filteredDetections.first()

            InferenceResult(
                className = best.className,
                probability = best.confidence,
                bbox = if (best.bbox.width() > 0 && best.bbox.height() > 0) best.bbox else null,
                rawOutput = floatArrayOf(),
                latencyMs = 0,
                allDetections = filteredDetections
            )
        } catch (e: Exception) {
            onLog("Error parseando output: ${e.message}")
            null
        }
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
        cachedOutputShape = null
        cachedOutputFormat = OutputFormat.UNKNOWN
        cachedNumClasses = 0
    }

    fun isModelLoaded(): Boolean = synchronized(lock) { currentSession != null }

    fun getModelInfo(): ModelInfo = synchronized(lock) {
        ModelInfo(
            inputShape = inputShape?.copyOf(),
            inputLayout = inputLayout,
            outputFormat = cachedOutputFormat,
            outputShape = cachedOutputShape?.copyOf(),
            numClasses = cachedNumClasses,
            isLoaded = currentSession != null
        )
    }

    fun getNumClasses(): Int = synchronized(lock) { cachedNumClasses }
}
