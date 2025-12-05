package com.example.onnxsc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*

object Logger {

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var consoleRef: WeakReference<TextView>? = null
    private var contextRef: WeakReference<Context>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLogs = mutableListOf<String>()
    
    private const val MAX_LINES = 100
    private const val THROTTLE_MS = 200L
    private var lastLogTime = 0L
    private val recentMessages = mutableMapOf<String, Long>()
    private const val DUPLICATE_WINDOW_MS = 2000L

    fun init(textView: TextView) {
        consoleRef = WeakReference(textView)
        contextRef = WeakReference(textView.context)
        textView.setTextIsSelectable(true)
        flushPendingLogs()
    }

    fun getContext(): Context? = contextRef?.get()

    fun success(msg: String) = log("✅ $msg", throttle = false)
    fun error(msg: String) = log("❌ $msg", throttle = false)
    fun info(msg: String) = log("ℹ️ $msg", throttle = true)
    fun warn(msg: String) = log("⚠️ $msg", throttle = false)

    private fun log(line: String, throttle: Boolean = true) {
        val now = System.currentTimeMillis()
        
        if (throttle) {
            val baseMsg = line.replace(Regex("\\d+"), "#")
            val lastTime = recentMessages[baseMsg]
            if (lastTime != null && now - lastTime < DUPLICATE_WINDOW_MS) {
                return
            }
            recentMessages[baseMsg] = now
            
            if (recentMessages.size > 50) {
                val keysToRemove = recentMessages.entries
                    .filter { now - it.value > DUPLICATE_WINDOW_MS * 2 }
                    .map { it.key }
                keysToRemove.forEach { recentMessages.remove(it) }
            }
        }
        
        val time = sdf.format(Date())
        val formattedLine = "> $time  $line\n"
        
        val console = consoleRef?.get()
        if (console == null) {
            synchronized(pendingLogs) {
                if (pendingLogs.size < MAX_LINES) {
                    pendingLogs.add(formattedLine)
                }
            }
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendToConsole(console, formattedLine)
        } else {
            mainHandler.post {
                consoleRef?.get()?.let { appendToConsole(it, formattedLine) }
            }
        }
    }

    private fun appendToConsole(console: TextView, line: String) {
        try {
            val currentText = console.text.toString()
            val lines = currentText.split("\n")
            
            val newText = if (lines.size > MAX_LINES) {
                val trimmed = lines.takeLast(MAX_LINES - 10).joinToString("\n")
                trimmed + "\n" + line
            } else {
                currentText + line
            }
            
            if (lines.size > MAX_LINES) {
                console.text = newText
            } else {
                console.append(line)
            }
            
            val scrollView = console.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        } catch (e: Exception) {
        }
    }

    private fun flushPendingLogs() {
        val console = consoleRef?.get() ?: return
        synchronized(pendingLogs) {
            for (log in pendingLogs) {
                appendToConsole(console, log)
            }
            pendingLogs.clear()
        }
    }

    fun clear() {
        mainHandler.post {
            consoleRef?.get()?.text = ""
            recentMessages.clear()
        }
    }
}
