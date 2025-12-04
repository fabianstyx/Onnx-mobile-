package com.example.onnxsc

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import java.io.File

object ExternalWeightsManager {

    fun copyExternalWeights(
        context: Context,
        contentResolver: ContentResolver,
        modelUri: Uri,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("Copiando pesos externos...")
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val weightsFile = File(modelDir, "external_weights.bin")
        return try {
            contentResolver.openInputStream(modelUri)?.use { input ->
                weightsFile.outputStream().use { out -> input.copyTo(out) }
            }
            onLog("Pesos externos copiados a ${weightsFile.absolutePath}")
            true
        } catch (e: Exception) {
            onLog("Error al copiar pesos externos: ${e.message}")
            false
        }
    }
}
