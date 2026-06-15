package com.example.phantomtrackerxr_final

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var boxResult: FloatArray = floatArrayOf()

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    private val textPaint = Paint().apply {
        color = Color.YELLOW // 배경과 잘 구분되도록 노란색으로 변경
        textSize = 60f
        style = Paint.Style.FILL
        setShadowLayer(5f, 2f, 2f, Color.BLACK) // 글씨가 더 잘 보이게 그림자 추가
    }

    fun updateResults(result: FloatArray) {
        boxResult = result
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (boxResult.size >= 7) {
            // 1. 상태에 상관없이 무조건 FPS와 Latency부터 그리기!
            val inferenceTime = boxResult[6].toLong()
            val fps = if (inferenceTime > 0) 1000 / inferenceTime else 0
            val statusText = if (boxResult[0] == -1f) "Status: Searching..." else "Status: Detected!"

            canvas.drawText("GPU Latency: ${inferenceTime}ms | FPS: $fps", 50f, 100f, textPaint)
            canvas.drawText(statusText, 50f, 180f, textPaint)

            // 2. 팬텀을 찾았을 때만 빨간 박스 그리기
            if (boxResult[0] != -1f) {
                // ⚠️ 90도 회전 보정:
                // Image Y(0, 2) -> View Y(top, bottom)
                // Image X(1, 3) -> View X(left, right)
                val top = boxResult[0] * height
                val left = boxResult[1] * width
                val bottom = boxResult[2] * height
                val right = boxResult[3] * width
                val score = boxResult[4]

                canvas.drawRect(left, top, right, bottom, boxPaint)
                canvas.drawText(String.format("Score: %.2f", score), left, top - 10f, textPaint)
            }
        }
    }
}