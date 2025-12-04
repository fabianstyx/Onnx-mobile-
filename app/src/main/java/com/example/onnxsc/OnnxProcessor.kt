package com.example.onnxsc

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.MappedByteBuffer

object OnnxProcessor {

    private const val TAG = "OnnxProcessor"

    fun processImage(context: Context, modelUri: Uri, bitmap: Bitmap, onLog: (String) -> Unit): TensorBuffer? {
        onLog("Procesando imagen con ONNX...")

        try {
            // Cargar el modelo ONNX
            val modelFile = File(context.filesDir, "model.onnx")
            context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                modelFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Cargar el modelo en ONNX Runtime
            val session = ortEnv?.createSession(modelFile.absolutePath)
            if (session == null) {
                onLog("Error al cargar el modelo ONNX")
                return null
            }

            // Preparar la imagen para el modelo
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val inputBuffer = TensorBuffer.createFixedSize(session.getInputTensor(0).shape, session.getInputTensor(0).dataType)
            inputBuffer.loadBuffer(tensorImage.buffer)

            // Ejecutar la inferencia
            val outputBuffer = TensorBuffer.createFixedSize(session.getOutputTensor(0).shape, session.getOutputTensor(0).dataType)
            session.runForTensors(mapOf(0 to inputBuffer), mapOf(0 to outputBuffer))

            onLog("Proceso completado")
            return outputBuffer
        } catch (e: Exception) {
            onLog("Error al procesar imagen: ${e.message}")
            return null
        }
    }
}
