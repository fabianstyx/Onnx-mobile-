package com.example.onnxsc

import android.content.Context
import java.io.File

object NodeJsManager {

    fun ensureNodeJsInstalled(context: Context, onLog: (String) -> Unit): Boolean {
        val nodeDir = File(context.filesDir, "nodejs")
        if (!nodeDir.exists()) {
            onLog("Node.js no está instalado. Iniciando instalación...")
            // Aquí iría la descarga real; ahora solo mock
            onLog("Instalación de Node.js completada (mock)")
            nodeDir.mkdirs()
        } else {
            onLog("Node.js ya está instalado en ${nodeDir.absolutePath}")
        }
        return true
    }
}
