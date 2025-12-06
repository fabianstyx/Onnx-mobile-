package com.example.onnxsc.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import app.cash.quickjs.QuickJs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScriptRuntime(private val context: Context) {
    
    companion object {
        private const val TAG = "ScriptRuntime"
        private const val SCRIPT_TIMEOUT_MS = 1000L
        private const val MAX_QUEUE_SIZE = 10
    }
    
    interface RuntimeCallback {
        fun onScriptError(error: String)
        fun onScriptLoaded(name: String)
    }
    
    var callback: RuntimeCallback? = null
    val actionsApi = ActionsApi(context)
    
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var quickJs: QuickJs? = null
    
    private val isRunning = AtomicBoolean(false)
    private val currentScript = AtomicReference<String?>(null)
    private val scriptName = AtomicReference<String?>(null)
    
    private val pendingPredictions = java.util.concurrent.LinkedBlockingQueue<Prediction>(MAX_QUEUE_SIZE)
    
    fun start() {
        if (isRunning.getAndSet(true)) return
        
        handlerThread = HandlerThread("ScriptRuntime").apply { start() }
        handler = Handler(handlerThread!!.looper)
        
        handler?.post {
            initializeQuickJs()
        }
        
        ScriptLogger.info("ScriptRuntime iniciado", "engine")
    }
    
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        
        handler?.post {
            try {
                quickJs?.close()
                quickJs = null
            } catch (e: Exception) {
                ScriptLogger.error("Error cerrando QuickJS: ${e.message}", "engine")
            }
        }
        
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        pendingPredictions.clear()
        
        ScriptLogger.info("ScriptRuntime detenido", "engine")
    }
    
    private fun initializeQuickJs() {
        try {
            quickJs = QuickJs.create()
            
            setupHostApis()
            
            ScriptLogger.info("QuickJS inicializado correctamente", "engine")
        } catch (e: Exception) {
            ScriptLogger.error("Error inicializando QuickJS: ${e.message}", "engine")
            callback?.onScriptError("Error inicializando motor JS: ${e.message}")
        }
    }
    
    private fun setupHostApis() {
        val qjs = quickJs ?: return
        
        val apiSetup = """
            var gamepad = {
                press: function(button) { _gamepad_press(button); },
                release: function(button) { _gamepad_release(button); }
            };
            
            function tap(x, y) { _tap(x, y); }
            function swipe(x1, y1, x2, y2, duration) { _swipe(x1, y1, x2, y2, duration || 300); }
            function delay(ms) { _delay(ms); }
            function log(msg) { _log(String(msg)); }
            
            var console = {
                log: function() { _log(Array.prototype.slice.call(arguments).join(' ')); },
                error: function() { _log('[ERROR] ' + Array.prototype.slice.call(arguments).join(' ')); },
                warn: function() { _log('[WARN] ' + Array.prototype.slice.call(arguments).join(' ')); }
            };
        """
        
        qjs.evaluate(apiSetup)
        
        qjs.set("_tap", TapFunction::class.java, TapFunction { x, y -> actionsApi.tap(x, y) })
        qjs.set("_swipe", SwipeFunction::class.java, SwipeFunction { x1, y1, x2, y2, d -> actionsApi.swipe(x1, y1, x2, y2, d) })
        qjs.set("_delay", DelayFunction::class.java, DelayFunction { ms -> actionsApi.delay(ms) })
        qjs.set("_log", LogFunction::class.java, LogFunction { msg -> actionsApi.log(msg) })
        qjs.set("_gamepad_press", GamepadPressFunction::class.java, GamepadPressFunction { btn -> actionsApi.gamepadPress(btn) })
        qjs.set("_gamepad_release", GamepadReleaseFunction::class.java, GamepadReleaseFunction { btn -> actionsApi.gamepadRelease(btn) })
    }
    
    fun loadScript(name: String, code: String) {
        handler?.post {
            try {
                quickJs?.close()
                quickJs = QuickJs.create()
                setupHostApis()
                
                currentScript.set(code)
                scriptName.set(name)
                
                ScriptLogger.info("Script '$name' cargado (${code.length} bytes)", "engine")
                callback?.onScriptLoaded(name)
            } catch (e: Exception) {
                ScriptLogger.error("Error cargando script: ${e.message}", "engine")
                callback?.onScriptError("Error cargando script: ${e.message}")
            }
        }
    }
    
    fun processPrediction(prediction: Prediction) {
        if (!isRunning.get() || currentScript.get() == null) return
        
        if (pendingPredictions.size >= MAX_QUEUE_SIZE) {
            pendingPredictions.poll()
        }
        pendingPredictions.offer(prediction)
        
        handler?.post {
            val pred = pendingPredictions.poll() ?: return@post
            executePrediction(pred)
        }
    }
    
    private fun executePrediction(prediction: Prediction) {
        val script = currentScript.get() ?: return
        val qjs = quickJs ?: return
        
        try {
            val predictionJs = """
                var prediction = {
                    target: ${prediction.target},
                    x: ${prediction.x},
                    y: ${prediction.y},
                    confidence: ${prediction.confidence},
                    className: "${prediction.className}",
                    classId: ${prediction.classId},
                    width: ${prediction.width},
                    height: ${prediction.height}
                };
            """
            
            qjs.evaluate(predictionJs)
            qjs.evaluate(script)
            
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Error desconocido"
            if (!errorMsg.contains("interrupted")) {
                ScriptLogger.error("Error ejecutando script: $errorMsg", "engine")
            }
        }
    }
    
    fun testScript(code: String, testPrediction: Prediction? = null): String {
        return try {
            val testQjs = QuickJs.create()
            val logs = mutableListOf<String>()
            
            val apiSetup = """
                var gamepad = {
                    press: function(button) { _log('[ACTION] gamepad.press("' + button + '")'); },
                    release: function(button) { _log('[ACTION] gamepad.release("' + button + '")'); }
                };
                function tap(x, y) { _log('[ACTION] tap(' + x + ', ' + y + ')'); }
                function swipe(x1, y1, x2, y2, duration) { _log('[ACTION] swipe(' + x1 + ', ' + y1 + ', ' + x2 + ', ' + y2 + ', ' + (duration || 300) + ')'); }
                function delay(ms) { _log('[ACTION] delay(' + ms + ')'); }
                function log(msg) { _log(String(msg)); }
                var console = {
                    log: function() { _log(Array.prototype.slice.call(arguments).join(' ')); },
                    error: function() { _log('[ERROR] ' + Array.prototype.slice.call(arguments).join(' ')); },
                    warn: function() { _log('[WARN] ' + Array.prototype.slice.call(arguments).join(' ')); }
                };
            """
            
            testQjs.evaluate(apiSetup)
            testQjs.set("_log", LogFunction::class.java, LogFunction { msg -> logs.add(msg) })
            
            val pred = testPrediction ?: Prediction(
                target = true,
                x = 540f,
                y = 960f,
                confidence = 0.85f,
                className = "enemy",
                classId = 0
            )
            
            val predictionJs = """
                var prediction = {
                    target: ${pred.target},
                    x: ${pred.x},
                    y: ${pred.y},
                    confidence: ${pred.confidence},
                    className: "${pred.className}",
                    classId: ${pred.classId},
                    width: ${pred.width},
                    height: ${pred.height}
                };
            """
            
            testQjs.evaluate(predictionJs)
            testQjs.evaluate(code)
            testQjs.close()
            
            if (logs.isEmpty()) {
                "Script ejecutado correctamente (sin output)"
            } else {
                logs.joinToString("\n")
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
    
    fun isActive(): Boolean = isRunning.get() && currentScript.get() != null
    
    fun getCurrentScriptName(): String? = scriptName.get()
    
    fun interface TapFunction { fun tap(x: Float, y: Float) }
    fun interface SwipeFunction { fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) }
    fun interface DelayFunction { fun delay(ms: Long) }
    fun interface LogFunction { fun log(msg: String) }
    fun interface GamepadPressFunction { fun press(button: String) }
    fun interface GamepadReleaseFunction { fun release(button: String) }
}
