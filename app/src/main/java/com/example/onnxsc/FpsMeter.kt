package com.example.onnxsc

object FpsMeter {

    private var lastFrame = 0L
    private var frames = 0
    private var lastLog = 0L

    fun tick(onLog: (String) -> Unit) {
        val now = System.currentTimeMillis()
        if (lastFrame == 0L) lastFrame = now
        frames++
        val delta = now - lastFrame
        if (delta >= 1000) {
            val fps = frames * 1000.0 / delta
            onLog("FPS: %.1f | Latencia: %d ms".format(fps, delta))
            frames = 0
            lastFrame = now
        }
    }
}
