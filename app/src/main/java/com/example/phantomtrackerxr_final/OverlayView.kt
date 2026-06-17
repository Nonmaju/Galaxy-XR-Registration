package com.example.phantomtrackerxr_final

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var boxResult: FloatArray = floatArrayOf()

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.CYAN
        textSize = 40f
        style = Paint.Style.FILL
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }

    fun updateResults(result: FloatArray) {
        boxResult = result
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (boxResult.size >= 7) {
            // 1. 팬텀을 찾았을 때만 빨간 박스 그리기
            if (boxResult[0] != -1f) {
                val top = boxResult[0] * height
                val left = boxResult[1] * width
                val bottom = boxResult[2] * height
                val right = boxResult[3] * width
                
                canvas.drawRect(left, top, right, bottom, boxPaint)
            }
        }
    }
}