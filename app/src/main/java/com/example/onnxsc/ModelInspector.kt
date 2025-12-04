package com.example.onnxsc

import android.content.ContentResolver
import android.net.Uri

object ModelInspector {

    fun inspect(contentResolver: ContentResolver, uri: Uri): Inspection {
        val inputStream = contentResolver.openInputStream(uri)
        if (inputStream == null) {
            return Inspection(false, false, false, 0)
        }

        val bytes = inputStream.use { it.readBytes().take(4096).toByteArray() }
        // Convertir a String usando UTF-8
        val header = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }

        val fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
        val sizeKb = (fileDescriptor?.statSize ?: 0) / 1024

        return Inspection(
            hasJsOperators = header.contains("com.microsoft.contrib"),
            hasNodeOps = header.contains("ai.onnx.contrib") || header.contains("ai.onnx.ml"),
            hasExternalWeights = header.contains("external_data"),
            sizeKb = sizeKb
        )
    }

    data class Inspection(
        val hasJsOperators: Boolean,
        val hasNodeOps: Boolean,
        val hasExternalWeights: Boolean,
        val sizeKb: Long
    )
}