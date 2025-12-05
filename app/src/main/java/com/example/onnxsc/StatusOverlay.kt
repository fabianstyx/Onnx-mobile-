package com.example.onnxsc

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object StatusOverlay {

    private var statusLayout: LinearLayout? = null
    private var txtFps: TextView? = null
    private var txtStatus: TextView? = null
    private var txtDetections: TextView? = null
    private var txtLatency: TextView? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isVisible = AtomicBoolean(false)
    private val detectionCount = AtomicInteger(0)
    private val lastFps = AtomicLong(0)
    private val lastLatency = AtomicLong(0)
    private var updateRunnable: Runnable? = null
    
    fun show(parent: ViewGroup) {
        if (isVisible.getAndSet(true)) return
        
        runOnMainThread {
            if (statusLayout != null) {
                parent.removeView(statusLayout)
            }
            
            val context = parent.context
            
            statusLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(20, 12, 20, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#EE111111"))
                    setStroke(2, Color.parseColor("#444444"))
                    cornerRadius = 12f
                }
                elevation = 24f
            }
            
            txtStatus = createStatusText(context, "REC", Color.parseColor("#F44336"))
            txtFps = createStatusText(context, "0 FPS", Color.parseColor("#4CAF50"))
            txtLatency = createStatusText(context, "0ms", Color.parseColor("#FF9800"))
            txtDetections = createStatusText(context, "0 det", Color.parseColor("#2196F3"))
            
            statusLayout?.addView(txtStatus)
            statusLayout?.addView(createSpacer(context))
            statusLayout?.addView(txtFps)
            statusLayout?.addView(createSpacer(context))
            statusLayout?.addView(txtLatency)
            statusLayout?.addView(createSpacer(context))
            statusLayout?.addView(txtDetections)
            
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = 16
                leftMargin = 16
            }
            
            parent.addView(statusLayout, params)
            
            startUpdating()
        }
    }
    
    private fun createStatusText(context: Context, text: String, color: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(8, 4, 8, 4)
        }
    }
    
    private fun createSpacer(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(4, 4, 4, 4)
            }
            setBackgroundColor(Color.parseColor("#444444"))
        }
    }
    
    private fun startUpdating() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (!isVisible.get()) return
                
                val fps = FpsMeter.getCurrentFps()
                val latency = FpsMeter.getCurrentLatency()
                val detCount = detectionCount.get()
                
                txtFps?.text = "%.1f FPS".format(fps)
                txtFps?.setTextColor(when {
                    fps >= 20 -> Color.parseColor("#4CAF50")
                    fps >= 10 -> Color.parseColor("#FF9800")
                    else -> Color.parseColor("#F44336")
                })
                
                txtLatency?.text = "${latency}ms"
                txtLatency?.setTextColor(when {
                    latency <= 50 -> Color.parseColor("#4CAF50")
                    latency <= 100 -> Color.parseColor("#FF9800")
                    else -> Color.parseColor("#F44336")
                })
                
                txtDetections?.text = "$detCount det"
                txtDetections?.setTextColor(when {
                    detCount > 0 -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#2196F3")
                })
                
                mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.post(updateRunnable!!)
    }
    
    fun updateDetectionCount(count: Int) {
        detectionCount.set(count)
    }
    
    fun setRecording(recording: Boolean) {
        runOnMainThread {
            if (recording) {
                txtStatus?.text = "REC"
                txtStatus?.setTextColor(Color.parseColor("#F44336"))
                animateRecordingDot()
            } else {
                txtStatus?.text = "STOP"
                txtStatus?.setTextColor(Color.parseColor("#9E9E9E"))
            }
        }
    }
    
    private var isRecordingDotVisible = true
    private var recordingAnimator: Runnable? = null
    
    private fun animateRecordingDot() {
        recordingAnimator?.let { mainHandler.removeCallbacks(it) }
        
        recordingAnimator = object : Runnable {
            override fun run() {
                if (!isVisible.get()) return
                
                isRecordingDotVisible = !isRecordingDotVisible
                txtStatus?.alpha = if (isRecordingDotVisible) 1f else 0.3f
                
                mainHandler.postDelayed(this, 500)
            }
        }
        mainHandler.post(recordingAnimator!!)
    }
    
    fun hide(parent: ViewGroup) {
        isVisible.set(false)
        
        updateRunnable?.let { mainHandler.removeCallbacks(it) }
        recordingAnimator?.let { mainHandler.removeCallbacks(it) }
        updateRunnable = null
        recordingAnimator = null
        
        runOnMainThread {
            statusLayout?.let { parent.removeView(it) }
            statusLayout = null
            txtFps = null
            txtStatus = null
            txtDetections = null
            txtLatency = null
        }
    }
    
    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
