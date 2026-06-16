package com.example.phantomtrackerxr_final

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.util.Log

class DepthCameraHelper(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // 찾은 뎁스 카메라의 ID를 저장할 변수
    var depthCameraId: String? = null
        private set

    // 🌟 추가: 카메라 장치와 데이터를 받을 ImageReader 변수
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    // 🌟 추가: 카메라 캡처 세션을 관리할 변수
    private var captureSession: CameraCaptureSession? = null
    // 🌟 새로 추가: YOLO가 찾은 최신 바운딩 박스를 실시간으로 저장할 변수
    var latestYoloBox: FloatArray? = null
    // 🌟 추가: 중심점이 계산되었을 때 MainActivity로 값을 전달할 콜백 함수
    var onCentroidCalculated: ((FloatArray) -> Unit)? = null

    // 🌟 추가: 뎁스맵 해상도 및 3D 계산을 위한 내부 파라미터(Intrinsics)
    var depthWidth: Int = 0
        private set
    var depthHeight: Int = 0
        private set
    var fx: Float = 0f
    var fy: Float = 0f
    var cx: Float = 0f
    var cy: Float = 0f
    /**
     * 기기에 장착된 카메라 중 Depth(ToF) 출력을 지원하는 렌즈의 ID를 찾습니다.
     */
    fun findDepthCamera(): Boolean {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

                // 해당 카메라 렌즈가 DEPTH_OUTPUT(뎁스 데이터 출력)을 지원하는지 확인
                val supportsDepth = capabilities?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                ) == true

                if (supportsDepth) {
                    depthCameraId = cameraId
                    Log.d("PhantomTracker", "✅ 뎁스 카메라 발견! ID: $cameraId")
                    return true
                }
            }
            Log.w("PhantomTracker", "⚠️ 뎁스 카메라를 찾을 수 없습니다. (에뮬레이터 또는 미지원 기기)")
            return false
        } catch (e: Exception) {
            Log.e("PhantomTracker", "뎁스 카메라 검색 중 에러 발생: ${e.message}")
            return false
        }
    }

    /**
     * 🌟 추가: 뎁스 카메라를 열고 ImageReader를 세팅합니다.
     */
    @SuppressLint("MissingPermission") // 권한은 MainActivity에서 이미 체크함
    fun startDepthCamera() {
        if (depthCameraId == null) {
            Log.w("PhantomTracker", "⚠️ 뎁스 카메라 ID가 없습니다. 실행을 건너뜁니다.")
            return
        }

        try {
            // 1. 카메라 특성 확인 및 최적의 뎁스 해상도 가져오기
            val characteristics = cameraManager.getCameraCharacteristics(depthCameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // DEPTH16 포맷의 출력 사이즈 목록을 가져옵니다.
            val depthSizes = map?.getOutputSizes(ImageFormat.DEPTH16)
            val bestSize = depthSizes?.firstOrNull() ?: android.util.Size(640, 480)

            // 🌟 1. 해상도 저장
            depthWidth = bestSize.width
            depthHeight = bestSize.height
            Log.d("PhantomTracker", "해상도 선택: ${bestSize.width} x ${bestSize.height}")

            // 🌟 2. 내부 파라미터(Intrinsic) 추출 및 저장
            val intrinsics = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (intrinsics != null && intrinsics.size >= 5) {
                fx = intrinsics[0]
                fy = intrinsics[1]
                cx = intrinsics[2]
                cy = intrinsics[3]
                Log.d("PhantomTracker", "✅ 뎁스 렌즈 파라미터 로드: fx=$fx, fy=$fy, cx=$cx, cy=$cy")
            } else {
                // 에뮬레이터나 하드웨어 미지원 기기를 위한 방어 로직 (근사치 사용)
                fx = depthWidth * 0.8f
                fy = depthHeight * 0.8f
                cx = depthWidth / 2f
                cy = depthHeight / 2f
                Log.w("PhantomTracker", "⚠️ 내부 파라미터 미지원. 근사치(해상도 기반)를 사용합니다.")
            }

            // 2. ImageReader 초기화 (바구니 준비)
            imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.DEPTH16, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        // 🌟 임시 하드코딩 삭제하고, MainActivity에서 넘어온 최신 YOLO 좌표 가져오기
                        val currentYoloBox = latestYoloBox

                        // 만약 YOLO가 아직 팬텀을 못 찾아서 좌표가 없으면 이번 뎁스 프레임은 무시하고 패스
                        if (currentYoloBox == null) {
                            return@setOnImageAvailableListener
                        }

                        // 1. YOLO 바운딩 박스를 뎁스 해상도(ROI) 픽셀로 변환
                        val roiIntArray = mapRgbBoxToDepthRoi(currentYoloBox)

                        // 2. 뎁스 이미지와 ROI를 던져서 원본 3D Point Cloud 추출
                        val rawPoints = extractPointCloud(image, roiIntArray)

                        // 3. 튀는 쓰레기 값(노이즈) 제거
                        val filteredPoints = filterPointCloud(rawPoints)

                        // 4. 살아남은 점들의 3D 중심점(Centroid) 계산
                        val centroid = calculateCentroid(filteredPoints)

                        // 5. 중심점이 성공적으로 구해졌다면 로그 출력 및 UI로 전달
                        if (centroid != null) {
                            Log.d("PhantomTracker", "🎯 팬텀 중심점 자동 발견: X=${centroid[0]}, Y=${centroid[1]}, Z=${centroid[2]}")

                            // 🌟 추가: UI 스레드로 3D 좌표 전달
                            // Compose 상태 업데이트를 위해 메인 스레드에서 실행되도록 합니다.
                            context.mainExecutor.execute {
                                onCentroidCalculated?.invoke(centroid)
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("PhantomTracker", "파이프라인 처리 중 에러: ${e.message}")
                    } finally {
                        image.close()
                    }
                }
            }, null)

            // 3. 카메라 장치 열기
            cameraManager.openCamera(depthCameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d("PhantomTracker", "✅ 뎁스 카메라 Open 성공!")
                    // 🌟 추가: 카메라가 열리면 프레임 요청 세션을 시작합니다.
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e("PhantomTracker", "❌ 뎁스 카메라 Open 에러: $error")
                }
            }, null)

        } catch (e: Exception) {
            Log.e("PhantomTracker", "뎁스 카메라 시작 중 에러: ${e.message}")
        }
    }

    // 🌟 새로 추가: 캡처 세션을 만들고 반복 요청을 보내는 함수
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val surface = imageReader?.surface ?: return

        try {
            // 1. 프레임을 계속 받을 Request Builder 생성 (PREVIEW 템플릿 사용)
            val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            // 2. 세션 설정 및 생성 (Target SDK 34 이상에 맞춘 최신 SessionConfiguration 방식)
            val outputConfiguration = OutputConfiguration(surface)
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfiguration),
                context.mainExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // 3. 카메라에게 "계속해서(Repeating) 프레임을 보내줘!" 라고 요청
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                            Log.d("PhantomTracker", "✅ 뎁스 카메라 세션 연결 및 스트리밍 시작!")
                        } catch (e: Exception) {
                            Log.e("PhantomTracker", "반복 요청 세팅 실패: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("PhantomTracker", "❌ 뎁스 카메라 세션 구성 실패")
                    }
                }
            )

            device.createCaptureSession(sessionConfiguration)

        } catch (e: Exception) {
            Log.e("PhantomTracker", "세션 생성 중 에러: ${e.message}")
        }
    }

    /**
     * 🌟 새로 추가: YOLO의 2D 바운딩 박스를 뎁스맵의 실제 픽셀 좌표(ROI)로 변환합니다.
     * @param yoloBox [top, left, bottom, right] (YoloAnalyzer에서 추출한 0.0 ~ 1.0 비율)
     * @return [depthTop, depthLeft, depthBottom, depthRight] (뎁스맵 픽셀 단위)
     */
    fun mapRgbBoxToDepthRoi(yoloBox: FloatArray): IntArray {
        if (depthWidth == 0 || depthHeight == 0) return intArrayOf(0, 0, 0, 0)

        // RGB와 Depth 카메라의 물리적 단차(Translation)가 기기 내에서 보정되어 있다고 가정하고,
        // 정규화된 좌표를 뎁스 해상도에 맞춰 스케일링(Scaling)합니다.
        val top = (yoloBox[0] * depthHeight).toInt().coerceIn(0, depthHeight - 1)
        val left = (yoloBox[1] * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val bottom = (yoloBox[2] * depthHeight).toInt().coerceIn(0, depthHeight - 1)
        val right = (yoloBox[3] * depthWidth).toInt().coerceIn(0, depthWidth - 1)

        return intArrayOf(top, left, bottom, right)
    }

    /**
     * 🌟 새로 추가: DEPTH16 이미지와 ROI를 받아 3D Point Cloud(밀리미터 단위)를 추출합니다.
     * @param depthImage Camera2에서 받아온 DEPTH16 포맷의 이미지
     * @param roi [top, left, bottom, right] 뎁스맵 기준의 픽셀 좌표
     * @return 3D 좌표 배열의 리스트 [ [x, y, z], [x, y, z], ... ]
     */
    fun extractPointCloud(depthImage: android.media.Image, roi: IntArray): List<FloatArray> {
        val pointCloud = mutableListOf<FloatArray>()

        val top = roi[0]
        val left = roi[1]
        val bottom = roi[2]
        val right = roi[3]

        // ROI 유효성 검사
        if (top >= bottom || left >= right) return pointCloud
        if (fx == 0f || fy == 0f) return pointCloud // 내부 파라미터가 없으면 계산 불가

        val plane = depthImage.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // DEPTH16 포맷은 1픽셀당 2바이트(16비트)

        // ROI 영역만 순회하며 Z값을 뽑아냅니다.
        for (v in top..bottom) {
            for (u in left..right) {
                // ⚠️ 핵심: 2차원 좌표(u, v)를 1차원 버퍼 인덱스로 변환 (Stride 고려 필수!)
                val index = (v * rowStride) + (u * pixelStride)

                // 버퍼 초과 접근 방지 (안전 장치)
                if (index + 1 >= buffer.capacity()) continue

                // DEPTH16은 Little Endian 방식으로 2바이트를 읽어옵니다.
                val byte1 = buffer.get(index).toInt() and 0xFF
                val byte2 = buffer.get(index + 1).toInt() and 0xFF
                val zRaw = (byte2 shl 8) or byte1

                // 일부 ToF 센서는 상위 3비트를 신뢰도(Confidence) 값으로 쓰기 때문에 하위 13비트만 가져옵니다.
                val z = (zRaw and 0x1FFF).toFloat()

                // 노이즈 필터링 1차: 유효한 깊이 범위만 취급 (예: 10cm ~ 1.5m 사이의 데이터만)
                // 메디컬 환경(수술대 등)을 고려하여 팬텀이 있을 법한 거리만 남깁니다.
                if (z > 100f && z < 1500f) {
                    // 핀홀 카메라 공식을 적용하여 X, Y 좌표 계산
                    val x = ((u - cx) * z) / fx
                    val y = ((v - cy) * z) / fy

                    pointCloud.add(floatArrayOf(x, y, z))
                }
            }
        }
        return pointCloud
    }

    /**
     * 🌟 새로 추가: 노이즈 필터링 (Z값 기준 표준편차 활용)
     * @param rawPoints 필터링 전의 원본 3D 포인트 리스트
     * @return 튀는 값(Outlier)이 제거된 깔끔한 포인트 리스트
     */
    fun filterPointCloud(rawPoints: List<FloatArray>): List<FloatArray> {
        if (rawPoints.isEmpty()) return emptyList()

        // 1. Z값(깊이) 평균 계산
        val zAverage = rawPoints.map { it[2] }.average().toFloat()

        // 2. Z값 표준편차(Standard Deviation) 계산
        val variance = rawPoints.map { Math.pow((it[2] - zAverage).toDouble(), 2.0) }.average()
        val stdDev = Math.sqrt(variance).toFloat()

        // 3. 통계적 필터링: 평균에서 1.5 표준편차 이상 벗어난 값은 노이즈로 보고 제거
        // (1.5 배율은 환경에 따라 조절 가능. 타이트하게 잡으려면 1.0, 넉넉하게 잡으려면 2.0)
        val threshold = 1.5f * stdDev
        return rawPoints.filter { Math.abs(it[2] - zAverage) <= threshold }
    }

    /**
     * 🌟 새로 추가: 필터링된 포인트 클라우드의 3D 중심점(Centroid) 계산
     * @param points 노이즈가 제거된 포인트 리스트
     * @return 중심점 좌표 [X_mean, Y_mean, Z_mean] (계산 불가 시 null 반환)
     */
    fun calculateCentroid(points: List<FloatArray>): FloatArray? {
        if (points.isEmpty()) return null

        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f

        for (p in points) {
            sumX += p[0]
            sumY += p[1]
            sumZ += p[2]
        }

        val count = points.size.toFloat()
        return floatArrayOf(sumX / count, sumY / count, sumZ / count)
    }
}