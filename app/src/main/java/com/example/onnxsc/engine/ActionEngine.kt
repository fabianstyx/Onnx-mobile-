package com.example.onnxsc.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object ActionEngine {
    
    private var accessibilityService: AccessibilityService? = null
    private var useRootMode = false
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isExecuting = AtomicBoolean(false)
    private val actionQueue = mutableListOf<LogicInstruction>()
    private var lastActionTime = 0L
    
    interface ActionCallback {
        fun onActionStarted(instruction: LogicInstruction)
        fun onActionCompleted(instruction: LogicInstruction, success: Boolean)
        fun onActionError(instruction: LogicInstruction, error: String)
    }
    
    private var callback: ActionCallback? = null
    
    fun init(context: Context, service: AccessibilityService? = null): Boolean {
        accessibilityService = service
        useRootMode = checkRootAccess()
        isInitialized = true
        return true
    }
    
    fun setAccessibilityService(service: AccessibilityService) {
        accessibilityService = service
    }
    
    fun setCallback(cb: ActionCallback?) {
        callback = cb
    }
    
    fun setRootMode(enabled: Boolean) {
        useRootMode = enabled && checkRootAccess()
    }
    
    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c echo test")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun executeInstruction(instruction: LogicInstruction): Boolean {
        if (!isInitialized) return false
        
        callback?.onActionStarted(instruction)
        
        val success = when (instruction.action) {
            ActionType.TAP -> tap(instruction.targetX, instruction.targetY, instruction.duration)
            ActionType.DOUBLE_TAP -> doubleTap(instruction.targetX, instruction.targetY)
            ActionType.LONG_PRESS -> longPress(instruction.targetX, instruction.targetY, instruction.duration)
            ActionType.SWIPE, ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
            ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT -> {
                swipe(instruction.targetX, instruction.targetY, 
                      instruction.targetX2, instruction.targetY2, instruction.duration)
            }
            ActionType.KEY_PRESS -> keyPress(instruction.keyCode)
            ActionType.SCROLL_UP -> scroll(instruction.targetX, instruction.targetY, -300f)
            ActionType.SCROLL_DOWN -> scroll(instruction.targetX, instruction.targetY, 300f)
            ActionType.AXIS_CONTROL -> axisControl(instruction.keyCode, instruction.targetX)
            ActionType.NONE -> true
            else -> false
        }
        
        callback?.onActionCompleted(instruction, success)
        lastActionTime = System.currentTimeMillis()
        
        return success
    }
    
    fun executeInstructions(instructions: List<LogicInstruction>, delayBetween: Long = 0L) {
        if (instructions.isEmpty()) return
        
        if (isExecuting.get()) {
            actionQueue.addAll(instructions)
            return
        }
        
        isExecuting.set(true)
        executeNextInstruction(instructions.toMutableList(), delayBetween)
    }
    
    private fun executeNextInstruction(remaining: MutableList<LogicInstruction>, delay: Long) {
        if (remaining.isEmpty()) {
            isExecuting.set(false)
            processQueue()
            return
        }
        
        val instruction = remaining.removeAt(0)
        executeInstruction(instruction)
        
        if (remaining.isNotEmpty()) {
            mainHandler.postDelayed({
                executeNextInstruction(remaining, delay)
            }, delay)
        } else {
            isExecuting.set(false)
            processQueue()
        }
    }
    
    private fun processQueue() {
        if (actionQueue.isNotEmpty()) {
            val delay = ConfigEngine.actionDelayMs.toLong()
            val queued = actionQueue.toList()
            actionQueue.clear()
            executeInstructions(queued, delay)
        }
    }
    
    fun tap(x: Float, y: Float, duration: Long = 50L): Boolean {
        return if (useRootMode) {
            executeShellCommand("input tap ${x.toInt()} ${y.toInt()}")
        } else {
            performGesture(createTapPath(x, y), duration)
        }
    }
    
    fun doubleTap(x: Float, y: Float): Boolean {
        return if (useRootMode) {
            executeShellCommand("input tap ${x.toInt()} ${y.toInt()} && sleep 0.1 && input tap ${x.toInt()} ${y.toInt()}")
        } else {
            if (tap(x, y, 30L)) {
                mainHandler.postDelayed({
                    tap(x, y, 30L)
                }, 100)
                true
            } else {
                false
            }
        }
    }
    
    fun longPress(x: Float, y: Float, duration: Long = 500L): Boolean {
        return if (useRootMode) {
            executeShellCommand("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} $duration")
        } else {
            performGesture(createTapPath(x, y), duration)
        }
    }
    
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300L): Boolean {
        return if (useRootMode) {
            executeShellCommand("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $duration")
        } else {
            performGesture(createSwipePath(x1, y1, x2, y2), duration)
        }
    }
    
    fun swipeUp(x: Float, y: Float, distance: Float = 300f, duration: Long = 300L): Boolean {
        return swipe(x, y, x, y - distance, duration)
    }
    
    fun swipeDown(x: Float, y: Float, distance: Float = 300f, duration: Long = 300L): Boolean {
        return swipe(x, y, x, y + distance, duration)
    }
    
    fun swipeLeft(x: Float, y: Float, distance: Float = 300f, duration: Long = 300L): Boolean {
        return swipe(x, y, x - distance, y, duration)
    }
    
    fun swipeRight(x: Float, y: Float, distance: Float = 300f, duration: Long = 300L): Boolean {
        return swipe(x, y, x + distance, y, duration)
    }
    
    fun scroll(x: Float, y: Float, deltaY: Float, duration: Long = 200L): Boolean {
        return swipe(x, y, x, y + deltaY, duration)
    }
    
    fun keyPress(keyCode: Int): Boolean {
        return if (useRootMode) {
            executeShellCommand("input keyevent $keyCode")
        } else {
            false
        }
    }
    
    fun keyPress(keyName: String): Boolean {
        val keyCode = getKeyCodeFromName(keyName)
        return if (keyCode != -1) keyPress(keyCode) else false
    }
    
    fun text(text: String): Boolean {
        return if (useRootMode) {
            val escapedText = text.replace(" ", "%s").replace("\"", "\\\"")
            executeShellCommand("input text \"$escapedText\"")
        } else {
            false
        }
    }
    
    fun axisControl(axis: Int, value: Float): Boolean {
        return if (useRootMode) {
            executeShellCommand("input motionevent AXIS_$axis $value")
        } else {
            false
        }
    }
    
    fun pinchIn(centerX: Float, centerY: Float, startDistance: Float, endDistance: Float, duration: Long = 300L): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && accessibilityService != null) {
            val path1 = Path().apply {
                moveTo(centerX - startDistance / 2, centerY)
                lineTo(centerX - endDistance / 2, centerY)
            }
            val path2 = Path().apply {
                moveTo(centerX + startDistance / 2, centerY)
                lineTo(centerX + endDistance / 2, centerY)
            }
            performMultiGesture(listOf(path1, path2), duration)
        } else {
            false
        }
    }
    
    fun pinchOut(centerX: Float, centerY: Float, startDistance: Float, endDistance: Float, duration: Long = 300L): Boolean {
        return pinchIn(centerX, centerY, endDistance, startDistance, duration)
    }
    
    fun back(): Boolean = keyPress(KeyEvent.KEYCODE_BACK)
    fun home(): Boolean = keyPress(KeyEvent.KEYCODE_HOME)
    fun recents(): Boolean = keyPress(KeyEvent.KEYCODE_APP_SWITCH)
    fun menu(): Boolean = keyPress(KeyEvent.KEYCODE_MENU)
    fun volumeUp(): Boolean = keyPress(KeyEvent.KEYCODE_VOLUME_UP)
    fun volumeDown(): Boolean = keyPress(KeyEvent.KEYCODE_VOLUME_DOWN)
    fun power(): Boolean = keyPress(KeyEvent.KEYCODE_POWER)
    
    private fun createTapPath(x: Float, y: Float): Path {
        return Path().apply {
            moveTo(x, y)
        }
    }
    
    private fun createSwipePath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        return Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
    }
    
    private fun performGesture(path: Path, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val service = accessibilityService ?: return false
        
        return try {
            val gestureBuilder = GestureDescription.Builder()
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            gestureBuilder.addStroke(stroke)
            
            service.dispatchGesture(
                gestureBuilder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                    }
                },
                mainHandler
            )
            true
        } catch (e: Exception) {
            callback?.onActionError(
                LogicInstruction(ActionType.CUSTOM, 0f, 0f),
                "Gesture error: ${e.message}"
            )
            false
        }
    }
    
    private fun performMultiGesture(paths: List<Path>, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val service = accessibilityService ?: return false
        
        return try {
            val gestureBuilder = GestureDescription.Builder()
            paths.forEach { path ->
                gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            }
            
            service.dispatchGesture(
                gestureBuilder.build(),
                null,
                mainHandler
            )
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun executeShellCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            callback?.onActionError(
                LogicInstruction(ActionType.CUSTOM, 0f, 0f),
                "Shell command error: ${e.message}"
            )
            false
        }
    }
    
    private fun getKeyCodeFromName(name: String): Int {
        return when (name.uppercase()) {
            "BACK" -> KeyEvent.KEYCODE_BACK
            "HOME" -> KeyEvent.KEYCODE_HOME
            "MENU" -> KeyEvent.KEYCODE_MENU
            "RECENTS", "APP_SWITCH" -> KeyEvent.KEYCODE_APP_SWITCH
            "POWER" -> KeyEvent.KEYCODE_POWER
            "VOLUME_UP" -> KeyEvent.KEYCODE_VOLUME_UP
            "VOLUME_DOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "ENTER" -> KeyEvent.KEYCODE_ENTER
            "SPACE" -> KeyEvent.KEYCODE_SPACE
            "TAB" -> KeyEvent.KEYCODE_TAB
            "ESCAPE", "ESC" -> KeyEvent.KEYCODE_ESCAPE
            "DEL", "DELETE" -> KeyEvent.KEYCODE_DEL
            "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
            "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "DPAD_CENTER" -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> name.toIntOrNull() ?: -1
        }
    }
    
    fun isAvailable(): Boolean {
        return isInitialized && (accessibilityService != null || useRootMode)
    }
    
    fun isUsingRoot(): Boolean = useRootMode
    
    fun isUsingAccessibility(): Boolean = accessibilityService != null
    
    fun getLastActionTime(): Long = lastActionTime
    
    fun isExecuting(): Boolean = isExecuting.get()
    
    fun cancelPendingActions() {
        actionQueue.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }
    
    fun getStatus(): String {
        return buildString {
            appendLine("=== ActionEngine Status ===")
            appendLine("Initialized: $isInitialized")
            appendLine("Root mode: $useRootMode")
            appendLine("Accessibility: ${accessibilityService != null}")
            appendLine("Executing: ${isExecuting.get()}")
            appendLine("Queue size: ${actionQueue.size}")
            appendLine("Last action: $lastActionTime")
        }
    }
}
