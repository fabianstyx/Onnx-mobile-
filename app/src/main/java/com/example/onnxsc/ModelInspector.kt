package com.example.onnxsc

import android.content.ContentResolver
import android.net.Uri
import kotlin.math.min

object ModelInspector {

    fun inspect(contentResolver: ContentResolver, uri: Uri): Inspection {
        // Abrir el archivo como InputStream
        val inputStream = contentResolver.openInputStream(uri) ?: return Inspection(false, false, false, 0)

        // Leer los primeros 4096 bytes (o menos si el archivo es más pequeño)
        val allBytes = inputStream.use { it.readBytes() }
        val bytes = allBytes.copyOfRange(0, min(4096, allBytes.size))

        // Convertir a String usando UTF-8 de manera segura
        val header = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }

        // Obtener tamaño aproximado en KB
        val fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
        val sizeKb = (fileDescriptor?.statSize ?: 0) / 1024

        // Analizar contenido para detectar características del modelo
        return Inspection(
            hasJsOperators = header.contains("com.microsoft.contrib"),
            hasNodeOps = header.contains("ai.onnx.contrib") || header.contains("ai.onnx.ml"),
            hasExternalWeights = header.contains("external_data"),
            sizeKb = sizeKb
        )
    }

    // Clase de inspección del modelo
    data class Inspection(
        val hasJsOperators: Boolean,
        val hasNodeOps: Boolean,
        val hasExternalWeights: Boolean,
        val sizeKb: Long
    )
}