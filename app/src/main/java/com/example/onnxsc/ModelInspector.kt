package com.example.onnxsc

import android.content.ContentResolver
import android.net.Uri
import kotlin.math.min

object ModelInspector {

    fun inspect(contentResolver: ContentResolver, uri: Uri): Inspection {
        return try {
            val inputStream = contentResolver.openInputStream(uri) 
                ?: return Inspection(
                    hasJsOperators = false,
                    hasNodeOps = false,
                    hasExternalWeights = false,
                    sizeKb = 0,
                    modelName = "desconocido",
                    detectedOps = emptyList()
                )

            val allBytes = inputStream.use { it.readBytes() }
            val headerSize = min(8192, allBytes.size)
            val bytes = allBytes.copyOfRange(0, headerSize)

            val header = try {
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }

            val fileDescriptor = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            }
            val sizeKb = (fileDescriptor?.statSize ?: allBytes.size.toLong()) / 1024
            fileDescriptor?.close()

            val modelName = uri.lastPathSegment?.substringAfterLast("/") ?: "modelo"

            val detectedOps = mutableListOf<String>()
            if (header.contains("Conv")) detectedOps.add("Conv")
            if (header.contains("BatchNorm")) detectedOps.add("BatchNorm")
            if (header.contains("Relu") || header.contains("ReLU")) detectedOps.add("ReLU")
            if (header.contains("MaxPool")) detectedOps.add("MaxPool")
            if (header.contains("Softmax")) detectedOps.add("Softmax")
            if (header.contains("Gemm") || header.contains("MatMul")) detectedOps.add("MatMul")

            Inspection(
                hasJsOperators = header.contains("com.microsoft.contrib"),
                hasNodeOps = header.contains("ai.onnx.contrib") || header.contains("ai.onnx.ml"),
                hasExternalWeights = header.contains("external_data") || 
                                     header.contains("data_location") ||
                                     header.contains("external"),
                sizeKb = sizeKb,
                modelName = modelName,
                detectedOps = detectedOps
            )
        } catch (e: Exception) {
            Logger.error("Error al inspeccionar modelo: ${e.message}")
            Inspection(
                hasJsOperators = false,
                hasNodeOps = false,
                hasExternalWeights = false,
                sizeKb = 0,
                modelName = "error",
                detectedOps = emptyList()
            )
        }
    }

    data class Inspection(
        val hasJsOperators: Boolean,
        val hasNodeOps: Boolean,
        val hasExternalWeights: Boolean,
        val sizeKb: Long,
        val modelName: String = "",
        val detectedOps: List<String> = emptyList()
    ) {
        fun getSummary(): String {
            val deps = mutableListOf<String>()
            if (hasExternalWeights) deps.add("pesos externos")
            if (hasJsOperators) deps.add("ops JS")
            if (hasNodeOps) deps.add("ops ML")
            
            return if (deps.isEmpty()) {
                "Sin dependencias especiales"
            } else {
                "Requiere: ${deps.joinToString(", ")}"
            }
        }
    }
}
