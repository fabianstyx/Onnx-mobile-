package com.example.onnxsc

import android.graphics.*
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.DecimalFormat

object ResultOverlay {

    private val df = DecimalFormat("#.##%")

    fun show(parent: View, clazz: String, prob: Float, bbox: RectF?) {
        val tv = TextView(parent.context).apply {
            text = "$clase  ${df.format(prob)}"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = 12f
            }
        }
        parent.post {
            (parent as android.widget.FrameLayout).addView(tv)
            tv.x = bbox?.left ?: 0f
            tv.y = bbox?.top ?: 0f
        }
    }

    fun clear(parent: View) {
        (parent as android.widget.FrameLayout).removeAllViews()
    }
}
