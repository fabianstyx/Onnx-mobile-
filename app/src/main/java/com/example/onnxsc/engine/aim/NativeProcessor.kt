package com.example.onnxsc.engine.aim

import java.nio.ByteBuffer
import java.nio.FloatBuffer

object NativeProcessor {
    
    private var isLoaded = false
    
    init {
        try {
            System.loadLibrary("onnxsc_native")
            isLoaded = true
            android.util.Log.i("NativeProcessor", "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NativeProcessor", "Failed to load native library: ${e.message}")
            isLoaded = false
        }
    }
    
    fun isAvailable(): Boolean = isLoaded
    
    external fun preprocessRGBA(
        byteBuffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        targetSize: Int,
        normalize: Boolean
    ): FloatArray?
    
    external fun preprocessRGBAToBuffer(
        srcByteBuffer: ByteBuffer,
        dstFloatBuffer: FloatBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        targetSize: Int,
        normalize: Boolean
    ): Long
    
    external fun preprocessNHWC(
        srcByteBuffer: ByteBuffer,
        dstFloatBuffer: FloatBuffer,
        srcWidth: Int,
        srcHeight: Int,
        pixelStride: Int,
        rowStride: Int,
        dstWidth: Int,
        dstHeight: Int
    ): Long
    
    external fun preprocessNCHW(
        srcByteBuffer: ByteBuffer,
        dstFloatBuffer: FloatBuffer,
        srcWidth: Int,
        srcHeight: Int,
        pixelStride: Int,
        rowStride: Int,
        dstWidth: Int,
        dstHeight: Int
    ): Long
    
    external fun extractROI(
        srcBuffer: ByteBuffer,
        dstBuffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        pixelStride: Int,
        rowStride: Int,
        roiX: Int,
        roiY: Int,
        roiWidth: Int,
        roiHeight: Int
    )
    
    external fun scaleCoordinates(
        keypoints: FloatArray,
        numKeypoints: Int,
        srcWidth: Float,
        srcHeight: Float,
        dstWidth: Float,
        dstHeight: Float
    )
    
    external fun calculateDeltas(
        targetX: Float,
        targetY: Float,
        currentX: Float,
        currentY: Float,
        sensitivity: Float,
        smoothing: Float,
        deltaTime: Float
    ): FloatArray?
    
    external fun applyPrediction(
        currentX: Float,
        currentY: Float,
        historyX: FloatArray,
        historyY: FloatArray,
        historySize: Int,
        predictionScale: Float,
        maxVelocity: Float,
        deltaTime: Float
    ): FloatArray?
    
    fun preprocessRGBAFallback(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        targetSize: Int,
        normalize: Boolean = true
    ): FloatArray {
        val output = FloatArray(targetSize * targetSize * 3)
        val scaleX = width.toFloat() / targetSize.toFloat()
        val scaleY = height.toFloat() / targetSize.toFloat()
        val normFactor = if (normalize) 1f / 255f else 1f
        
        var outIdx = 0
        for (y in 0 until targetSize) {
            val srcY = (y * scaleY).toInt()
            val rowOffset = srcY * rowStride
            
            for (x in 0 until targetSize) {
                val srcX = (x * scaleX).toInt()
                val pixelOffset = rowOffset + srcX * pixelStride
                
                val r = buffer.get(pixelOffset).toInt() and 0xFF
                val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                val b = buffer.get(pixelOffset + 2).toInt() and 0xFF
                
                output[outIdx++] = r * normFactor
                output[outIdx++] = g * normFactor
                output[outIdx++] = b * normFactor
            }
        }
        
        return output
    }
    
    fun preprocessFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        targetSize: Int
    ): FloatArray {
        return if (isLoaded) {
            preprocessRGBA(buffer, width, height, pixelStride, rowStride, targetSize, true)
                ?: preprocessRGBAFallback(buffer, width, height, pixelStride, rowStride, targetSize, true)
        } else {
            preprocessRGBAFallback(buffer, width, height, pixelStride, rowStride, targetSize, true)
        }
    }
}
