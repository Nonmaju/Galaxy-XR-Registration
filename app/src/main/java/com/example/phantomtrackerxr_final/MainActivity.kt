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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

// XR 기본 레이아웃 Import
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.layout.offset

// Alpha09 버전용 Scenecore Import
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity

import com.example.phantomtrackerxr_final.ui.theme.PhantomTrackerXR_FinalTheme
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorEntity

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

        // Depth 헬퍼 초기화 및 기기 내 뎁스 센서 스캔
        depthCameraHelper = DepthCameraHelper(this)
        depthCameraHelper.findDepthCamera()
        depthCameraHelper.startDepthCamera()

        // 권한 확인
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }

        setContent {
            PhantomTrackerXR_FinalTheme {
                // 1. 팬텀의 3D 좌표를 저장할 상태 변수
                var phantomPosition by remember { mutableStateOf<FloatArray?>(null) }

                // 2. Helper에서 좌표를 계산할 때마다 업데이트
                DisposableEffect(Unit) {
                    depthCameraHelper.onCentroidCalculated = { centroid ->
                        phantomPosition = centroid
                    }
                    onDispose { depthCameraHelper.onCentroidCalculated = null }
                }

                val session = LocalSession.current
                var loadedGltfModel by remember { mutableStateOf<GltfModel?>(null) }

                // GltfModel.create는 suspend 함수이므로 안전한 비동기 코루틴(LaunchedEffect) 안에서 로딩합니다!
                LaunchedEffect(session) {
                    if (session != null) {
                        try {
                            // alpha05+ 에서는 Session과 Path를 사용
                            loadedGltfModel = GltfModel.create(session, Paths.get("phantom_model.glb"))
                            Log.d("PhantomTracker", "✅ 3D 모델 로딩 완료!")
                        } catch (e: Exception) {
                            Log.e("PhantomTracker", "❌ 모델 로딩 실패: ${e.message}")
                        }
                    }
                }

                Subspace {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(800.dp)
                            .height(600.dp)
                            .movable()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreviewWithOverlay(
                                cameraExecutor = cameraExecutor,
                                depthCameraHelper = depthCameraHelper
                            )

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

                    // 위치 데이터도 있고, 비동기 모델 로딩도 끝났을 때만 화면에 띄웁니다!
                    if (phantomPosition != null && session != null) {
                        val pos = phantomPosition!!
                        val posX = pos[0] / 1000f
                        val posY = pos[1] / 1000f
                        val posZ = pos[2] / 1000f // mm를 m로 변환

                        // Anchor와 Model Entity 상태 관리
                        var modelEntity by remember { mutableStateOf<GltfModelEntity?>(null) }
                        var anchorEntity by remember { mutableStateOf<AnchorEntity?>(null) }

                        LaunchedEffect(phantomPosition) {
                            try {
                                // 1. Alpha 07 방식의 깔끔한 모델 로드 (동기 코드 참고)
                                val model = GltfModel.create(session, Paths.get("phantom_model.glb"))
                                val gEntity = GltfModelEntity.create(session, model)

                                // 크기가 너무 크면 조절 (동기 코드는 0.04f로 축소했음)
                                gEntity.setScale(Vector3(1f, 1f, 1f))

                                // 2. 핵심 비법: 현실 세계의 (x,y,z) 좌표에 Anchor 생성
                                val targetPose = Pose(translation = Vector3(posX, posY, posZ))
                                val anchorResult = Anchor.create(session, targetPose)

                                if (anchorResult is AnchorCreateSuccess) {
                                    val validAnchor = anchorResult.anchor
                                    val aEntity = AnchorEntity.create(session, validAnchor)

                                    // 3. 모델을 Anchor의 자식(parent)으로 설정하여 현실 공간에 고정!
                                    gEntity.parent = aEntity

                                    anchorEntity = aEntity
                                    modelEntity = gEntity
                                    Log.d("PhantomTracker", "✅ 현실 세계 Anchor 생성 및 모델 부착 성공!")
                                } else {
                                    Log.e("PhantomTracker", "❌ Anchor 생성 실패")
                                }
                            } catch (e: Exception) {
                                Log.e("PhantomTracker", "3D 렌더링 에러: ${e.message}")
                            }
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
fun CameraPreviewWithOverlay(cameraExecutor: ExecutorService, depthCameraHelper: DepthCameraHelper) {
    val context = LocalContext.current
    val overlayView = remember { OverlayView(context, null) }

    DisposableEffect(Unit) {
        val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, YoloAnalyzer(context) { resultCoordinates ->
                        overlayView.post { overlayView.updateResults(resultCoordinates) }

                        // 뎁스 계산을 위해 최신 바운딩 박스 좌표 전달
                        if (resultCoordinates.isNotEmpty() && resultCoordinates[0] != -1f) {
                            depthCameraHelper.latestYoloBox = resultCoordinates
                        } else {
                            depthCameraHelper.latestYoloBox = null
                        }
                    })
                }

            val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val lifecycleOwner = context as ComponentActivity
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalyzer)
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