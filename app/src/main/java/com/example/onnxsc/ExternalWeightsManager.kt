package com.example.onnxsc

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

object ExternalWeightsManager {

    fun copyExternalWeights(
        context: Context,
        contentResolver: ContentResolver,
        modelUri: Uri,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            onLog("Buscando pesos externos...")
            
            val modelDir = File(context.filesDir, "models").apply { 
                if (!exists()) mkdirs() 
            }

            val parentUri = getParentUri(modelUri)
            if (parentUri == null) {
                onLog("No se pudo determinar el directorio del modelo")
                return false
            }

            val modelFileName = modelUri.lastPathSegment?.substringAfterLast("/") ?: "model"
            val baseName = modelFileName.removeSuffix(".onnx")

            val possibleWeightFiles = listOf(
                "${baseName}.bin",
                "${baseName}_data",
                "${baseName}.data",
                "weights.bin",
                "external_data.bin"
            )

            var weightsCopied = 0
            for (weightFile in possibleWeightFiles) {
                try {
                    val weightsPath = parentUri.toString().replace(modelFileName, weightFile)
                    val weightsUri = Uri.parse(weightsPath)
                    
                    contentResolver.openInputStream(weightsUri)?.use { input ->
                        val destFile = File(modelDir, weightFile)
                        destFile.outputStream().use { out ->
                            input.copyTo(out)
                        }
                        onLog("Copiado: $weightFile (${destFile.length() / 1024} KB)")
                        weightsCopied++
                    }
                } catch (e: Exception) {
                    // El archivo no existe, continuar buscando
                }
            }

            if (weightsCopied == 0) {
                onLog("No se encontraron archivos de pesos externos")
                
                val weightsFile = File(modelDir, "external_weights.bin")
                contentResolver.openInputStream(modelUri)?.use { input ->
                    weightsFile.outputStream().use { out -> input.copyTo(out) }
                }
                onLog("Modelo copiado como respaldo a ${weightsFile.name}")
            }

            onLog("Directorio de modelos: ${modelDir.absolutePath}")
            true
        } catch (e: SecurityException) {
            onLog("Error de permisos: ${e.message}")
            false
        } catch (e: Exception) {
            onLog("Error al copiar pesos: ${e.message}")
            false
        }
    }

    private fun getParentUri(uri: Uri): Uri? {
        return try {
            val path = uri.toString()
            val lastSlash = path.lastIndexOf("/")
            if (lastSlash > 0) {
                Uri.parse(path.substring(0, lastSlash))
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getExternalWeightsDir(context: Context): File {
        return File(context.filesDir, "models").apply {
            if (!exists()) mkdirs()
        }
    }
}
