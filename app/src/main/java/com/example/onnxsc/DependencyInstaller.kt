package com.example.onnxsc

import android.content.Context
import java.io.File

object DependencyInstaller {

    fun checkAndInstall(context: Context, inspection: ModelInspector.Inspection, onLog: (String) -> Unit) {
        onLog("Verificando dependencias...")

        if (inspection.hasExternalWeights) {
            onLog("Modelo requiere pesos externos")
        }
        if (inspection.hasJsOperators || inspection.hasNodeOps) {
            onLog("⚠️  El modelo usa operadores Node.js")
            onLog("   Instale Node.js en el sistema si ve errores")
        }

        val libDir = File(context.filesDir, "custom_ops")
        if (!libDir.exists()) libDir.mkdirs()
        onLog("Ruta de ops personalizadas: ${libDir.absolutePath}")
        onLog("Verificación finalizada")
    }
}
