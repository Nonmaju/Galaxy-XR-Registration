package com.example.phantomtrackerxr_final

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.arcore.DepthMap
import androidx.xr.arcore.RenderViewpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.tan

class DepthCameraHelper(private val context: Context) {

    var isAnchorPlaced = false
    var latestYoloBox: FloatArray? = null
    var onCentroidCalculated: ((FloatArray) -> Unit)? = null
    var onPointsUpdated: ((List<FloatArray>) -> Unit)? = null

    private var depthJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun startBackgroundThread() { }
    fun stopBackgroundThread() { depthJob?.cancel() }

    @SuppressLint("RestrictedApi")
    fun startDepthStream(session: Session) {
        if (isAnchorPlaced || depthJob != null) {
            Log.v("PhantomTracker", "🚀 뎁스 수집 스킵 (이미 배치됨:$isAnchorPlaced, 작업중:${depthJob != null})")
            return
        }
        Log.d("PhantomTracker", "🚀 [STEP 1] 뎁스 스트림 수집 시작")

        depthJob = scope.launch {
            val depthMapInstance = DepthMap.left(session) ?: DepthMap.mono(session)
            val viewpointInstance = RenderViewpoint.left(session) ?: RenderViewpoint.mono(session)

            if (depthMapInstance == null || viewpointInstance == null) {
                Log.e("PhantomTracker", "❌ [ERROR] DepthMap(${depthMapInstance != null}) 또는 Viewpoint(${viewpointInstance != null}) 획득 실패")
                depthJob = null
                return@launch
            }

            Log.d("PhantomTracker", "✅ [STEP 2] 인스턴스 획득 성공, collect 대기 중...")

            depthMapInstance.state.collect { state ->
                Log.v("PhantomTracker", "📥 [STEP 3] 뎁스 프레임 수신 (${state.width}x${state.height})")
                
                if (isAnchorPlaced) return@collect

                val currentYoloBox = latestYoloBox
                if (currentYoloBox != null) {
                    val viewpoint = viewpointInstance.state.value

                    Log.v("PhantomTracker", "📍 [STEP 4] 분석 시도 (박스 영역 Centroid 계산)")
                    
                    val centroid = projectPixelTo3D(currentYoloBox, state, viewpoint)

                    if (centroid != null) {
                        Log.i("PhantomTracker", "🎯 [SUCCESS] 중심점 확정: ${centroid.joinToString()}")
                        isAnchorPlaced = true
                        context.mainExecutor.execute {
                            onCentroidCalculated?.invoke(centroid)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun projectPixelTo3D(
        box: FloatArray,
        depthSnapshot: DepthMap.State,
        viewpoint: RenderViewpoint.State
    ): FloatArray? {
        val depthWidth = depthSnapshot.width
        val depthHeight = depthSnapshot.height
        val rawDepth = depthSnapshot.smoothDepthMap ?: depthSnapshot.rawDepthMap

        if (rawDepth == null) {
            Log.e("PhantomTracker", "❌ [ERROR] 뎁스 버퍼(raw/smooth)가 모두 null입니다.")
            return null
        }

        // 1. YOLO 박스 영역을 뎁스 맵 인덱스 범위로 변환 (Crop 영역 설정)
        val uStart = (box[1] * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val uEnd = (box[3] * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val vStart = (box[0] * depthHeight).toInt().coerceIn(0, depthHeight - 1)
        val vEnd = (box[2] * depthHeight).toInt().coerceIn(0, depthHeight - 1)

        Log.d("PhantomTracker", "📦 [YOLO BOX RAW] ymin:${box[0]}, xmin:${box[1]}, ymax:${box[2]}, xmax:${box[3]}")
        Log.d("PhantomTracker", "🔍 [BOX SCAN] 영역: U($uStart~$uEnd), V($vStart~$vEnd) | DepthSize:${depthWidth}x${depthHeight}")

        val fov = viewpoint.fieldOfView
        val pointCloud = mutableListOf<FloatArray>()
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var validPointCount = 0

        Log.d("PhantomTracker", "🔍 [BOX SCAN] 영역: U($uStart~$uEnd), V($vStart~$vEnd)")

        // 2. 박스 내부의 모든 픽셀을 순회하며 3D 좌표 추출 (Point Cloud 수집)
        for (vIdx in vStart..vEnd) {
            for (uIdx in uStart..uEnd) {
                val depth = rawDepth.get(vIdx * depthWidth + uIdx)

                if (depth > 0.05f && depth < 3.0f) {
                    val normX = uIdx.toFloat() / depthWidth
                    val normY = vIdx.toFloat() / depthHeight

                    val rayTanX = tan(fov.angleLeft) + normX * (tan(fov.angleRight) - tan(fov.angleLeft))
                    val rayTanY = tan(fov.angleUp) + normY * (tan(fov.angleDown) - tan(fov.angleUp))

                    val vx = rayTanX * depth
                    val vy = rayTanY * depth
                    val vz = -depth

                    val point = floatArrayOf(vx, vy, vz)
                    pointCloud.add(point)

                    sumX += vx
                    sumY += vy
                    sumZ += vz
                    validPointCount++
                }
            }
        }
        
        // UI 시각화를 위해 포인트 리스트 전달
        context.mainExecutor.execute {
            onPointsUpdated?.invoke(pointCloud)
        }

        // 5. 유효한 포인트가 충분할 때만 평균값(Centroid) 반환
        if (validPointCount < 5) { // 최소 5개 이상의 정점이 잡혀야 함
            Log.w("PhantomTracker", "⚠️ 유효 포인트 부족 ($validPointCount 개)")
            return null
        }

        val avgX = sumX / validPointCount
        val avgY = sumY / validPointCount
        val avgZ = sumZ / validPointCount

        Log.i("PhantomTracker", "✅ [CENTROID] $validPointCount 개의 포인트로부터 중심점 산출 성공")
        Log.d("PhantomTracker", "📊 결과: X=${"%.3f".format(avgX)}, Y=${"%.3f".format(avgY)}, Z=${"%.3f".format(avgZ)}")

        return floatArrayOf(avgX, avgY, avgZ)
    }

    fun findDepthCamera(): Boolean = true
    fun startDepthCamera() { }
    fun stopDepthCamera() {
        isAnchorPlaced = false // 리셋
        depthJob?.cancel()
        depthJob = null
    }
}