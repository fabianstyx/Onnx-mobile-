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

object StatusOverlay {

    private var statusLayout: LinearLayout? = null
    private var txtFps: TextView? = null
    private var txtStatus: TextView? = null
    private var txtDetections: TextView? = null
    private var txtLatency: TextView? = null
    private var parentRef: ViewGroup? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isVisible = AtomicBoolean(false)
    private var recordingAnimator: Runnable? = null
    private var isRecordingDotVisible = true
    
    fun show(parent: ViewGroup) {
        Logger.info("[StatusOverlay] show() llamado")
        
        mainHandler.post {
            removeExistingLayout()
            
            parentRef = parent
            isVisible.set(true)
            
            val context = parent.context
            
            statusLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 14, 24, 14)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#EE111111"))
                    setStroke(3, Color.parseColor("#555555"))
                    cornerRadius = 16f
                }
                elevation = 32f
            }
            
            txtStatus = createStatusText(context, "REC", Color.parseColor("#F44336"))
            txtFps = createStatusText(context, "-- FPS", Color.parseColor("#4CAF50"))
            txtLatency = createStatusText(context, "--ms", Color.parseColor("#FF9800"))
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
                topMargin = 24
                leftMargin = 24
            }
            
            parent.addView(statusLayout, params)
            Logger.info("[StatusOverlay] Layout creado y agregado")
            
            startRecordingAnimation()
        }
    }
    
    private fun removeExistingLayout() {
        recordingAnimator?.let { mainHandler.removeCallbacks(it) }
        recordingAnimator = null
        
        val layout = statusLayout
        val parent = parentRef
        
        if (layout != null && parent != null) {
            try {
                parent.removeView(layout)
            } catch (e: Exception) {}
        }
        
        statusLayout = null
        txtFps = null
        txtStatus = null
        txtDetections = null
        txtLatency = null
    }
    
    private fun createStatusText(context: Context, text: String, color: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(12, 6, 12, 6)
        }
    }
    
    private fun createSpacer(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(2, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(6, 6, 6, 6)
            }
            setBackgroundColor(Color.parseColor("#555555"))
        }
    }
    
    private fun startRecordingAnimation() {
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
    
    fun updateStats(fps: Double, latency: Long, detections: Int) {
        if (!isVisible.get()) return
        
        mainHandler.post {
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
            
            txtDetections?.text = "$detections det"
            txtDetections?.setTextColor(when {
                detections > 0 -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#2196F3")
            })
        }
    }
    
    fun updateDetectionCount(count: Int) {
        if (!isVisible.get()) return
        
        mainHandler.post {
            txtDetections?.text = "$count det"
            txtDetections?.setTextColor(when {
                count > 0 -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#2196F3")
            })
        }
    }
    
    fun updateFps(fps: Double, latency: Long) {
        if (!isVisible.get()) return
        
        mainHandler.post {
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
        }
    }
    
    fun setRecording(recording: Boolean) {
        mainHandler.post {
            if (recording) {
                txtStatus?.text = "REC"
                txtStatus?.setTextColor(Color.parseColor("#F44336"))
                startRecordingAnimation()
            } else {
                recordingAnimator?.let { mainHandler.removeCallbacks(it) }
                recordingAnimator = null
                txtStatus?.text = "STOP"
                txtStatus?.setTextColor(Color.parseColor("#9E9E9E"))
                txtStatus?.alpha = 1f
            }
        }
    }
    
    fun hide(parent: ViewGroup) {
        Logger.info("[StatusOverlay] hide() llamado")
        isVisible.set(false)
        
        mainHandler.post {
            removeExistingLayout()
            parentRef = null
        }
    }
    
    fun reset() {
        Logger.info("[StatusOverlay] reset() llamado")
        isVisible.set(false)
        
        mainHandler.post {
            removeExistingLayout()
            parentRef = null
        }
    }
}
