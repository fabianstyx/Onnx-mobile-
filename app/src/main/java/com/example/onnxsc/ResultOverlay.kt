package com.example.onnxsc

import android.graphics.*
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.text.DecimalFormat

object ResultOverlay {

    private val df = DecimalFormat("#.#%")
    private val overlayViews = mutableListOf<View>()

    fun show(parent: ViewGroup, clazz: String, prob: Float, bbox: RectF?) {
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
        
        if (bbox != null) {
            params.leftMargin = bbox.left.toInt().coerceAtLeast(0)
            params.topMargin = bbox.top.toInt().coerceAtLeast(0)
            params.gravity = Gravity.TOP or Gravity.START
        } else {
            params.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            params.topMargin = 16
        }
        
        parent.post {
            parent.addView(tv, params)
            overlayViews.add(tv)
            
            if (bbox != null) {
                val bboxView = BboxView(context, bbox, clazz)
                val bboxParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                parent.addView(bboxView, bboxParams)
                overlayViews.add(bboxView)
            }
        }
    }

    fun clear(parent: ViewGroup) {
        parent.post {
            for (view in overlayViews) {
                parent.removeView(view)
            }
            overlayViews.clear()
        }
    }

    private class BboxView(
        context: android.content.Context,
        private val bbox: RectF,
        private val label: String
    ) : View(context) {
        
        private val paint = Paint().apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
        }
        
        private val bgPaint = Paint().apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            canvas.drawRect(bbox, paint)
            
            val textWidth = textPaint.measureText(label)
            val textHeight = 40f
            
            canvas.drawRect(
                bbox.left,
                bbox.top - textHeight,
                bbox.left + textWidth + 16,
                bbox.top,
                bgPaint
            )
            
            canvas.drawText(label, bbox.left + 8, bbox.top - 10, textPaint)
        }
    }
}
