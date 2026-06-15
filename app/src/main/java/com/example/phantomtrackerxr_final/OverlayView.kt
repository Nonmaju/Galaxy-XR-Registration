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
        color = Color.RED
        textSize = 50f
        style = Paint.Style.FILL
    }

    fun updateResults(result: FloatArray) {
        boxResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (boxResult.size >= 5) {
            // 수정: 모델의 실제 출력 포맷인 [ymin, xmin, ymax, xmax]에 맞게 그리기
            val top = boxResult[0] * height
            val left = boxResult[1] * width
            val bottom = boxResult[2] * height
            val right = boxResult[3] * width

            val score = boxResult[4]
            val classId = if (boxResult.size > 5) boxResult[5].toInt() else -1
            val inferenceTime = if (boxResult.size > 6) boxResult[6].toInt() else 0

            // 좌표를 바로 넣어서 사각형을 그립니다.
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val infoText = "ID: $classId | Score: ${
                String.format(
                    "%.2f",
                    score
                )
            } | Latency: ${inferenceTime}ms"
            canvas.drawText(infoText, left, top - 10f, textPaint)

            val fps = if (inferenceTime > 0) 1000 / inferenceTime else 0
            canvas.drawText("FPS: $fps", 50f, 100f, textPaint)
        } else if (boxResult.size == 7) {
            val inferenceTime = boxResult[6].toInt()
            canvas.drawText("Latency: ${inferenceTime}ms", 50f, 50f, textPaint)
        }
    }
}