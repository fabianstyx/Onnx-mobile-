package com.example.onnxsc

import android.content.Context
import android.net.Uri
import android.content.ContentResolver
import java.io.File
import java.io.InputStream

object ExternalWeightsManager {

    /**
     * Copia los pesos externos a la carpeta de datos de la app.
     */
    fun copyExternalWeights(
        context: Context,
        contentResolver: ContentResolver,
        modelUri: Uri,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("Copiando pesos externos...")

        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()

        val weightsFile = File(modelDir, "external_weights.bin")
        try {
            contentResolver.openInputStream(modelUri)?.use { inputStream ->
                weightsFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            onLog("Pesos externos copiados a ${weightsFile.absolutePath}")
            return true
        } catch (e: Exception) {
            onLog("Error al copiar pesos externos: ${e.message}")
            return false
        }
    }
}
