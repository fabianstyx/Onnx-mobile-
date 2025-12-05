package com.example.onnxsc

import android.content.Context
import java.io.File

object DependencyInstaller {

    fun checkAndInstall(
        context: Context, 
        inspection: ModelInspector.Inspection, 
        onLog: (String) -> Unit
    ) {
        onLog("Verificando dependencias...")
        onLog(inspection.getSummary())

        if (inspection.detectedOps.isNotEmpty()) {
            onLog("Operaciones detectadas: ${inspection.detectedOps.joinToString(", ")}")
        }

        if (inspection.hasExternalWeights) {
            onLog("El modelo requiere pesos externos")
            onLog("   Se buscarán archivos .bin/.data junto al modelo")
        }

        if (inspection.hasJsOperators || inspection.hasNodeOps) {
            onLog("El modelo usa operadores especiales:")
            if (inspection.hasJsOperators) {
                onLog("   - Microsoft contrib operators")
            }
            if (inspection.hasNodeOps) {
                onLog("   - ONNX ML operators")
            }
            onLog("   Nota: Algunos ops pueden no estar soportados")
        }

        val libDir = File(context.filesDir, "custom_ops")
        if (!libDir.exists()) {
            libDir.mkdirs()
        }

        val cacheDir = File(context.cacheDir, "onnx_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        onLog("Directorio de ops: ${libDir.absolutePath}")
        onLog("Verificación completada")
    }

    fun getCustomOpsDir(context: Context): File {
        return File(context.filesDir, "custom_ops").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getCacheDir(context: Context): File {
        return File(context.cacheDir, "onnx_cache").apply {
            if (!exists()) mkdirs()
        }
    }
}
