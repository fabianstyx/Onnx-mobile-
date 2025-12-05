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

    fun init(textView: TextView) {
        consoleRef = WeakReference(textView)
        contextRef = WeakReference(textView.context)
        flushPendingLogs()
    }

    fun getContext(): Context? = contextRef?.get()

    fun success(msg: String) = log("✅ $msg")
    fun error(msg: String) = log("❌ $msg")
    fun info(msg: String) = log("ℹ️ $msg")
    fun warn(msg: String) = log("⚠️ $msg")

    private fun log(line: String) {
        val time = sdf.format(Date())
        val formattedLine = "> $time  $line\n"
        
        val console = consoleRef?.get()
        if (console == null) {
            synchronized(pendingLogs) {
                pendingLogs.add(formattedLine)
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
            console.append(line)
            val scrollView = console.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        } catch (e: Exception) {
            // Ignorar errores de UI
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
        }
    }
}
