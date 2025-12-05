package com.example.onnxsc

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.text.DecimalFormat

object ResultOverlay {

    private val df = DecimalFormat("0.0%")
    private val overlayViews = mutableListOf<View>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

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
                        val bboxView = BboxView(context, bbox, clazz)
                        val bboxParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        parent.addView(bboxView, bboxParams)
                        overlayViews.add(bboxView)
                    }
                } catch (e: Exception) {
                    // Ignorar errores de UI
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
                    // Ignorar errores de UI
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
        private val label: String
    ) : View(context) {

        private val strokePaint = Paint().apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        private val bgPaint = Paint().apply {
            color = Color.parseColor("#CC4CAF50")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            if (bbox.width() <= 0 || bbox.height() <= 0) return

            val safeBox = RectF(
                bbox.left.coerceAtLeast(2f),
                bbox.top.coerceAtLeast(2f),
                bbox.right.coerceAtMost(width.toFloat() - 2f),
                bbox.bottom.coerceAtMost(height.toFloat() - 2f)
            )

            canvas.drawRect(safeBox, strokePaint)

            val textWidth = textPaint.measureText(label)
            val textHeight = 36f
            val labelTop = (safeBox.top - textHeight).coerceAtLeast(0f)

            canvas.drawRect(
                safeBox.left,
                labelTop,
                safeBox.left + textWidth + 16,
                labelTop + textHeight,
                bgPaint
            )

            canvas.drawText(label, safeBox.left + 8, labelTop + textHeight - 8, textPaint)
        }
    }
}
