package com.example.onnxsc

object FpsMeter {

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var lastFrameTime = 0L
    private var currentFps = 0.0
    private var currentLatency = 0L

    fun tick(onLog: (String) -> Unit) {
        val now = System.currentTimeMillis()
        
        if (lastFrameTime > 0) {
            currentLatency = now - lastFrameTime
        }
        lastFrameTime = now
        
        frameCount++
        
        if (lastFpsTime == 0L) {
            lastFpsTime = now
        }
        
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            currentFps = frameCount * 1000.0 / elapsed
            onLog("FPS: %.1f | Latencia: %d ms".format(currentFps, currentLatency))
            frameCount = 0
            lastFpsTime = now
        }
    }

    fun reset() {
        frameCount = 0
        lastFpsTime = 0L
        lastFrameTime = 0L
        currentFps = 0.0
        currentLatency = 0L
    }

    fun getStats(): Pair<Double, Long> = Pair(currentFps, currentLatency)
}
