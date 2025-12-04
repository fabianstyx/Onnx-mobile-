package com.example.onnxsc

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.nio.FloatBuffer

object OnnxProcessor {

    private val ortEnv = OrtEnvironment.getEnvironment()

    fun processImage(
        context: Context,
        modelUri: Uri,
        bitmap: Bitmap,
        onLog: (String) -> Unit
    ): OnnxTensor? {

        onLog("Procesando imagen con ONNX...")

        return try {
            // 1. Copiar modelo
            val modelFile = File(context.filesDir, "model.onnx")
            context.contentResolver.openInputStream(modelUri)?.use { input ->
                modelFile.outputStream().use { out -> input.copyTo(out) }
            }
            onLog("Modelo copiado a ${modelFile.absolutePath}")

            // 2. Crear sesión
            val session = ortEnv.createSession(modelFile.absolutePath)
            onLog("Sesión ONNX creada")

            // 3. Preparar tensor de entrada
            val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val floatBuf = FloatArray(224 * 224 * 3).also { arr ->
                var idx = 0
                for (y in 0 until 224) {
                    for (x in 0 until 224) {
                        val px = resized.getPixel(x, y)
                        arr[idx++] = ((px shr 16) and 0xFF) / 255.0f   // R
                        arr[idx++] = ((px shr 8) and 0xFF) / 255.0f    // G
                        arr[idx++] = (px and 0xFF) / 255.0f            // B
                    }
                }
            }.let { FloatBuffer.wrap(it) }

            val shape = longArrayOf(1, 3, 224, 224) // NCHW
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuf, shape)
            onLog("Tensor de entrada creado")

            // 4. Ejecutar
            val output = session.run(mapOf(session.inputNames.iterator().next() to inputTensor))
            onLog("Inferencia finalizada")

            inputTensor.close() // liberar entrada
            output.use { it[0] as OnnxTensor } // devolver salida
        } catch (e: Exception) {
            onLog("Error en ONNX: ${e.message}")
            null
        }
    }
}
