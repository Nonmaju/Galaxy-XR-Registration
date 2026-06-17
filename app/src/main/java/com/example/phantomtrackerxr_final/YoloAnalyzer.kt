package com.example.phantomtrackerxr_final

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.FileInputStream
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

class YoloAnalyzer(
    context: Context,
    private val onResult: (FloatArray) -> Unit,
) : ImageAnalysis.Analyzer {

    private val interpreter: Interpreter
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

    private val outputBuffer = Array(1) { Array(300) { FloatArray(6) } }

    init {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd("best_float16.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        // 기기가 GPU Delegate를 지원하는지 확인
        val options = Interpreter.Options()
        val compatList = CompatibilityList()
        
        if (compatList.isDelegateSupportedOnThisDevice) {
            // GPU Delegate를 사용할 수 있는 경우 기기에 가장 최적화된 GPU 옵션을 가져와서 적용
            val delegateOptions = compatList.bestOptionsForThisDevice
            delegateOptions.isPrecisionLossAllowed = true
            val gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Log.d("PhantomTracker", "🚀 GPU 활성화 성공!")
        } else {
            // 지원하지 않으면 CPU 멀티스레드 사용
            options.setNumThreads(4)
            Log.w("PhantomTracker", "GPU 미지원으로 CPU 모드로 동작합니다.")
        }
        // 세팅된 옵션으로 모델(interpreter) 생성
        interpreter = Interpreter(modelBuffer, options)
        Log.d("PhantomTracker", "✅ YOLO 인터프리터 초기화 완료!")
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val startTime = System.currentTimeMillis()
        try {
            val bitmap = image.toBitmap()
            val rotationDegrees = image.imageInfo.rotationDegrees
            
            Log.d("PhantomTracker", "분석 시작 - Image size: ${image.width}x${image.height}, Rotation: $rotationDegrees")
            
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(rotatedBitmap)
            tensorImage = imageProcessor.process(tensorImage)

            interpreter.run(tensorImage.buffer, outputBuffer)

            val bestBox = extractBestBox(outputBuffer)
            val inferenceTime = System.currentTimeMillis() - startTime

            if (bestBox.isNotEmpty()) {
                Log.d("PhantomTracker", "🎯 객체 검출 성공! Conf: ${bestBox[4]}, Time: ${inferenceTime}ms")
                onResult(floatArrayOf(bestBox[0], bestBox[1], bestBox[2], bestBox[3], bestBox[4], 0f, inferenceTime.toFloat()))
            } else {
                // 검출 실패 시에도 Latency 정보 전달을 위해 inferenceTime 포함
                onResult(floatArrayOf(-1f, -1f, -1f, -1f, -1f, -1f, inferenceTime.toFloat()))
            }

            if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
            bitmap.recycle()

        } catch (e: Exception) {
            Log.e("PhantomTracker", "분석 에러: ${e.message}", e)
        } finally {
            image.close()
        }
    }

    private fun extractBestBox(output: Array<Array<FloatArray>>): FloatArray {
        val boxes = output[0]
        var bestConfidence = 0f
        var bestBox = floatArrayOf()

        for (i in 0 until 300) {
            val box = boxes[i]
            val confidence = if (box.size > 4) box[4] else 0f
            if (confidence > bestConfidence) {
                bestConfidence = confidence
                bestBox = box
            }
        }

        return if (bestConfidence > 0.3f) bestBox else floatArrayOf()
    }
}
