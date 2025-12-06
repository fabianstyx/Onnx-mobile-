package com.example.onnxsc.engine

import android.graphics.RectF
import com.example.onnxsc.Detection

data class LogicInstruction(
    val action: ActionType,
    val targetX: Float,
    val targetY: Float,
    val targetX2: Float = 0f,
    val targetY2: Float = 0f,
    val duration: Long = 0L,
    val keyCode: Int = 0,
    val detection: Detection? = null,
    val priority: Int = 0,
    val metadata: Map<String, Any> = emptyMap()
)

enum class ActionType {
    NONE,
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    KEY_PRESS,
    AXIS_CONTROL,
    SCROLL_UP,
    SCROLL_DOWN,
    PINCH_IN,
    PINCH_OUT,
    CUSTOM
}

enum class TargetMode {
    CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_CENTER,
    BOTTOM_CENTER,
    LEFT_CENTER,
    RIGHT_CENTER,
    RANDOM
}

enum class PriorityMode {
    HIGHEST_CONFIDENCE,
    LOWEST_CONFIDENCE,
    LARGEST_AREA,
    SMALLEST_AREA,
    CLOSEST_TO_CENTER,
    FIRST_DETECTED,
    BY_CLASS
}

object LogicEngine {
    
    private var lastProcessedTime = 0L
    private var lastActionTime = 0L
    private var detectionHistory = mutableListOf<List<Detection>>()
    private const val HISTORY_SIZE = 10
    
    fun processDetections(
        detections: List<Detection>,
        screenWidth: Int,
        screenHeight: Int
    ): List<LogicInstruction> {
        
        if (!ConfigEngine.isEnabled) {
            return emptyList()
        }
        
        val currentTime = System.currentTimeMillis()
        val actionDelay = ConfigEngine.actionDelayMs.toLong()
        
        if (currentTime - lastActionTime < actionDelay) {
            return emptyList()
        }
        
        val filtered = filterDetections(detections, screenWidth, screenHeight)
        
        if (filtered.isEmpty()) {
            return emptyList()
        }
        
        updateHistory(filtered)
        
        val prioritized = prioritizeDetections(filtered, screenWidth, screenHeight)
        
        val instructions = generateInstructions(prioritized, screenWidth, screenHeight)
        
        if (instructions.isNotEmpty()) {
            lastActionTime = currentTime
        }
        
        lastProcessedTime = currentTime
        
        return instructions
    }
    
    private fun filterDetections(
        detections: List<Detection>,
        screenWidth: Int,
        screenHeight: Int
    ): List<Detection> {
        
        val confThreshold = ConfigEngine.confidenceThreshold
        val enabledClasses = ConfigEngine.getIntList("detection", "enabled_classes")
        
        val minWidth = ConfigEngine.getFloat("filters", "min_width", 10f)
        val minHeight = ConfigEngine.getFloat("filters", "min_height", 10f)
        val maxWidth = ConfigEngine.getFloat("filters", "max_width", screenWidth.toFloat())
        val maxHeight = ConfigEngine.getFloat("filters", "max_height", screenHeight.toFloat())
        val minArea = ConfigEngine.getFloat("filters", "min_area", 100f)
        val maxArea = ConfigEngine.getFloat("filters", "max_area", (screenWidth * screenHeight).toFloat())
        
        val roiEnabled = ConfigEngine.roiEnabled
        val roiX = ConfigEngine.roiX.toFloat()
        val roiY = ConfigEngine.roiY.toFloat()
        val roiWidth = ConfigEngine.roiWidth.toFloat()
        val roiHeight = ConfigEngine.roiHeight.toFloat()
        val roi = if (roiEnabled) RectF(roiX, roiY, roiX + roiWidth, roiY + roiHeight) else null
        
        return detections.filter { detection ->
            if (detection.confidence < confThreshold) return@filter false
            
            if (enabledClasses.isNotEmpty() && detection.classId !in enabledClasses) {
                return@filter false
            }
            
            val bbox = detection.bbox
            val width = bbox.width()
            val height = bbox.height()
            val area = width * height
            
            if (width < minWidth || width > maxWidth) return@filter false
            if (height < minHeight || height > maxHeight) return@filter false
            if (area < minArea || area > maxArea) return@filter false
            
            if (roi != null) {
                val centerX = bbox.centerX()
                val centerY = bbox.centerY()
                if (!roi.contains(centerX, centerY)) return@filter false
            }
            
            true
        }
    }
    
    private fun prioritizeDetections(
        detections: List<Detection>,
        screenWidth: Int,
        screenHeight: Int
    ): List<Detection> {
        
        val priorityMode = when (ConfigEngine.getString("targeting", "priority_mode", "highest_confidence").lowercase()) {
            "highest_confidence" -> PriorityMode.HIGHEST_CONFIDENCE
            "lowest_confidence" -> PriorityMode.LOWEST_CONFIDENCE
            "largest_area" -> PriorityMode.LARGEST_AREA
            "smallest_area" -> PriorityMode.SMALLEST_AREA
            "closest_to_center" -> PriorityMode.CLOSEST_TO_CENTER
            "first_detected" -> PriorityMode.FIRST_DETECTED
            "by_class" -> PriorityMode.BY_CLASS
            else -> PriorityMode.HIGHEST_CONFIDENCE
        }
        
        val priorityClass = ConfigEngine.getInt("targeting", "priority_class", -1)
        
        val screenCenterX = screenWidth / 2f
        val screenCenterY = screenHeight / 2f
        
        return when (priorityMode) {
            PriorityMode.HIGHEST_CONFIDENCE -> detections.sortedByDescending { it.confidence }
            PriorityMode.LOWEST_CONFIDENCE -> detections.sortedBy { it.confidence }
            PriorityMode.LARGEST_AREA -> detections.sortedByDescending { it.bbox.width() * it.bbox.height() }
            PriorityMode.SMALLEST_AREA -> detections.sortedBy { it.bbox.width() * it.bbox.height() }
            PriorityMode.CLOSEST_TO_CENTER -> detections.sortedBy { detection ->
                val dx = detection.bbox.centerX() - screenCenterX
                val dy = detection.bbox.centerY() - screenCenterY
                dx * dx + dy * dy
            }
            PriorityMode.FIRST_DETECTED -> detections
            PriorityMode.BY_CLASS -> {
                if (priorityClass >= 0) {
                    val priorityDetections = detections.filter { it.classId == priorityClass }
                    val otherDetections = detections.filter { it.classId != priorityClass }
                    priorityDetections.sortedByDescending { it.confidence } + 
                        otherDetections.sortedByDescending { it.confidence }
                } else {
                    detections.sortedBy { it.classId }.sortedByDescending { it.confidence }
                }
            }
        }
    }
    
    private fun generateInstructions(
        detections: List<Detection>,
        screenWidth: Int,
        screenHeight: Int
    ): List<LogicInstruction> {
        
        if (!ConfigEngine.actionEnabled) {
            return emptyList()
        }
        
        val actionMode = ConfigEngine.actionMode.lowercase()
        val maxDetections = ConfigEngine.maxDetections
        
        val targetDetections = when (actionMode) {
            "single" -> detections.take(1)
            "all" -> detections.take(maxDetections)
            "batch" -> detections.take(ConfigEngine.getInt("actions", "batch_size", 5))
            else -> detections.take(1)
        }
        
        val instructions = mutableListOf<LogicInstruction>()
        
        targetDetections.forEachIndexed { index, detection ->
            val instruction = createInstructionForDetection(detection, screenWidth, screenHeight, index)
            if (instruction.action != ActionType.NONE) {
                instructions.add(instruction)
            }
        }
        
        return instructions
    }
    
    private fun createInstructionForDetection(
        detection: Detection,
        screenWidth: Int,
        screenHeight: Int,
        priority: Int
    ): LogicInstruction {
        
        val targetMode = when (ConfigEngine.targetMode.lowercase()) {
            "center" -> TargetMode.CENTER
            "top_left" -> TargetMode.TOP_LEFT
            "top_right" -> TargetMode.TOP_RIGHT
            "bottom_left" -> TargetMode.BOTTOM_LEFT
            "bottom_right" -> TargetMode.BOTTOM_RIGHT
            "top_center" -> TargetMode.TOP_CENTER
            "bottom_center" -> TargetMode.BOTTOM_CENTER
            "left_center" -> TargetMode.LEFT_CENTER
            "right_center" -> TargetMode.RIGHT_CENTER
            "random" -> TargetMode.RANDOM
            else -> TargetMode.CENTER
        }
        
        val (targetX, targetY) = calculateTargetPoint(detection.bbox, targetMode)
        
        val offsetX = ConfigEngine.offsetX.toFloat()
        val offsetY = ConfigEngine.offsetY.toFloat()
        
        val finalX = (targetX + offsetX).coerceIn(0f, screenWidth.toFloat())
        val finalY = (targetY + offsetY).coerceIn(0f, screenHeight.toFloat())
        
        val actionType = determineActionType(detection)
        
        val tapDuration = ConfigEngine.getInt("actions", "tap_duration_ms", 50).toLong()
        val swipeDuration = ConfigEngine.getInt("actions", "swipe_duration_ms", 300).toLong()
        val longPressDuration = ConfigEngine.getInt("actions", "long_press_duration_ms", 500).toLong()
        
        val duration = when (actionType) {
            ActionType.TAP, ActionType.DOUBLE_TAP -> tapDuration
            ActionType.LONG_PRESS -> longPressDuration
            ActionType.SWIPE, ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
            ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT -> swipeDuration
            else -> 0L
        }
        
        var targetX2 = finalX
        var targetY2 = finalY
        
        when (actionType) {
            ActionType.SWIPE_UP -> {
                targetY2 = (finalY - ConfigEngine.getInt("actions", "swipe_distance", 200)).coerceAtLeast(0f)
            }
            ActionType.SWIPE_DOWN -> {
                targetY2 = (finalY + ConfigEngine.getInt("actions", "swipe_distance", 200)).coerceAtMost(screenHeight.toFloat())
            }
            ActionType.SWIPE_LEFT -> {
                targetX2 = (finalX - ConfigEngine.getInt("actions", "swipe_distance", 200)).coerceAtLeast(0f)
            }
            ActionType.SWIPE_RIGHT -> {
                targetX2 = (finalX + ConfigEngine.getInt("actions", "swipe_distance", 200)).coerceAtMost(screenWidth.toFloat())
            }
            else -> {}
        }
        
        return LogicInstruction(
            action = actionType,
            targetX = finalX,
            targetY = finalY,
            targetX2 = targetX2,
            targetY2 = targetY2,
            duration = duration,
            detection = detection,
            priority = priority,
            metadata = mapOf(
                "confidence" to detection.confidence,
                "classId" to detection.classId,
                "className" to detection.className
            )
        )
    }
    
    private fun calculateTargetPoint(bbox: RectF, targetMode: TargetMode): Pair<Float, Float> {
        return when (targetMode) {
            TargetMode.CENTER -> Pair(bbox.centerX(), bbox.centerY())
            TargetMode.TOP_LEFT -> Pair(bbox.left, bbox.top)
            TargetMode.TOP_RIGHT -> Pair(bbox.right, bbox.top)
            TargetMode.BOTTOM_LEFT -> Pair(bbox.left, bbox.bottom)
            TargetMode.BOTTOM_RIGHT -> Pair(bbox.right, bbox.bottom)
            TargetMode.TOP_CENTER -> Pair(bbox.centerX(), bbox.top)
            TargetMode.BOTTOM_CENTER -> Pair(bbox.centerX(), bbox.bottom)
            TargetMode.LEFT_CENTER -> Pair(bbox.left, bbox.centerY())
            TargetMode.RIGHT_CENTER -> Pair(bbox.right, bbox.centerY())
            TargetMode.RANDOM -> {
                val x = bbox.left + (Math.random() * bbox.width()).toFloat()
                val y = bbox.top + (Math.random() * bbox.height()).toFloat()
                Pair(x, y)
            }
        }
    }
    
    private fun determineActionType(detection: Detection): ActionType {
        val defaultAction = ConfigEngine.getString("actions", "default_action", "tap").lowercase()
        
        val classActions = ConfigEngine.getString("actions", "class_actions", "")
        if (classActions.isNotEmpty()) {
            val mappings = classActions.split(";")
            for (mapping in mappings) {
                val parts = mapping.split(":")
                if (parts.size == 2) {
                    val classId = parts[0].trim().toIntOrNull()
                    val action = parts[1].trim().lowercase()
                    if (classId == detection.classId) {
                        return parseActionType(action)
                    }
                }
            }
        }
        
        return parseActionType(defaultAction)
    }
    
    private fun parseActionType(actionString: String): ActionType {
        return when (actionString) {
            "tap" -> ActionType.TAP
            "double_tap" -> ActionType.DOUBLE_TAP
            "long_press" -> ActionType.LONG_PRESS
            "swipe" -> ActionType.SWIPE
            "swipe_up" -> ActionType.SWIPE_UP
            "swipe_down" -> ActionType.SWIPE_DOWN
            "swipe_left" -> ActionType.SWIPE_LEFT
            "swipe_right" -> ActionType.SWIPE_RIGHT
            "scroll_up" -> ActionType.SCROLL_UP
            "scroll_down" -> ActionType.SCROLL_DOWN
            "none" -> ActionType.NONE
            else -> ActionType.TAP
        }
    }
    
    private fun updateHistory(detections: List<Detection>) {
        detectionHistory.add(detections)
        if (detectionHistory.size > HISTORY_SIZE) {
            detectionHistory.removeAt(0)
        }
    }
    
    fun getDetectionHistory(): List<List<Detection>> = detectionHistory.toList()
    
    fun clearHistory() {
        detectionHistory.clear()
    }
    
    fun getLastProcessedTime(): Long = lastProcessedTime
    
    fun getLastActionTime(): Long = lastActionTime
    
    fun evaluateCondition(
        detections: List<Detection>,
        condition: String
    ): Boolean {
        val parts = condition.split(" ")
        if (parts.size < 3) return false
        
        val field = parts[0].lowercase()
        val operator = parts[1]
        val value = parts[2]
        
        return when (field) {
            "count" -> {
                val count = detections.size
                val target = value.toIntOrNull() ?: return false
                compareInt(count, operator, target)
            }
            "max_confidence" -> {
                val maxConf = detections.maxOfOrNull { it.confidence } ?: 0f
                val target = value.toFloatOrNull() ?: return false
                compareFloat(maxConf, operator, target)
            }
            "min_confidence" -> {
                val minConf = detections.minOfOrNull { it.confidence } ?: 0f
                val target = value.toFloatOrNull() ?: return false
                compareFloat(minConf, operator, target)
            }
            "has_class" -> {
                val targetClass = value.toIntOrNull() ?: return false
                detections.any { it.classId == targetClass }
            }
            else -> false
        }
    }
    
    private fun compareInt(a: Int, operator: String, b: Int): Boolean {
        return when (operator) {
            ">" -> a > b
            ">=" -> a >= b
            "<" -> a < b
            "<=" -> a <= b
            "==" -> a == b
            "!=" -> a != b
            else -> false
        }
    }
    
    private fun compareFloat(a: Float, operator: String, b: Float): Boolean {
        return when (operator) {
            ">" -> a > b
            ">=" -> a >= b
            "<" -> a < b
            "<=" -> a <= b
            "==" -> a == b
            "!=" -> a != b
            else -> false
        }
    }
}
