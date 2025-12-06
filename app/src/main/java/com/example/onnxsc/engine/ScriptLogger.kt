package com.example.onnxsc.engine

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object ScriptLogger {
    
    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR, ACTION
    }
    
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val message: String,
        val source: String = "script"
    ) {
        fun formatted(): String {
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val levelStr = when(level) {
                LogLevel.DEBUG -> "[D]"
                LogLevel.INFO -> "[I]"
                LogLevel.WARN -> "[W]"
                LogLevel.ERROR -> "[E]"
                LogLevel.ACTION -> "[A]"
            }
            return "$timeStr $levelStr [$source] $message"
        }
    }
    
    private const val MAX_ENTRIES = 500
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val _logs = MutableLiveData<List<LogEntry>>(emptyList())
    val logs: LiveData<List<LogEntry>> = _logs
    
    private val _latestLog = MutableLiveData<LogEntry>()
    val latestLog: LiveData<LogEntry> = _latestLog
    
    fun log(level: LogLevel, message: String, source: String = "script") {
        val entry = LogEntry(System.currentTimeMillis(), level, message, source)
        logQueue.add(entry)
        
        while (logQueue.size > MAX_ENTRIES) {
            logQueue.poll()
        }
        
        mainHandler.post {
            _latestLog.value = entry
            _logs.value = logQueue.toList()
        }
    }
    
    fun debug(message: String, source: String = "script") = log(LogLevel.DEBUG, message, source)
    fun info(message: String, source: String = "script") = log(LogLevel.INFO, message, source)
    fun warn(message: String, source: String = "script") = log(LogLevel.WARN, message, source)
    fun error(message: String, source: String = "script") = log(LogLevel.ERROR, message, source)
    fun action(message: String, source: String = "actions") = log(LogLevel.ACTION, message, source)
    
    fun clear() {
        logQueue.clear()
        mainHandler.post {
            _logs.value = emptyList()
        }
    }
    
    fun getAll(): List<LogEntry> = logQueue.toList()
    
    fun getFormatted(): String = logQueue.joinToString("\n") { it.formatted() }
}
