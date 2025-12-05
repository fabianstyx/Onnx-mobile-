package com.example.onnxsc

object FpsMeter {

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var lastFrameTime = 0L
    private var currentFps = 0.0
    private var currentLatency = 0L
    private var totalLatency = 0L
    private var latencyCount = 0
    private val lock = Any()

    fun tick(onLog: (String) -> Unit) {
        synchronized(lock) {
            val now = System.currentTimeMillis()

            if (lastFrameTime > 0) {
                currentLatency = now - lastFrameTime
                totalLatency += currentLatency
                latencyCount++
            }
            lastFrameTime = now

            frameCount++

            if (lastFpsTime == 0L) {
                lastFpsTime = now
            }

            val elapsed = now - lastFpsTime
            if (elapsed >= 1000) {
                currentFps = frameCount * 1000.0 / elapsed
                val avgLatency = if (latencyCount > 0) totalLatency / latencyCount else 0L
                
                onLog("FPS: %.1f | Latencia avg: %d ms".format(currentFps, avgLatency))
                
                frameCount = 0
                lastFpsTime = now
                totalLatency = 0
                latencyCount = 0
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            frameCount = 0
            lastFpsTime = 0L
            lastFrameTime = 0L
            currentFps = 0.0
            currentLatency = 0L
            totalLatency = 0L
            latencyCount = 0
        }
    }

    fun getStats(): Pair<Double, Long> = synchronized(lock) { Pair(currentFps, currentLatency) }
    
    fun getCurrentFps(): Double = synchronized(lock) { currentFps }
    
    fun getCurrentLatency(): Long = synchronized(lock) { currentLatency }
}
