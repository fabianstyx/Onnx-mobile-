package com.example.onnxsc

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context

class OnnxProcessor(context: Context, modelPath: String) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    init {
        try {
            session = env.createSession(modelPath, OrtSession.SessionOptions())
            Logger.log("ONNX model loaded from: $modelPath")
        } catch (e: Exception) {
            Logger.logError("Failed to load ONNX model: ${e.message}")
        }
    }

    /**
     * Ejecuta el modelo ONNX con los inputs dados
     */
    fun run(inputs: Map<String, Any>): Map<String, Any>? {
        if (session == null) {
            Logger.logError("Session is not initialized")
            return null
        }

        return try {
            val onnxInputs = inputs.mapValues { (_, value) ->
                when (value) {
                    is FloatArray -> OnnxTensor.createTensor(env, value)
                    is Array<FloatArray> -> OnnxTensor.createTensor(env, value)
                    else -> throw IllegalArgumentException("Unsupported input type: ${value::class.java}")
                }
            }

            val results = session!!.run(onnxInputs)
            val outputMap = mutableMapOf<String, Any>()
            results.forEach { outputMap[it.key] = it.value.value }
            results.forEach { it.value.close() } // liberar recursos
            outputMap
        } catch (e: Exception) {
            Logger.logError("ONNX model run failed: ${e.message}")
            null
        }
    }

    /**
     * Libera la sesión y recursos asociados
     */
    fun close() {
        try {
            session?.close()
            env.close()
            Logger.log("ONNX session closed")
        } catch (e: Exception) {
            Logger.logError("Error closing ONNX session: ${e.message}")
        }
    }
}