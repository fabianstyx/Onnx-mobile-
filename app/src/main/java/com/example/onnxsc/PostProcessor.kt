package com.example.onnxsc

import android.graphics.RectF
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val bbox: RectF
)

enum class OutputFormat {
    CLASSIFICATION,
    YOLO_V5_V7,
    YOLO_V8_V11,
    RT_DETR,
    SSD,
    SEGMENTATION,
    UNKNOWN
}

object PostProcessor {

    private const val DEFAULT_CONF_THRESHOLD = 0.25f
    private const val DEFAULT_IOU_THRESHOLD = 0.45f
    private const val MAX_DETECTIONS = 100

    fun detectOutputFormat(shape: LongArray, numOutputs: Int): OutputFormat {
        if (shape.isEmpty()) return OutputFormat.UNKNOWN

        if (numOutputs >= 2) {
            return OutputFormat.SSD
        }

        return when (shape.size) {
            2 -> {
                val dim0 = shape[0]
                val dim1 = shape[1]
                when {
                    dim0 == 1L && dim1 <= 1000 && dim1 > 1 -> OutputFormat.CLASSIFICATION
                    dim0 == 1L && dim1 > 1000 -> OutputFormat.UNKNOWN
                    else -> OutputFormat.CLASSIFICATION
                }
            }
            3 -> {
                val batch = shape[0]
                val dim1 = shape[1]
                val dim2 = shape[2]
                when {
                    dim1 in 100..900 && dim2 >= 4 && dim2 <= 100 -> OutputFormat.RT_DETR
                    
                    dim2 >= 5 && dim2 <= 165 && dim1 > 100 -> OutputFormat.YOLO_V5_V7
                    
                    dim1 in 4..84 && dim2 > 100 -> OutputFormat.YOLO_V8_V11
                    
                    dim1 > 100 && dim2 >= 5 && dim2 <= 165 -> OutputFormat.YOLO_V5_V7
                    
                    dim1 > 100 && dim2 > 4 -> OutputFormat.RT_DETR
                    
                    else -> OutputFormat.UNKNOWN
                }
            }
            4 -> {
                if (shape[1] > 1 && shape[2] > 1 && shape[3] > 1) {
                    OutputFormat.SEGMENTATION
                } else {
                    OutputFormat.UNKNOWN
                }
            }
            else -> OutputFormat.UNKNOWN
        }
    }

    fun processOutput(
        data: FloatArray,
        shape: LongArray,
        format: OutputFormat,
        imgWidth: Int,
        imgHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        classNames: List<String>? = null
    ): List<Detection> {
        return when (format) {
            OutputFormat.CLASSIFICATION -> processClassification(data, shape, classNames)
            OutputFormat.YOLO_V5_V7 -> processYoloV5V7(
                data, shape, imgWidth, imgHeight, inputWidth, inputHeight,
                confThreshold, iouThreshold, classNames
            )
            OutputFormat.YOLO_V8_V11 -> processYoloV8V11(
                data, shape, imgWidth, imgHeight, inputWidth, inputHeight,
                confThreshold, iouThreshold, classNames
            )
            OutputFormat.RT_DETR -> processRTDETR(
                data, shape, imgWidth, imgHeight, inputWidth, inputHeight,
                confThreshold, classNames
            )
            OutputFormat.SSD -> processSSD(
                data, shape, imgWidth, imgHeight,
                confThreshold, iouThreshold, classNames
            )
            OutputFormat.SEGMENTATION -> processSegmentation(data, shape, classNames)
            OutputFormat.UNKNOWN -> processGeneric(data, shape, imgWidth, imgHeight, classNames)
        }
    }

    private fun processClassification(
        data: FloatArray,
        shape: LongArray,
        classNames: List<String>?
    ): List<Detection> {
        if (data.isEmpty()) return emptyList()

        val probs = softmax(data)
        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val maxProb = probs[maxIdx]

        val name = classNames?.getOrNull(maxIdx) ?: "Clase $maxIdx"

        return listOf(
            Detection(
                classId = maxIdx,
                className = name,
                confidence = maxProb,
                bbox = RectF(0f, 0f, 0f, 0f)
            )
        )
    }

    private fun processYoloV5V7(
        data: FloatArray,
        shape: LongArray,
        imgWidth: Int,
        imgHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        confThreshold: Float,
        iouThreshold: Float,
        classNames: List<String>?
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        val numDetections = shape[1].toInt()
        val numFields = shape[2].toInt()
        val numClasses = numFields - 5

        val scaleX = imgWidth.toFloat() / inputWidth
        val scaleY = imgHeight.toFloat() / inputHeight

        for (i in 0 until numDetections) {
            val baseIdx = i * numFields
            if (baseIdx + 4 >= data.size) break

            val objectness = data[baseIdx + 4]
            if (objectness < confThreshold) continue

            var maxClassProb = 0f
            var classId = 0
            for (c in 0 until numClasses) {
                val prob = data[baseIdx + 5 + c]
                if (prob > maxClassProb) {
                    maxClassProb = prob
                    classId = c
                }
            }

            val confidence = objectness * maxClassProb
            if (confidence < confThreshold) continue

            val cx = data[baseIdx] * scaleX
            val cy = data[baseIdx + 1] * scaleY
            val w = data[baseIdx + 2] * scaleX
            val h = data[baseIdx + 3] * scaleY

            val bbox = RectF(
                (cx - w / 2).coerceIn(0f, imgWidth.toFloat()),
                (cy - h / 2).coerceIn(0f, imgHeight.toFloat()),
                (cx + w / 2).coerceIn(0f, imgWidth.toFloat()),
                (cy + h / 2).coerceIn(0f, imgHeight.toFloat())
            )

            val name = classNames?.getOrNull(classId) ?: "Clase $classId"
            detections.add(Detection(classId, name, confidence, bbox))
        }

        return applyNMS(detections, iouThreshold)
    }

    private fun processYoloV8V11(
        data: FloatArray,
        shape: LongArray,
        imgWidth: Int,
        imgHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        confThreshold: Float,
        iouThreshold: Float,
        classNames: List<String>?
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        val numFields = shape[1].toInt()
        val numDetections = shape[2].toInt()
        val numClasses = numFields - 4

        val scaleX = imgWidth.toFloat() / inputWidth
        val scaleY = imgHeight.toFloat() / inputHeight

        for (i in 0 until numDetections) {
            var maxClassProb = 0f
            var classId = 0

            for (c in 0 until numClasses) {
                val prob = data[(4 + c) * numDetections + i]
                if (prob > maxClassProb) {
                    maxClassProb = prob
                    classId = c
                }
            }

            if (maxClassProb < confThreshold) continue

            val cx = data[0 * numDetections + i] * scaleX
            val cy = data[1 * numDetections + i] * scaleY
            val w = data[2 * numDetections + i] * scaleX
            val h = data[3 * numDetections + i] * scaleY

            val bbox = RectF(
                (cx - w / 2).coerceIn(0f, imgWidth.toFloat()),
                (cy - h / 2).coerceIn(0f, imgHeight.toFloat()),
                (cx + w / 2).coerceIn(0f, imgWidth.toFloat()),
                (cy + h / 2).coerceIn(0f, imgHeight.toFloat())
            )

            val name = classNames?.getOrNull(classId) ?: "Clase $classId"
            detections.add(Detection(classId, name, maxClassProb, bbox))
        }

        return applyNMS(detections, iouThreshold)
    }

    private fun processRTDETR(
        data: FloatArray,
        shape: LongArray,
        imgWidth: Int,
        imgHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        confThreshold: Float,
        classNames: List<String>?
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        if (shape.size != 3) return detections

        val numDetections = shape[1].toInt()
        val numFields = shape[2].toInt()
        val numClasses = numFields - 4

        val scaleX = imgWidth.toFloat() / inputWidth
        val scaleY = imgHeight.toFloat() / inputHeight

        for (i in 0 until numDetections) {
            val baseIdx = i * numFields
            if (baseIdx + 4 >= data.size) break

            var maxClassProb = 0f
            var classId = 0
            for (c in 0 until numClasses) {
                val prob = data[baseIdx + 4 + c]
                if (prob > maxClassProb) {
                    maxClassProb = prob
                    classId = c
                }
            }

            if (maxClassProb < confThreshold) continue

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

            val name = classNames?.getOrNull(classId) ?: "Clase $classId"
            detections.add(Detection(classId, name, maxClassProb, bbox))
        }

        return applyNMS(detections, DEFAULT_IOU_THRESHOLD)
    }

    fun processSSDMultiOutput(
        boxes: FloatArray,
        boxesShape: LongArray,
        scores: FloatArray,
        scoresShape: LongArray,
        imgWidth: Int,
        imgHeight: Int,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        classNames: List<String>? = null
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        val numDetections = when {
            boxesShape.size == 3 -> boxesShape[1].toInt()
            boxesShape.size == 2 -> boxesShape[0].toInt()
            else -> return detections
        }

        val numClasses = when {
            scoresShape.size == 3 -> scoresShape[2].toInt()
            scoresShape.size == 2 -> scoresShape[1].toInt()
            else -> 1
        }

        for (i in 0 until numDetections) {
            var maxProb = 0f
            var bestClass = 0

            for (c in 0 until numClasses) {
                val scoreIdx = if (scoresShape.size == 3) {
                    i * numClasses + c
                } else {
                    i * numClasses + c
                }

                if (scoreIdx < scores.size) {
                    val prob = scores[scoreIdx]
                    if (prob > maxProb) {
                        maxProb = prob
                        bestClass = c
                    }
                }
            }

            if (maxProb < confThreshold) continue

            val boxIdx = i * 4
            if (boxIdx + 3 >= boxes.size) continue

            val ymin = boxes[boxIdx] * imgHeight
            val xmin = boxes[boxIdx + 1] * imgWidth
            val ymax = boxes[boxIdx + 2] * imgHeight
            val xmax = boxes[boxIdx + 3] * imgWidth

            val bbox = RectF(
                xmin.coerceIn(0f, imgWidth.toFloat()),
                ymin.coerceIn(0f, imgHeight.toFloat()),
                xmax.coerceIn(0f, imgWidth.toFloat()),
                ymax.coerceIn(0f, imgHeight.toFloat())
            )

            if (bbox.width() <= 0 || bbox.height() <= 0) continue

            val name = classNames?.getOrNull(bestClass) ?: "Clase $bestClass"
            detections.add(Detection(bestClass, name, maxProb, bbox))
        }

        return applyNMS(detections, iouThreshold)
    }

    private fun processSSD(
        data: FloatArray,
        shape: LongArray,
        imgWidth: Int,
        imgHeight: Int,
        confThreshold: Float,
        iouThreshold: Float,
        classNames: List<String>?
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        if (shape.size < 2) return detections

        val numDetections = if (shape.size == 3) shape[1].toInt() else shape[0].toInt()
        val numFields = if (shape.size == 3) shape[2].toInt() else shape[1].toInt()

        for (i in 0 until numDetections) {
            val baseIdx = i * numFields
            if (baseIdx + 5 >= data.size) break

            val classId = data[baseIdx + 1].toInt()
            val confidence = data[baseIdx + 2]

            if (confidence < confThreshold) continue

            val ymin = data[baseIdx + 3] * imgHeight
            val xmin = data[baseIdx + 4] * imgWidth
            val ymax = data[baseIdx + 5] * imgHeight
            val xmax = data[baseIdx + 6] * imgWidth

            val bbox = RectF(
                xmin.coerceIn(0f, imgWidth.toFloat()),
                ymin.coerceIn(0f, imgHeight.toFloat()),
                xmax.coerceIn(0f, imgWidth.toFloat()),
                ymax.coerceIn(0f, imgHeight.toFloat())
            )

            val name = classNames?.getOrNull(classId) ?: "Clase $classId"
            detections.add(Detection(classId, name, confidence, bbox))
        }

        return applyNMS(detections, iouThreshold)
    }

    private fun processSegmentation(
        data: FloatArray,
        shape: LongArray,
        classNames: List<String>?
    ): List<Detection> {
        if (shape.size != 4) return emptyList()

        val numClasses = shape[1].toInt()
        val height = shape[2].toInt()
        val width = shape[3].toInt()

        val classPixelCounts = IntArray(numClasses)
        val pixelsPerClass = height * width

        for (y in 0 until height) {
            for (x in 0 until width) {
                var maxProb = Float.MIN_VALUE
                var maxClass = 0
                for (c in 0 until numClasses) {
                    val idx = c * pixelsPerClass + y * width + x
                    if (idx < data.size && data[idx] > maxProb) {
                        maxProb = data[idx]
                        maxClass = c
                    }
                }
                classPixelCounts[maxClass]++
            }
        }

        val totalPixels = height * width
        val results = mutableListOf<Detection>()

        for (c in 0 until numClasses) {
            val percentage = classPixelCounts[c].toFloat() / totalPixels
            if (percentage > 0.01f) {
                val name = classNames?.getOrNull(c) ?: "Clase $c"
                results.add(
                    Detection(
                        classId = c,
                        className = name,
                        confidence = percentage,
                        bbox = RectF(0f, 0f, 0f, 0f)
                    )
                )
            }
        }

        return results.sortedByDescending { it.confidence }
    }

    private fun processGeneric(
        data: FloatArray,
        shape: LongArray,
        imgWidth: Int,
        imgHeight: Int,
        classNames: List<String>?
    ): List<Detection> {
        if (data.isEmpty()) return emptyList()

        if (shape.size == 2 && shape[0] == 1L) {
            return processClassification(data, shape, classNames)
        }

        if (shape.size == 3) {
            val dim1 = shape[1].toInt()
            val dim2 = shape[2].toInt()

            if (dim2 >= 5) {
                return processYoloV5V7(
                    data, shape, imgWidth, imgHeight, 640, 640,
                    DEFAULT_CONF_THRESHOLD, DEFAULT_IOU_THRESHOLD, classNames
                )
            }

            if (dim1 >= 5 && dim2 > 100) {
                return processYoloV8V11(
                    data, shape, imgWidth, imgHeight, 640, 640,
                    DEFAULT_CONF_THRESHOLD, DEFAULT_IOU_THRESHOLD, classNames
                )
            }
        }

        val maxIdx = data.indices.maxByOrNull { data[it] } ?: 0
        val maxVal = data[maxIdx]
        val name = classNames?.getOrNull(maxIdx) ?: "Valor $maxIdx"

        return listOf(
            Detection(
                classId = maxIdx,
                className = name,
                confidence = maxVal.coerceIn(0f, 1f),
                bbox = RectF(0f, 0f, 0f, 0f)
            )
        )
    }

    private fun applyNMS(
        detections: List<Detection>,
        iouThreshold: Float
    ): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()

        while (sorted.isNotEmpty() && result.size < MAX_DETECTIONS) {
            val best = sorted.removeAt(0)
            result.add(best)

            sorted.removeAll { det ->
                det.classId == best.classId && calculateIoU(best.bbox, det.bbox) > iouThreshold
            }
        }

        return result
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectLeft = max(a.left, b.left)
        val intersectTop = max(a.top, b.top)
        val intersectRight = min(a.right, b.right)
        val intersectBottom = min(a.bottom, b.bottom)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return 0f
        }

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - intersectArea

        return if (unionArea > 0) intersectArea / unionArea else 0f
    }

    private fun softmax(input: FloatArray): FloatArray {
        if (input.isEmpty()) return floatArrayOf()
        val maxVal = input.maxOrNull() ?: 0f
        val exps = FloatArray(input.size) { exp((input[it] - maxVal).toDouble()).toFloat() }
        val sum = exps.sum()
        return if (sum > 0) FloatArray(input.size) { exps[it] / sum } else input
    }
}
