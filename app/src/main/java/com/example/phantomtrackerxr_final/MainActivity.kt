package com.example.phantomtrackerxr_final

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.width
import com.example.phantomtrackerxr_final.ui.theme.PhantomTrackerXR_FinalTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var depthCameraHelper: DepthCameraHelper
    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) Log.e("PhantomTracker", "Camera permission denied")
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Depth 헬퍼 초기화 및 기긴 내 뎁스 센서 스캔
        depthCameraHelper = DepthCameraHelper(this)
        depthCameraHelper.findDepthCamera()
        depthCameraHelper.startDepthCamera()

        // 권한 확인 및 요청
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }

        setContent {
            PhantomTrackerXR_FinalTheme {
                Subspace {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(800.dp)
                            .height(600.dp)
                            .movable()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreviewWithOverlay(cameraExecutor = cameraExecutor)

                            // 생존 확인용 텍스트
                            Text(
                                text = "🟢 Home Space Mode",
                                color = Color.Green,
                                fontSize = 28.sp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
@Composable
fun CameraPreviewWithOverlay(cameraExecutor: ExecutorService) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val overlayView = androidx.compose.runtime.remember { OverlayView(context, null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 1. 포맷 강제 변환 등 불필요한 옵션을 빼고 가장 가볍게 분석기 세팅
            val imageAnalyzer = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, YoloAnalyzer(context) { resultCoordinates ->
                        overlayView.post { overlayView.updateResults(resultCoordinates) }
                    })
                }

            val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                // ⚠️ 핵심 픽스 1: Subspace가 아닌 실제 Activity의 생명주기를 강제로 가져옴
                val lifecycleOwner = context as ComponentActivity

                // ⚠️ 핵심 픽스 2: 무한 대기를 유발하던 더미 프리뷰를 제거하고 오직 분석기만 바인딩
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalyzer
                )
                Log.d("PhantomTracker", "✅ 카메라 바인딩 완벽 성공!")
            } catch (exc: Exception) {
                Log.e("PhantomTracker", "❌ 카메라 바인딩 실패: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProviderFuture.get().unbindAll()
        }
    }

    AndroidView(
        factory = { _ -> overlayView },
        modifier = Modifier.fillMaxSize()
    )
}