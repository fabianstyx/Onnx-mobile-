package com.example.onnxsc

import android.content.Context
import java.io.File

object DependencyInstaller {

    /**
     * 1. Node.js para ONNX-runtime-js (opcional)
     * 2. external_data si el modelo lo requiere
     * 3. custom-ops library (si aparece en el modelo)
     */
    fun checkAndInstall(
        context: Context,
        inspection: Inspection,
        onLog: (String) -> Unit
    ): Boolean {

        onLog("Verificando dependencias...")

        // 1. pesos externos
        if (inspection.hasExternalWeights) {
            onLog("Modelo requiere pesos externos")
            // los pesos se copiarán junto al .onnx después
        }

        // 2. Node.js (mock: solo avisamos)
        if (inspection.hasJsOperators || inspection.hasNodeOps) {
            onLog("⚠️  El modelo usa operadores Node.js")
            onLog("   Instale Node.js en el sistema si ve errores")
            // Aquí podrías descargar un binario node pre-compilado
            // y descomprimirlo en filesDir/node
        }

        // 3. custom-ops library
        val libDir = File(context.filesDir, "custom_ops")
        if (!libDir.exists()) libDir.mkdirs()
        onLog("Ruta de ops personalizadas: ${libDir.absolutePath}")

        onLog("Verificación finalizada")
        return true
    }
}
