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

    fun updateResults(result: FloatArray) {
        boxResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (boxResult.isNotEmpty()) {
            // 수정: 모델의 실제 출력 포맷인 [ymin, xmin, ymax, xmax]에 맞게 그리기
            val top = boxResult[0] * height
            val left = boxResult[1] * width
            val bottom = boxResult[2] * height
            val right = boxResult[3] * width

            // 좌표를 바로 넣어서 사각형을 그립니다.
            canvas.drawRect(left, top, right, bottom, boxPaint)
        }
    }
}