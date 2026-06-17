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

    private var depthJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun startBackgroundThread() { }
    fun stopBackgroundThread() { depthJob?.cancel() }

    @SuppressLint("RestrictedApi")
    fun startDepthStream(session: Session) {
        if (isAnchorPlaced || depthJob != null) return
        Log.d("PhantomTracker", "🚀 뎁스 스트림 수집 시작")

        depthJob = scope.launch {
            val depthMapInstance = DepthMap.left(session)
            val viewpointInstance = RenderViewpoint.left(session)

            if (depthMapInstance == null || viewpointInstance == null) {
                Log.e("PhantomTracker", "❌ DepthMap 또는 Viewpoint 인스턴스를 가져올 수 없습니다.")
                return@launch
            }

            depthMapInstance.state.collect { state ->
                val currentYoloBox = latestYoloBox
                if (currentYoloBox != null && !isAnchorPlaced) {
                    val viewpoint = viewpointInstance.state.value
                    
                    // YOLO Box의 중심점 계산 (정규화 좌표 0~1)
                    val centerX = (currentYoloBox[1] + currentYoloBox[3]) / 2f
                    val centerY = (currentYoloBox[0] + currentYoloBox[2]) / 2f

                    val centroid = projectPixelTo3D(centerX, centerY, state, viewpoint)

                    if (centroid != null) {
                        Log.d("PhantomTracker", "🎯 중심점 확정: ${centroid.joinToString()}")
                        isAnchorPlaced = true
                        context.mainExecutor.execute {
                            onCentroidCalculated?.invoke(centroid)
                        }
                        // 🌟 찾았으면 즉시 코루틴 종료
                        depthJob?.cancel()
                        depthJob = null
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun projectPixelTo3D(
        normX: Float,
        normY: Float,
        depthSnapshot: DepthMap.State,
        viewpoint: RenderViewpoint.State
    ): FloatArray? {
        val depthWidth = depthSnapshot.width
        val depthHeight = depthSnapshot.height
        val rawDepth = depthSnapshot.rawDepthMap ?: return null

        // 1. NDC 좌표 변환 (-1 ~ 1)
        val ndcX = normX * 2f - 1f
        val ndcY = 1f - normY * 2f

        // 2. 동기분 코드의 FOV 매핑 방식 적용
        val fov = viewpoint.fieldOfView
        val tanLeft = tan(fov.angleLeft)
        val tanRight = tan(fov.angleRight)
        val tanUp = tan(fov.angleUp)
        val tanDown = tan(fov.angleDown)

        // RGB 카메라의 수동 FOV 가정 (동기분 코드 참고)
        val rgbHalfFovH = Math.toRadians(75.0).toFloat() / 2f
        val rgbHalfFovV = Math.toRadians(60.0).toFloat() / 2f
        val tanAngleX = ndcX * tan(rgbHalfFovH)
        val tanAngleY = ndcY * tan(rgbHalfFovV)

        // 뎁스 맵 인덱스로 매핑 (비대칭 FOV 보정)
        val u = (((tanAngleX - tanLeft) / (tanRight - tanLeft)) * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val v = (((tanUp - tanAngleY) / (tanUp - tanDown)) * depthHeight).toInt().coerceIn(0, depthHeight - 1)

        val depth = rawDepth.get(v * depthWidth + u)
        
        // 디버깅용 로그 (너무 많이 찍히면 주석 처리)
        // Log.d("PhantomTracker", "Mapping: ndcX=$ndcX, u=$u, v=$v, depth=$depth")

        if (depth <= 0.1f || depth > 5.0f) return null // 유효 거리 10cm ~ 5m

        // 3. 로컬 3D 좌표 계산 (XR은 앞방향이 -Z)
        val vx = tanAngleX * depth
        val vy = tanAngleY * depth
        val vz = -depth 

        return floatArrayOf(vx, vy, vz)
    }

    fun findDepthCamera(): Boolean = true
    fun startDepthCamera() { }
    fun stopDepthCamera() { depthJob?.cancel(); depthJob = null }
}