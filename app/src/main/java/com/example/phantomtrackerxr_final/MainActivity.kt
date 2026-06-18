package com.example.phantomtrackerxr_final

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
            var yoloResult by remember { mutableStateOf(floatArrayOf(-1f, -1f, -1f, -1f, -1f, -1f, 0f)) }
            var phantomPosition by remember { mutableStateOf<FloatArray?>(null) }
            var pointCloud by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
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
                    onResultUpdated = { res ->
                        yoloResult = res
                        latency = if (res.size >= 7) res[6].toLong() else 0L
                        isDetected = (res[0] != -1f)
                    }
                )

                /* 🌟 2) YOLO 바운딩 박스 시각화 (2D Overlay)
                if (yoloResult[0] != -1f) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val screenWidth = this.maxWidth
                        val screenHeight = this.maxHeight
                        val res = yoloResult
                        
                        // YOLO 출력: [ymin, xmin, ymax, xmax] (0~1 정규화 가정)
                        val top = res[0] * screenHeight.value
                        val left = res[1] * screenWidth.value
                        val bottom = res[2] * screenHeight.value
                        val right = res[3] * screenWidth.value

                        Box(
                            modifier = Modifier
                                .offset(x = left.dp, y = top.dp)
                                .size(width = (right - left).dp, height = (bottom - top).dp)
                                .border(3.dp, Color.Red, RoundedCornerShape(4.dp))
                        )
                    }
                }
                */

                DisposableEffect(Unit) {
                    depthCameraHelper.onCentroidCalculated = { centroid ->
                        if (anchorEntity == null) phantomPosition = centroid
                    }
                    depthCameraHelper.onPointsUpdated = { points ->
                        pointCloud = points
                    }
                    onDispose { 
                        depthCameraHelper.onCentroidCalculated = null 
                        depthCameraHelper.onPointsUpdated = null
                    }
                }

                Subspace {
                    // 1. 왼쪽 성능 HUD (생략)
                    
                    // 2. 포인트 클라우드 시각화 패널 (2배 확대: 600x600)
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(600.dp)
                            .height(600.dp)
                            .offset(x = 0.dp, y = (-300).dp, z = (-700).dp)
                            .movable()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("☁️ Point Cloud View", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                PointCloudVisualizer(points = pointCloud)
                            }
                        }
                    }

                    // 3. 기존 HUD 및 좌표 표시 UI (offset 유지하며 뒤로 밀기)
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
                                text = "Latency: ${latency}ms",
                                color = Color.LightGray,
                                fontSize = 20.sp,
                                textAlign = TextAlign.Center
                            )
                            // 좌표 데이터 HUD에 표시
                            if (yoloResult[0] != -1f) {
                                Text(
                                    text = "Box: [${"%.2f".format(yoloResult[1])}, ${"%.2f".format(yoloResult[0])}]",
                                    color = Color.Yellow,
                                    fontSize = 16.sp
                                )
                            }
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
                                    .background(Color(0xFFE91E63).copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🎯 PHANTOM CORE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                                    Text("X: ${"%.3f".format(pos[0])} Y: ${"%.3f".format(pos[1])} Z: ${"%.3f".format(pos[2])}", color = Color.White, fontSize = 18.sp)
                                }
                            }
                        }
                    }

                    // 5) 앵커 기반 메시 배치
                    if (phantomPosition != null && session != null && anchorEntity == null) {
                        val pos = phantomPosition!!
                        PlacedEntityBasedOnAnchor(
                            xrSession = session,
                            x = pos[0], y = pos[1], z = pos[2],
                            onAnchorCreated = { anchorEntity = it }
                        )
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
fun PointCloudVisualizer(points: List<FloatArray>) {
    Canvas(modifier = Modifier.fillMaxSize().background(Color.DarkGray.copy(alpha = 0.3f))) {
        val center = size / 2f
        // 배율을 다시 원래대로(150f) 복구
        val scale = 150f 

        points.forEach { p ->
            // 정면 뷰 (X, Y) 투영
            val drawX = center.width + (p[0] * scale)
            val drawY = center.height - (p[1] * scale)
            
            // 거리에 따라 색상 변경 (가까울수록 노란색, 멀수록 보라색)
            val zNorm = (p[2].coerceIn(-3f, -0.1f) + 3f) / 2.9f
            val color = androidx.compose.ui.graphics.lerp(Color.Yellow, Color.Magenta, 1f - zNorm)

            drawCircle(
                color = color,
                radius = 2.5f,
                center = androidx.compose.ui.geometry.Offset(drawX, drawY)
            )
        }
    }
}

@Composable
fun CameraTracker(
    executor: ExecutorService,
    helper: DepthCameraHelper,
    session: androidx.xr.runtime.Session?,
    onResultUpdated: (FloatArray) -> Unit
) {
    val context = LocalContext.current
    val previewView = remember { androidx.camera.view.PreviewView(context) }

    Box(modifier = Modifier.size(1.dp).background(Color.Transparent)) {
        AndroidView(factory = { previewView })
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
                            onResultUpdated(res)
                            if (res[0] != -1f && !helper.isAnchorPlaced && session != null) {
                                helper.latestYoloBox = res
                                helper.startDepthStream(session)
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

@Composable
fun PlacedEntityBasedOnAnchor(
    xrSession: androidx.xr.runtime.Session,
    x: Float,
    y: Float,
    z: Float,
    onAnchorCreated: (AnchorEntity) -> Unit
) {
    var modelEntity by remember { mutableStateOf<GltfModelEntity?>(null) }
    var anchorEntity by remember { mutableStateOf<AnchorEntity?>(null) }

    Log.i("PhantomTracker", "추출된 좌표: $x, $y, $z")

    LaunchedEffect(Unit) {
        try {
            // 1. 모델 로드
            val model = GltfModel.create(xrSession, Paths.get("phantom_model.glb"))
            val gEntity = GltfModelEntity.create(xrSession, model)
            gEntity.setScale(0.2f)

            // 2. Local -> World 변환 (Viewpoint Pose 적용)
            val viewpoint = RenderViewpoint.left(xrSession) ?: return@LaunchedEffect
            val cameraPose = viewpoint.state.value.pose
            val worldPos = cameraPose.translation + cameraPose.rotation * Vector3(x, y, z)

            // 3. Anchor 생성
            val targetPose = Pose(translation = worldPos)
            val anchorResult = Anchor.create(xrSession, targetPose)

            if (anchorResult is AnchorCreateSuccess) {
                val aEntity = AnchorEntity.create(xrSession, anchorResult.anchor)
                gEntity.parent = aEntity

                anchorEntity = aEntity
                modelEntity = gEntity
                onAnchorCreated(aEntity)
                Log.i("PhantomTracker", "앵커 생성 및 모델 부착 성공!")
            } else {
                Log.e("PhantomTracker", "앵커 생성 실패: $anchorResult")
            }
        } catch (e: Exception) {
            Log.e("PhantomTracker", "Placement Error", e)
        }
    }
}