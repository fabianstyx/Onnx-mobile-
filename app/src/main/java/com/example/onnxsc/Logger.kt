package com.example.onnxsc

import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

object Logger {

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var console: TextView

    fun init(textView: TextView) {
        console = textView
    }

    fun success(msg: String) {
        log("✅ $msg")
    }

    fun error(msg: String) {
        log("❌ $msg")
    }

    fun info(msg: String) {
        log("ℹ️ $msg")
    }

    private fun log(line: String) {
        val time = sdf.format(Date())
        console.append("> $time  $line\n")
        // auto-scroll al final
        console.post { console.parent.parent.requestChildFocus(console, console) }
    }

    fun clear() = console.setText("")
}
