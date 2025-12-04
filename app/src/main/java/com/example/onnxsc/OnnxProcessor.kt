package com.example.onnxsc

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File 
import com.microsoft.onnxruntime.OrtEnvironment
import com.microsoft.onnxruntime.OrtSession
import org.tensorflow.lite.DataType 

class OnnxProcessor(private val context: Context, private val logger: Logger) {

    // Inicialización del entorno ONNX
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment() 
    private var ortSession: OrtSession? = null

    // Función modificada para recibir el nombre del archivo del modelo
    fun loadModel(modelPath: String) {
        try {
            val modelFile = File(modelPath) 
            if (!modelFile.exists()) {
                logger.logError("Modelo no encontrado en la ruta: $modelPath") 
                return
            }

            // Clases de ONNX Runtime ahora resueltas
            ortSession = ortEnv.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            logger.log("Modelo ONNX cargado correctamente.")

        } catch (e: Exception) {
            logger.logError("Error al cargar el modelo: ${e.message}")
        }
    }

    fun process(bitmap: Bitmap): String {
        if (ortSession == null) {
            logger.logError("Sesión de ONNX no iniciada.")
            return "Error"
        }

        try {
            // Ejemplo de uso de las clases TFLite Support
            val tensorImage = TensorImage.fromBitmap(bitmap) 
            val inputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32) 
            
            // Lógica de procesamiento
            
            return "Procesamiento completado"
        } catch (e: Exception) {
            logger.logError("Error durante el procesamiento: ${e.message}")
            return "Error"
        }
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}
