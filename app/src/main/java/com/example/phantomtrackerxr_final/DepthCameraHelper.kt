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

            Log.d("PhantomTracker", "해상도 선택: ${bestSize.width} x ${bestSize.height}")

            // 2. ImageReader 초기화 (바구니 준비)
            imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.DEPTH16, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    // 🎉 여기서 드디어 뎁스(Z) 데이터가 들어옵니다!
                    // 나중에 YOLO 좌표와 결합하여 특정 픽셀의 깊이를 추출할 곳입니다.
                    Log.v("PhantomTracker", "뎁스 프레임 수신 중!")
                    image.close() // 메모리 누수 방지를 위해 반드시 닫아줌
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
}