package com.example.onnxsc

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File // ✅ CORRECCIÓN CLAVE: Aseguramos la importación de File
import com.microsoft.onnxruntime.OrtEnvironment
import com.microsoft.onnxruntime.OrtSession
import org.tensorflow.lite.DataType

class OnnxProcessor(private val context: Context, private val logger: Logger) {

    // ✅ CORRECCIÓN: Inicializamos ortEnv inmediatamente para resolver la referencia
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    fun loadModel(modelPath: String) {
        try {
            // El compilador ahora puede resolver 'File'
            val modelFile = File(modelPath) 
            if (!modelFile.exists()) {
                logger.logError("Modelo no encontrado en la ruta: $modelPath")
                return
            }

            // El compilador ahora puede resolver 'ortEnv'
            ortSession = ortEnv.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            logger.log("Modelo ONNX cargado correctamente.")

        } catch (e: Exception) {
            // Este catch maneja errores y ayuda a evitar el error de "Cannot infer type"
            logger.logError("Error al cargar el modelo: ${e.message}")
        }
    }

    fun process(bitmap: Bitmap): String {
        if (ortSession == null) {
            logger.logError("Sesión de ONNX no iniciada.")
            return "Error"
        }

        try {
            // Lógica de preprocesamiento usando clases TFLite Support
            val tensorImage = TensorImage.fromBitmap(bitmap) 
            val inputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32) 
            
            // ... (rest of the processing logic)
            
            return "Procesamiento completado"
        } catch (e: Exception) {
            logger.logError("Error durante el procesamiento: ${e.message}")
            return "Error"
        }
    }

    fun close() {
        ortSession?.close()
        // ortEnv.close() // Se puede cerrar el entorno aquí si es necesario
    }
}
