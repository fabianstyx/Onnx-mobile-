package com.example.onnxsc

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream

object ModelInspector {

    /**
     * Devuelve un resumen rápido del contenido del modelo.
     * Por ahora solo leemos los primeros 4 KB y contamos
     * ciertos strings que indican dependencias externas.
     */
    fun inspect(contentResolver: ContentResolver, uri: Uri): Inspection {
        val header = readHeader(contentResolver, uri)
        return Inspection(
            hasJsOperators = "com.microsoft.contrib" in header,
            hasNodeOps = "ai.onnx.contrib" in header || "ai.onnx.ml" in header,
            hasExternalWeights = "external_data" in header,
            sizeKb = header.length / 1024
        )
    }

    private fun readHeader(contentResolver: ContentResolver, uri: Uri, maxBytes: Int = 4096): String {
        return contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(maxBytes)
            val read = stream.read(buffer)
            String(buffer, 0, read)
        } ?: ""
    }
}

data class Inspection(
    val hasJsOperators: Boolean,
    val hasNodeOps: Boolean,
    val hasExternalWeights: Boolean,
    val sizeKb: Int
)
