package com.example.onnxsc

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DecimalFormat

object ResultOverlay {

    private val df = DecimalFormat("0.0%")
    private val overlayViews = mutableListOf<View>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    
    fun setSourceDimensions(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
    }

    private val COLORS = listOf(
        Color.parseColor("#4CAF50"),
        Color.parseColor("#2196F3"),
        Color.parseColor("#FF9800"),
        Color.parseColor("#E91E63"),
        Color.parseColor("#9C27B0"),
        Color.parseColor("#00BCD4"),
        Color.parseColor("#FFEB3B"),
        Color.parseColor("#795548")
    )

    fun showMultiple(parent: ViewGroup, detections: List<Detection>) {
        Logger.info("[ResultOverlay] showMultiple() con ${detections.size} detecciones, srcDim=${sourceWidth}x${sourceHeight}")
        
        synchronized(lock) {
            val context = parent.context

            runOnMainThread {
                try {
                    for (view in overlayViews.toList()) {
                        try { parent.removeView(view) } catch (e: Exception) {}
                    }
                    overlayViews.clear()

                    if (detections.isEmpty()) {
                        Logger.info("[ResultOverlay] No hay detecciones para mostrar")
                        return@runOnMainThread
                    }

                    val summaryLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 16, 24, 16)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor("#EE000000"))
                            cornerRadius = 16f
                        }
                        elevation = 12f
                    }

                    val titleTv = TextView(context).apply {
                        text = "Detecciones: ${detections.size}"
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                    }
                    summaryLayout.addView(titleTv)

                    val maxToShow = minOf(detections.size, 5)
                    for (i in 0 until maxToShow) {
                        val det = detections[i]
                        val color = COLORS[det.classId % COLORS.size]
                        val detTv = TextView(context).apply {
                            text = "${det.className}: ${df.format(det.confidence)}"
                            setTextColor(color)
                            textSize = 13f
                            setPadding(0, 4, 0, 0)
                        }
                        summaryLayout.addView(detTv)
                    }

                    if (detections.size > maxToShow) {
                        val moreTv = TextView(context).apply {
                            text = "...y ${detections.size - maxToShow} más"
                            setTextColor(Color.GRAY)
                            textSize = 11f
                            setPadding(0, 4, 0, 0)
                        }
                        summaryLayout.addView(moreTv)
                    }

                    val summaryParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = 60
                        rightMargin = 16
                    }

                    parent.addView(summaryLayout, summaryParams)
                    overlayViews.add(summaryLayout)

                    var bboxCount = 0
                    for ((index, det) in detections.withIndex()) {
                        if (det.bbox.width() > 0 && det.bbox.height() > 0) {
                            val color = COLORS[det.classId % COLORS.size]
                            val bboxView = BboxView(
                                context, 
                                det.bbox, 
                                det.className, 
                                det.confidence, 
                                color,
                                sourceWidth,
                                sourceHeight
                            )
                            val bboxParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            parent.addView(bboxView, bboxParams)
                            overlayViews.add(bboxView)
                            bboxCount++
                            
                            if (index < 3) {
                                Logger.info("[ResultOverlay] Bbox $index: ${det.className} (${det.confidence}) @ ${det.bbox}")
                            }
                        }
                    }
                    Logger.info("[ResultOverlay] ${bboxCount} bboxes agregados al overlay")
                } catch (e: Exception) {
                    Logger.error("[ResultOverlay] Error: ${e.message}")
                }
            }
        }
    }

    fun show(parent: ViewGroup, clazz: String, prob: Float, bbox: RectF?) {
        synchronized(lock) {
            val context = parent.context

            val resultText = "$clazz  ${df.format(prob)}"

            val tv = TextView(context).apply {
                text = resultText
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(24, 12, 24, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#DD000000"))
                    cornerRadius = 16f
                }
                elevation = 8f
            }

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )

            if (bbox != null && bbox.width() > 0 && bbox.height() > 0) {
                params.leftMargin = bbox.left.toInt().coerceAtLeast(0)
                params.topMargin = (bbox.top - 50).toInt().coerceAtLeast(0)
                params.gravity = Gravity.TOP or Gravity.START
            } else {
                params.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                params.topMargin = 100
            }

            runOnMainThread {
                try {
                    parent.addView(tv, params)
                    overlayViews.add(tv)

                    if (bbox != null && bbox.width() > 0 && bbox.height() > 0) {
                        val color = COLORS[0]
                        val bboxView = BboxView(
                            context, 
                            bbox, 
                            clazz, 
                            prob, 
                            color,
                            sourceWidth,
                            sourceHeight
                        )
                        val bboxParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        parent.addView(bboxView, bboxParams)
                        overlayViews.add(bboxView)
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    fun clear(parent: ViewGroup) {
        synchronized(lock) {
            val viewsToRemove = overlayViews.toList()
            overlayViews.clear()
            
            runOnMainThread {
                try {
                    for (view in viewsToRemove) {
                        parent.removeView(view)
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private class BboxView(
        context: android.content.Context,
        private val bbox: RectF,
        private val label: String,
        private val confidence: Float,
        private val color: Int,
        private val srcWidth: Int,
        private val srcHeight: Int
    ) : View(context) {

        private val strokePaint = Paint().apply {
            this.color = this@BboxView.color
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        
        private val outlinePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 10f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        private val bgPaint = Paint().apply {
            color = (this@BboxView.color and 0x00FFFFFF) or 0xDD000000.toInt()
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            if (width <= 0 || height <= 0) return
            if (bbox.width() <= 0 || bbox.height() <= 0) return

            val effectiveSrcWidth = if (srcWidth > 0) srcWidth else width
            val effectiveSrcHeight = if (srcHeight > 0) srcHeight else height
            
            val scaleX = width.toFloat() / effectiveSrcWidth
            val scaleY = height.toFloat() / effectiveSrcHeight
            val uniformScale = kotlin.math.min(scaleX, scaleY)
            
            val scaledSrcWidth = effectiveSrcWidth * uniformScale
            val scaledSrcHeight = effectiveSrcHeight * uniformScale
            val offsetX = (width - scaledSrcWidth) / 2f
            val offsetY = (height - scaledSrcHeight) / 2f

            val scaledBox = RectF(
                (bbox.left * uniformScale + offsetX).coerceIn(5f, width.toFloat() - 5f),
                (bbox.top * uniformScale + offsetY).coerceIn(5f, height.toFloat() - 5f),
                (bbox.right * uniformScale + offsetX).coerceIn(5f, width.toFloat() - 5f),
                (bbox.bottom * uniformScale + offsetY).coerceIn(5f, height.toFloat() - 5f)
            )
            
            if (scaledBox.width() <= 10 || scaledBox.height() <= 10) return

            canvas.drawRect(scaledBox, outlinePaint)
            canvas.drawRect(scaledBox, strokePaint)

            val labelText = "$label ${(confidence * 100).toInt()}%"
            val textWidth = textPaint.measureText(labelText)
            val textHeight = 44f
            val labelTop = (scaledBox.top - textHeight - 6).coerceAtLeast(0f)

            canvas.drawRect(
                scaledBox.left - 4,
                labelTop,
                scaledBox.left + textWidth + 24,
                labelTop + textHeight + 4,
                bgPaint
            )

            canvas.drawText(labelText, scaledBox.left + 10, labelTop + textHeight - 8, textPaint)
        }
    }
}
