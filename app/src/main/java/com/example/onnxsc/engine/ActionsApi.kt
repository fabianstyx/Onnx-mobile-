package com.example.onnxsc.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ActionsApi(private val context: Context) {
    
    interface ActionCallback {
        fun onTap(x: Float, y: Float)
        fun onSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long)
        fun onGamepadPress(button: String)
        fun onGamepadRelease(button: String)
        fun onDelay(ms: Long)
    }
    
    var callback: ActionCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920
    
    fun setScreenDimensions(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }
    
    fun tap(x: Float, y: Float) {
        val normalizedX = if (x <= 1f) x * screenWidth else x
        val normalizedY = if (y <= 1f) y * screenHeight else y
        
        ScriptLogger.action("tap(${"%.1f".format(normalizedX)}, ${"%.1f".format(normalizedY)})")
        
        mainHandler.post {
            callback?.onTap(normalizedX, normalizedY)
        }
    }
    
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val nx1 = if (x1 <= 1f) x1 * screenWidth else x1
        val ny1 = if (y1 <= 1f) y1 * screenHeight else y1
        val nx2 = if (x2 <= 1f) x2 * screenWidth else x2
        val ny2 = if (y2 <= 1f) y2 * screenHeight else y2
        
        ScriptLogger.action("swipe(${"%.1f".format(nx1)}, ${"%.1f".format(ny1)} -> ${"%.1f".format(nx2)}, ${"%.1f".format(ny2)}, ${duration}ms)")
        
        mainHandler.post {
            callback?.onSwipe(nx1, ny1, nx2, ny2, duration)
        }
    }
    
    fun gamepadPress(button: String) {
        ScriptLogger.action("gamepad.press(\"$button\")")
        
        mainHandler.post {
            callback?.onGamepadPress(button)
        }
    }
    
    fun gamepadRelease(button: String) {
        ScriptLogger.action("gamepad.release(\"$button\")")
        
        mainHandler.post {
            callback?.onGamepadRelease(button)
        }
    }
    
    fun delay(ms: Long) {
        if (ms > 0 && ms < 10000) {
            ScriptLogger.action("delay(${ms}ms)")
            callback?.onDelay(ms)
            Thread.sleep(ms)
        }
    }
    
    fun log(message: String) {
        ScriptLogger.info(message, "user-script")
    }
    
    companion object {
        val GAMEPAD_BUTTONS = mapOf(
            "A" to KeyEvent.KEYCODE_BUTTON_A,
            "B" to KeyEvent.KEYCODE_BUTTON_B,
            "X" to KeyEvent.KEYCODE_BUTTON_X,
            "Y" to KeyEvent.KEYCODE_BUTTON_Y,
            "L1" to KeyEvent.KEYCODE_BUTTON_L1,
            "R1" to KeyEvent.KEYCODE_BUTTON_R1,
            "L2" to KeyEvent.KEYCODE_BUTTON_L2,
            "R2" to KeyEvent.KEYCODE_BUTTON_R2,
            "L3" to KeyEvent.KEYCODE_BUTTON_THUMBL,
            "R3" to KeyEvent.KEYCODE_BUTTON_THUMBR,
            "START" to KeyEvent.KEYCODE_BUTTON_START,
            "SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT,
            "DPAD_UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DPAD_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "DPAD_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "DPAD_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT
        )
        
        fun getButtonKeyCode(button: String): Int {
            return GAMEPAD_BUTTONS[button.uppercase()] ?: KeyEvent.KEYCODE_UNKNOWN
        }
    }
}
