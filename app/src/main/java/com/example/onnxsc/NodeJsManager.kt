package com.example.onnxsc

import android.content.Context
import java.io.File

object NodeJsManager {

    fun ensureNodeJsInstalled(context: Context, onLog: (String) -> Unit): Boolean {
        return try {
            val nodeDir = File(context.filesDir, "nodejs")
            
            if (!nodeDir.exists()) {
                nodeDir.mkdirs()
                onLog("Directorio Node.js creado: ${nodeDir.absolutePath}")
            }

            onLog("Nota: Los operadores Node.js/JavaScript no están soportados")
            onLog("      en ONNX Runtime Android de forma nativa")
            onLog("      El modelo puede funcionar si usa ops estándar")

            val customOpsDir = File(context.filesDir, "custom_ops")
            if (!customOpsDir.exists()) {
                customOpsDir.mkdirs()
            }

            true
        } catch (e: Exception) {
            onLog("Error al configurar Node.js: ${e.message}")
            false
        }
    }

    fun isNodeJsRequired(inspection: ModelInspector.Inspection): Boolean {
        return inspection.hasJsOperators || inspection.hasNodeOps
    }

    fun getNodeJsDir(context: Context): File {
        return File(context.filesDir, "nodejs")
    }
}
