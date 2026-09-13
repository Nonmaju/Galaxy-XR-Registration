# Galaxy XR Registration

Android XR(Galaxy XR) 앱. Depth Camera와 온디바이스 YOLO 탐지로 실물 오브젝트의 3D 위치를 구하고,
ICP 정합으로 3D 모델을 그 위치에 등록(register)합니다.

## 동작 방식

1. Camera2 API로 카메라 내부 파라미터와 depth map을 가져옴
2. YOLO(TFLite)로 탐지한 바운딩 박스 영역의 depth를 이용해 3D 좌표 계산
3. 계산된 3D 좌표를 중심으로 3D 메시(`phantom_model.glb`)를 배치
4. ICP 알고리즘으로 메시를 실물 위치에 정합
5. Home Space ↔ Full Space 모드 전환 지원

## 사용 기술

`Kotlin` · `Jetpack Compose` · `Android XR (SceneCore)` · `TensorFlow Lite` · `CameraX`

## 빌드

Android Studio에서 프로젝트를 열고 실행하면 됩니다. (minSdk 34, compileSdk 36)

---

<details>
<summary>개발 메모</summary>

- Home-space 모드로 복귀: 풀스페이스 모드로 하면 사용자가 육안으로 보기에 자연스럽지 않음
  → 홈스페이스 모드로 (개발 편의상) 바운딩박스로 뎁스맵 구하고 3D 좌표 확인
  → 이후 풀스페이스 모드로 전환해서 3D mesh를 ICP로 띄움
  → 실제 사용자는 바운딩 박스를 볼 필요가 없으므로, 개발 편의를 위한 임시 단계이고 실제로는 풀스페이스 모드에서 바로 좌표 따고 정합할 예정

</details>
