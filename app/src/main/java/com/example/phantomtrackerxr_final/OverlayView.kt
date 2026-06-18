package com.example.phantomtrackerxr_final

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var boundingBox: FloatArray? = null

    // 사각형 테두리를 그릴 페인트 설정
    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // YOLO 좌표를 업데이트하고 화면을 다시 그리도록 요청하는 함수
    fun updateBoundingBox(box: FloatArray?) {
        this.boundingBox = box
        postInvalidate() // UI 스레드 밖에서도 안전하게 화면 갱신(onDraw 호출)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // boundingBox가 null이 아니면 사각형을 그립니다
        boundingBox?.let { box ->
            // 보통 YOLO 결과는 [top, left, bottom, right, score] 형태입니다.
            val top = box[0]
            val left = box[1]
            val bottom = box[2]
            val right = box[3]

            canvas.drawRect(left, top, right, bottom, boxPaint)
        }
    }
}