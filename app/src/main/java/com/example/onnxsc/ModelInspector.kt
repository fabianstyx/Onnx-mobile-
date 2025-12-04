package com.example.onnxsc

import android.content.ContentResolver
import android.net.Uri

object ModelInspector {

    fun inspect(contentResolver: ContentResolver, uri: Uri): Inspection {
        val header = contentResolver.openInputStream(uri)?.use { it.readBytes().take(4096).decodeToString() } ?: ""
        return Inspection(
            hasJsOperators = "com.microsoft.contrib" in header,
            hasNodeOps = "ai.onnx.contrib" in header || "ai.onnx.ml" in header,
            hasExternalWeights = "external_data" in header,
            sizeKb = (contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0) / 1024
        )
    }

    data class Inspection(
        val hasJsOperators: Boolean,
        val hasNodeOps: Boolean,
        val hasExternalWeights: Boolean,
        val sizeKb: Long
    )
}
