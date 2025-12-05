package com.example.onnxsc

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

object ModelInspector {

    fun inspect(contentResolver: ContentResolver, uri: Uri): Inspection {
        // Nombre humano
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        } ?: uri.lastPathSegment ?: "unknown"

        // Header
        val header = contentResolver.openInputStream(uri)?.use { it.readBytes().take(4096).decodeToString() } ?: ""

        // Tamaño
        val size = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            cursor.moveToFirst()
            cursor.getLong(sizeIndex)
        } ?: 0

        return Inspection(
            name = name,
            hasJsOperators = "com.microsoft.contrib" in header,
            hasNodeOps = "ai.onnx.contrib" in header || "ai.onnx.ml" in header,
            hasExternalWeights = "external_data" in header,
            sizeKb = size / 1024
        )
    }

    data class Inspection(
        val name: String,
        val hasJsOperators: Boolean,
        val hasNodeOps: Boolean,
        val hasExternalWeights: Boolean,
        val sizeKb: Long
    )
}
