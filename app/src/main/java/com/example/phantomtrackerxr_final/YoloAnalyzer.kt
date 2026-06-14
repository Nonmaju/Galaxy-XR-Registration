package com.example.phantomtrackerxr_final

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

class YoloAnalyzer(
    context: Context,
    private val onResult: (FloatArray) -> Unit
) : ImageAnalysis.Analyzer {

    private var interpreter: Interpreter

    // ⚠️ 핵심 수정: 무거운 그릇들은 함수 밖에서 딱 한 번만 생성합니다! (메모리 폭발 방지)
    private val byteBuffer = ByteBuffer.allocateDirect(4 * 1 * 640 * 640 * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val intValues = IntArray(640 * 640)
    private val outputBuffer = Array(1) { Array(300) { FloatArray(6) } }

    init {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd("best_int8.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        val options = Interpreter.Options()

        // ⚠️ 핵심 추가: 기기가 GPU를 지원하면 GPU Delegate를 사용하도록 설정
        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            val delegateOptions = compatList.bestOptionsForThisDevice
            val gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Log.d("PhantomTracker", "GPU Delegate 활성화 성공!")
        } else {
            options.numThreads = 4 // GPU 미지원 시 기존처럼 CPU 4쓰레드 사용
            Log.w("PhantomTracker", "GPU를 지원하지 않아 CPU로 실행합니다.")
        }

        interpreter = Interpreter(modelBuffer, options)
    }

    override fun analyze(image: ImageProxy) {
        try {
            // 1. 카메라 프레임 방향 보정
            val originalBitmap = image.toBitmap()
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
            val resizedBitmap = Bitmap.createScaledBitmap(rotatedBitmap, 640, 640, true)

            // 2. 수동 전처리
            byteBuffer.rewind() // 커서를 맨 앞으로!
            resizedBitmap.getPixels(intValues, 0, 640, 0, 0, 640, 640)

            for (pixelValue in intValues) {
                val r = ((pixelValue shr 16) and 0xFF) / 255.0f
                val g = ((pixelValue shr 8) and 0xFF) / 255.0f
                val b = (pixelValue and 0xFF) / 255.0f
                byteBuffer.putFloat(r)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(b)
            }

            // 3. 추론 실행
            interpreter.run(byteBuffer, outputBuffer)

            // 4. 결과 추출
            onResult(extractBestBox(outputBuffer))

            // ⚠️ 핵심 수정: 다 쓴 사진은 즉시 메모리에서 삭제합니다!
            originalBitmap.recycle()
            if (rotatedBitmap != originalBitmap) rotatedBitmap.recycle()
            if (resizedBitmap != rotatedBitmap) resizedBitmap.recycle()

        } catch (e: Exception) {
            Log.e("PhantomTracker", "AI 추론 중 에러 발생: ${e.message}")
        } finally {
            image.close() // 다음 프레임을 받기 위해 필수
        }
    }

    private fun extractBestBox(output: Array<Array<FloatArray>>): FloatArray {
        val boxes = output[0]
        var bestConfidence = 0f
        var bestBox = floatArrayOf()

        for (i in 0 until 300) {
            val box = boxes[i]
            val confidence = maxOf(box[4], box[5])

            if (confidence > bestConfidence) {
                bestConfidence = confidence
                bestBox = floatArrayOf(box[0], box[1], box[2], box[3], confidence)
            }
        }

        Log.d("PhantomTracker", "최고 확률: $bestConfidence, 좌표: ${bestBox.contentToString()}")

        return if (bestConfidence > 0.1f) {
            bestBox
        } else {
            floatArrayOf()
        }
    }
}