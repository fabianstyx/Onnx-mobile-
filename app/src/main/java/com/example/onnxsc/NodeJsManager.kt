package com.example.onnxsc

import android.content.Context
import java.io.File

object NodeJsManager {

    fun ensureNodeJsInstalled(context: Context, onLog: (String) -> Unit): Boolean {
        onLog("Analizando requisitos de Node.js/JavaScript...")
        
        val customOpsDir = File(context.filesDir, "custom_ops")
        if (!customOpsDir.exists()) {
            customOpsDir.mkdirs()
        }

        onLog("NOTA: Los operadores Node.js/JavaScript personalizados")
        onLog("      NO están soportados en ONNX Runtime Android.")
        onLog("      Si el modelo usa solo operadores estándar ONNX,")
        onLog("      funcionará correctamente.")
        onLog("      Directorio custom_ops: ${customOpsDir.absolutePath}")

        return false
    }

    fun isNodeJsRequired(inspection: ModelInspector.Inspection): Boolean {
        return inspection.hasJsOperators || inspection.hasNodeOps
    }

    fun getCustomOpsDir(context: Context): File {
        return File(context.filesDir, "custom_ops").apply {
            if (!exists()) mkdirs()
        }
    }
}
