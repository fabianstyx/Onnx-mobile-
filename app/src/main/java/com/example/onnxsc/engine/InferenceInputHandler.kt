package com.example.onnxsc.engine

import android.os.Handler
import android.os.Looper
import com.example.onnxsc.Detection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class InferenceInputHandler(private val scriptRuntime: ScriptRuntime) {
    
    companion object {
        private const val DEBOUNCE_MS = 16L
        private const val MIN_CONFIDENCE = 0.1f
    }
    
    interface DetectionFilter {
        fun shouldProcess(detection: Detection): Boolean
        fun selectTarget(detections: List<Detection>): Detection?
    }
    
    var detectionFilter: DetectionFilter? = null
    
    private val isEnabled = AtomicBoolean(false)
    private val lastProcessTime = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var targetClassIds: Set<Int>? = null
    private var targetClassNames: Set<String>? = null
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ScriptLogger.info("InferenceInputHandler ${if (enabled) "habilitado" else "deshabilitado"}", "handler")
    }
    
    fun isEnabled(): Boolean = isEnabled.get()
    
    fun setTargetClasses(classIds: Set<Int>? = null, classNames: Set<String>? = null) {
        targetClassIds = classIds
        targetClassNames = classNames?.map { it.lowercase() }?.toSet()
    }
    
    fun processDetections(detections: List<Detection>) {
        if (!isEnabled.get()) return
        if (detections.isEmpty()) {
            processPrediction(createEmptyPrediction())
            return
        }
        
        val now = System.currentTimeMillis()
        if (now - lastProcessTime.get() < DEBOUNCE_MS) return
        lastProcessTime.set(now)
        
        val filteredDetections = detections.filter { det ->
            det.confidence >= MIN_CONFIDENCE && matchesTargetFilter(det)
        }
        
        val target = selectBestTarget(filteredDetections)
        
        if (target != null) {
            val prediction = detectionToPrediction(target, isTarget = true)
            processPrediction(prediction)
        } else if (filteredDetections.isNotEmpty()) {
            val first = filteredDetections.first()
            val prediction = detectionToPrediction(first, isTarget = false)
            processPrediction(prediction)
        } else {
            processPrediction(createEmptyPrediction())
        }
    }
    
    fun processAllDetections(detections: List<Detection>) {
        if (!isEnabled.get()) return
        
        val now = System.currentTimeMillis()
        if (now - lastProcessTime.get() < DEBOUNCE_MS) return
        lastProcessTime.set(now)
        
        detections.filter { it.confidence >= MIN_CONFIDENCE }
            .forEach { det ->
                val prediction = detectionToPrediction(det, matchesTargetFilter(det))
                processPrediction(prediction)
            }
    }
    
    private fun matchesTargetFilter(detection: Detection): Boolean {
        val classIds = targetClassIds
        val classNames = targetClassNames
        
        if (classIds == null && classNames == null) return true
        
        if (classIds != null && detection.classId in classIds) return true
        if (classNames != null && detection.className.lowercase() in classNames) return true
        
        return false
    }
    
    private fun selectBestTarget(detections: List<Detection>): Detection? {
        if (detections.isEmpty()) return null
        
        val filter = detectionFilter
        if (filter != null) {
            return filter.selectTarget(detections)
        }
        
        return detections.maxByOrNull { it.confidence }
    }
    
    private fun detectionToPrediction(detection: Detection, isTarget: Boolean): Prediction {
        val width = detection.bbox.width()
        val height = detection.bbox.height()
        val centerX = detection.bbox.left + width / 2
        val centerY = detection.bbox.top + height / 2
        
        return Prediction(
            target = isTarget,
            x = centerX,
            y = centerY,
            confidence = detection.confidence,
            className = detection.className,
            classId = detection.classId,
            width = width,
            height = height
        )
    }
    
    private fun createEmptyPrediction(): Prediction {
        return Prediction(
            target = false,
            x = 0f,
            y = 0f,
            confidence = 0f,
            className = "",
            classId = -1
        )
    }
    
    private fun processPrediction(prediction: Prediction) {
        scriptRuntime.processPrediction(prediction)
    }
}
