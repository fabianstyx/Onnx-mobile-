package com.example.onnxsc

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object ExternalWeightsManager {

    private val EXTERNAL_DATA_PATTERNS = listOf(
        Regex("location\"?\\s*:\\s*\"([^\"]+\\.bin)\""),
        Regex("location\"?\\s*:\\s*\"([^\"]+\\.data)\""),
        Regex("external_data.*?\"([^\"]+\\.bin)\""),
        Regex("external_data.*?\"([^\"]+\\.data)\"")
    )

    fun copyExternalWeights(
        context: Context,
        contentResolver: ContentResolver,
        modelUri: Uri,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            onLog("Analizando pesos externos...")

            val modelDir = File(context.filesDir, "models").apply {
                if (!exists()) mkdirs()
            }

            val modelBytes = contentResolver.openInputStream(modelUri)?.use { 
                it.readBytes() 
            } ?: run {
                onLog("Error: No se pudo leer el modelo")
                return false
            }

            val headerSection = modelBytes.take(16384).toByteArray()
            val headerText = try {
                String(headerSection, Charsets.UTF_8)
            } catch (e: Exception) { "" }

            val externalFiles = mutableSetOf<String>()
            for (pattern in EXTERNAL_DATA_PATTERNS) {
                pattern.findAll(headerText).forEach { match ->
                    match.groupValues.getOrNull(1)?.let { fileName ->
                        externalFiles.add(fileName)
                    }
                }
            }

            if (externalFiles.isEmpty()) {
                onLog("No se detectaron referencias a pesos externos en el modelo")
                return searchAndCopyWeightFiles(context, contentResolver, modelUri, modelDir, onLog)
            }

            onLog("Archivos externos detectados: ${externalFiles.joinToString(", ")}")

            val modelFileName = getFileName(contentResolver, modelUri)
            val modelParentPath = modelUri.toString().substringBeforeLast("/")

            var copiedCount = 0
            for (externalFile in externalFiles) {
                val success = tryToCopyWeightFile(
                    contentResolver, 
                    modelParentPath, 
                    externalFile, 
                    modelDir, 
                    onLog
                )
                if (success) copiedCount++
            }

            if (copiedCount > 0) {
                onLog("Copiados $copiedCount archivos de pesos externos")
                true
            } else {
                onLog("No se pudieron copiar los archivos de pesos")
                onLog("Nota: Coloca los archivos .bin/.data junto al modelo")
                false
            }

        } catch (e: Exception) {
            onLog("Error al procesar pesos externos: ${e.message}")
            false
        }
    }

    private fun searchAndCopyWeightFiles(
        context: Context,
        contentResolver: ContentResolver,
        modelUri: Uri,
        modelDir: File,
        onLog: (String) -> Unit
    ): Boolean {
        val modelFileName = getFileName(contentResolver, modelUri)
        val baseName = modelFileName.removeSuffix(".onnx")
        val modelParentPath = modelUri.toString().substringBeforeLast("/")

        val possibleFiles = listOf(
            "$baseName.bin",
            "${baseName}_data",
            "${baseName}.data",
            "${baseName}_weights.bin",
            "model.bin",
            "weights.bin"
        )

        var foundAny = false
        for (fileName in possibleFiles) {
            val success = tryToCopyWeightFile(contentResolver, modelParentPath, fileName, modelDir, onLog)
            if (success) foundAny = true
        }

        return foundAny
    }

    private fun tryToCopyWeightFile(
        contentResolver: ContentResolver,
        parentPath: String,
        fileName: String,
        destDir: File,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            val weightsUriStr = "$parentPath/$fileName"
            val weightsUri = Uri.parse(weightsUriStr)
            
            contentResolver.openInputStream(weightsUri)?.use { input ->
                val destFile = File(destDir, fileName)
                destFile.outputStream().use { out ->
                    input.copyTo(out)
                }
                val sizeKb = destFile.length() / 1024
                onLog("Copiado: $fileName ($sizeKb KB)")
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        var name = "model.onnx"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                }
            }
        } catch (e: Exception) {
            name = uri.lastPathSegment?.substringAfterLast("/") ?: name
        }
        return name
    }

    fun getExternalWeightsDir(context: Context): File {
        return File(context.filesDir, "models").apply {
            if (!exists()) mkdirs()
        }
    }
}
