package com.example.onnxsc

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.example.onnxsc.config.ModelConfig
import java.io.File
import java.nio.FloatBuffer

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

object OnnxProcessor {

    private var ortEnv: OrtEnvironment? = null
    private var currentSession: OrtSession? = null
    private var currentModelUri: String? = null
    private var inputName: String? = null
    private var inputShape: LongArray? = null
    private var inputLayout: TensorLayout = TensorLayout.NCHW
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
                inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), shape)

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
        config: ModelConfig,
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
                inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), shape)

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
                    val rawData = try {
                        outputValue.floatBuffer
                    } catch (e: Exception) {
                        onLog("Error: No se puede leer tensor como float")
                        return null
                    }
                    val arr = FloatArray(rawData.remaining())
                    rawData.get(arr)
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
                                try {
                                    val buf = item.floatBuffer
                                    val arr = FloatArray(buf.remaining())
                                    buf.get(arr)
                                    allData.addAll(arr.toList())
                                    tensorCount++
                                } catch (e: Exception) { }
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
                            val secondBuffer = secondValue.floatBuffer
                            secondOutputData = FloatArray(secondBuffer.remaining())
                            secondBuffer.get(secondOutputData)
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
                                            try {
                                                val buf = item.floatBuffer
                                                val arr = FloatArray(buf.remaining())
                                                buf.get(arr)
                                                allData.addAll(arr.toList())
                                                tensorCount++
                                            } catch (e: Exception) { }
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
                    val rawData = try {
                        outputValue.floatBuffer
                    } catch (e: Exception) {
                        onLog("Error: No se puede leer tensor como float")
                        return null
                    }
                    val arr = FloatArray(rawData.remaining())
                    rawData.get(arr)
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
                                try {
                                    val buf = item.floatBuffer
                                    val arr = FloatArray(buf.remaining())
                                    buf.get(arr)
                                    allData.addAll(arr.toList())
                                    tensorCount++
                                } catch (e: Exception) { }
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
                            val secondBuffer = secondValue.floatBuffer
                            secondOutputData = FloatArray(secondBuffer.remaining())
                            secondBuffer.get(secondOutputData)
                        }
                    }
                } catch (e: Exception) { }
            }

            val format = PostProcessor.detectOutputFormat(shape, outputs.size())

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
    }

    fun isModelLoaded(): Boolean = synchronized(lock) { currentSession != null }
}
