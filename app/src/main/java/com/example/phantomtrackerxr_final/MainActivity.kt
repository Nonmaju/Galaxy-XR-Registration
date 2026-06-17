package com.example.phantomtrackerxr_final

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.platform.LocalSession
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.arcore.RenderViewpoint
import androidx.xr.scenecore.scene
import androidx.xr.runtime.Config
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorEntity

import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var depthCameraHelper: DepthCameraHelper
    
    private val requiredPermissions = arrayOf(
        android.Manifest.permission.CAMERA,
        "android.permission.HAND_TRACKING",
        "android.permission.HEAD_TRACKING",
        "android.permission.SCENE_UNDERSTANDING_COARSE",
        "android.permission.SCENE_UNDERSTANDING_FINE"
    )

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) Log.d("PhantomTracker", "✅ XR 권한 승인 완료")
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.setBackgroundDrawableResource(android.R.color.transparent)

        cameraExecutor = Executors.newSingleThreadExecutor()
        depthCameraHelper = DepthCameraHelper(this)
        depthCameraHelper.startBackgroundThread()

        if (requiredPermissions.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(requiredPermissions)
        }

        setContent {
            val session = LocalSession.current
            
            var latency by remember { mutableLongStateOf(0L) }
            var isDetected by remember { mutableStateOf(false) }
            var debugInfo by remember { mutableStateOf("Ready to scan") }
            var phantomPosition by remember { mutableStateOf<FloatArray?>(null) }
            var anchorEntity by remember { mutableStateOf<AnchorEntity?>(null) }

            LaunchedEffect(session) {
                if (session != null) {
                    try {
                        session.scene.requestFullSpaceMode()
                        val config = session.config.copy(
                            depthEstimation = Config.DepthEstimationMode.RAW_ONLY,
                            deviceTracking = Config.DeviceTrackingMode.LAST_KNOWN
                        )
                        session.configure(config)
                    } catch (e: Exception) { Log.e("PhantomTracker", "Session Config Error", e) }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                CameraTracker(cameraExecutor, depthCameraHelper, session, 
                    onMetricsUpdated = { currentLatency, status ->
                        latency = currentLatency
                        isDetected = (status == "Detected!")
                    },
                    onDebugInfoUpdated = { info -> debugInfo = info }
                )

                DisposableEffect(Unit) {
                    depthCameraHelper.onCentroidCalculated = { centroid ->
                        if (anchorEntity == null) phantomPosition = centroid
                    }
                    onDispose { depthCameraHelper.onCentroidCalculated = null }
                }

                Subspace {
                    // 1. 왼쪽 성능 HUD (글씨 키우고 중앙 정렬)
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(400.dp)
                            .height(250.dp)
                            .offset(x = (-400).dp, z = (-800).dp)
                            .movable()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isDetected) "🟢 Status: Detected!" else "🔍 Status: Searching...",
                                color = if (isDetected) Color.Green else Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Latency: ${latency}ms | FPS: ${if (latency > 0) 1000/latency else 0}",
                                color = Color.LightGray,
                                fontSize = 20.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = debugInfo,
                                color = Color.Cyan,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // 2. 3D 중앙 좌표 표시 UI (원형 뱃지 스타일)
                    if (phantomPosition != null) {
                        val pos = phantomPosition!!
                        SpatialPanel(
                            modifier = SubspaceModifier
                                .width(350.dp)
                                .height(180.dp)
                                .offset(x = 400.dp, z = (-800).dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFFE91E63).copy(alpha = 0.8f), RoundedCornerShape(100.dp)) // 핑크색 라운드
                                    .border(4.dp, Color.White, RoundedCornerShape(100.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "🎯 PHANTOM CORE",
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "X: ${"%.3f".format(pos[0])}m",
                                        color = Color.White,
                                        fontSize = 18.sp
                                    )
                                    Text(
                                        "Y: ${"%.3f".format(pos[1])}m",
                                        color = Color.White,
                                        fontSize = 18.sp
                                    )
                                    Text(
                                        "Z: ${"%.3f".format(pos[2])}m",
                                        color = Color.White,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }
                    }

                    LaunchedEffect(phantomPosition) {
                        val currentPos = phantomPosition
                        val currentSession = session
                        if (currentPos != null && currentSession != null && anchorEntity == null) {
                            try {
                                // 1. 현재 카메라 Pose를 원점 기준으로 가져옴
                                val viewpoint = RenderViewpoint.left(currentSession) ?: return@LaunchedEffect
                                val cameraPose = viewpoint.state.value.pose
                                
                                // 2. 카메라 기준 로컬 좌표 -> 월드(세션 원점) 좌표로 변환
                                val worldPos = cameraPose.translation + cameraPose.rotation * Vector3(currentPos[0], currentPos[1], currentPos[2])
                                
                                // 3. 3D 모델 로드 및 엔티티 생성 
                                val model = GltfModel.create(currentSession, java.nio.file.Paths.get("phantom_model.glb"))
                                val gEntity = GltfModelEntity.create(currentSession, model)
                                gEntity.setScale(0.5f) // 모델 크기 조정

                                // 4. 계산된 위치에 앵커 생성 및 모델 부착
                                val anchorResult = Anchor.create(currentSession, Pose(translation = worldPos))
                                if (anchorResult is AnchorCreateSuccess) {
                                    val aEntity = AnchorEntity.create(currentSession, anchorResult.anchor)
                                    gEntity.parent = aEntity // 앵커 엔티티의 자식으로 모델 추가
                                    anchorEntity = aEntity
                                    
                                    depthCameraHelper.isAnchorPlaced = true
                                    depthCameraHelper.stopDepthCamera()
                                    Log.d("PhantomTracker", "✅ Phantom Mesh Placed at: $worldPos")
                                }
                            } catch (e: Exception) { Log.e("PhantomTracker", "Placement Error", e) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        depthCameraHelper.stopDepthCamera()
        depthCameraHelper.stopBackgroundThread()
    }
}

@Composable
fun CameraTracker(
    executor: ExecutorService, 
    helper: DepthCameraHelper, 
    session: androidx.xr.runtime.Session?,
    onMetricsUpdated: (Long, String) -> Unit,
    onDebugInfoUpdated: (String) -> Unit
) {
    val context = LocalContext.current
    val frameLayout = remember { android.widget.FrameLayout(context) }
    val previewView = remember { androidx.camera.view.PreviewView(context) }
    val overlayView = remember { OverlayView(context, null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { 
                frameLayout.apply {
                    addView(previewView)
                    addView(overlayView)
                }
            }
        )
    }

    DisposableEffect(Unit) {
        val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = androidx.camera.core.Preview.Builder().build().also { 
                    it.setSurfaceProvider(previewView.surfaceProvider) 
                }
                val analyzer = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor, YoloAnalyzer(context) { res ->
                            val latency = if (res.size >= 7) res[6].toLong() else 0L
                            val status = if (helper.isAnchorPlaced) "Detected!" else if (res[0] != -1f) "Depth Sync..." else "Searching..."
                            onMetricsUpdated(latency, status)

                            // OverlayView 직접 갱신 (Compose 상태 변화를 거치지 않아 연산량 감소)
                            overlayView.updateResults(res)

                            if (res[0] != -1f) {
                                // ymin, xmin, ymax, xmax 4개 좌표 모두 표시
                                onDebugInfoUpdated("Box: [${"%.2f".format(res[1])}, ${"%.2f".format(res[0])}, ${"%.2f".format(res[3])}, ${"%.2f".format(res[2])}]")
                                if (!helper.isAnchorPlaced && session != null) {
                                    helper.latestYoloBox = res
                                    helper.startDepthStream(session)
                                }
                            } else {
                                onDebugInfoUpdated("Searching for Phantom...")
                            }
                        })
                    }
                provider.unbindAll()
                provider.bindToLifecycle(context as androidx.lifecycle.LifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            } catch (e: Exception) { Log.e("PhantomTracker", "Camera Bind Error", e) }
        }, ContextCompat.getMainExecutor(context))
        onDispose { providerFuture.get().unbindAll() }
    }
}