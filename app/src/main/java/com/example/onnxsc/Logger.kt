package com.example.onnxsc

import android.util.Log

class Logger(private val tag: String) {

    // Hacemos que la función sea accesible públicamente para otros archivos (como OnnxProcessor)
    fun log(message: String) { 
        Log.d(tag, message)
    }
    
    // Asumimos que la función logError existe y debe ser accesible
    fun logError(message: String) { 
        Log.e(tag, message)
    }
}
