# Galaxy XR Registration

An Android XR (Galaxy XR) app that finds a real-world object's 3D position using the depth camera
and an on-device YOLO detector, then registers a 3D mesh onto that position via ICP alignment.

## How it works

1. Get camera intrinsics and a depth map via the Camera2 API
2. Compute 3D coordinates from the depth inside the YOLO (TFLite) bounding box
3. Place a 3D mesh (`phantom_model.glb`) at the computed center point
4. Align the mesh to the real object's pose using ICP
5. Supports switching between Home Space and Full Space modes

## Tech Stack

`Kotlin` · `Jetpack Compose` · `Android XR (SceneCore)` · `TensorFlow Lite` · `CameraX`

## Build

Open the project in Android Studio and run it. (minSdk 34, compileSdk 36)

---

<details>
<summary>Dev notes</summary>

- Returning to Home Space mode: Full Space mode doesn't look natural to the user visually.
  → For now (dev convenience), use Home Space to get the depth map from the bounding box and check the 3D coordinates.
  → Then switch to Full Space mode and place the 3D mesh via ICP.
  → The end user never needs to see the bounding box, so this is a temporary dev-only step — eventually coordinates will be captured and aligned directly in Full Space mode.

</details>
