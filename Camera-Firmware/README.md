# Black-dot cap tracking handoff

Corrected app-side versions of the Kotlin files in this handoff folder are integrated under
`app/src/main/java/com/uran/tashtari/camera/` (and the unit test under `app/src/test/...`).
They run on the Android phone against frames from the ESP32-CAM MJPEG stream. They are not
ESP32-S3 firmware and do not run on the camera board.

The app source paths are canonical; the original handoff Kotlin files are kept here as the input
snapshot.

## Detector behavior

- Downscales each frame to 240 pixels wide for analysis.
- Finds dark, neutral connected blobs that are approximately circular and surrounded by bright,
  neutral pixels.
- Auto-acquires only when exactly one candidate is visible for three consecutive frames.
- When multiple candidates appear, tap the desired dot in the camera preview.
- Smooths a selected target and rejects large jumps. After `LOST`, it requires an explicit tap
  before selecting again, so it will not silently switch to another person.
- Reports normalized image offsets, confidence, candidate count, detection age, and processing time.
- Draws candidate and selected-target overlays in the Camera tab.

This is classical image processing. It uses no trained model, OpenCV, or ML Kit. Detection
thresholds are initial values and have not been validated on actual OV3660 footage.

## Current integration boundary

The Camera tab displays and analyzes the MJPEG feed. It does **not** send vision results as
autonomous flight commands. The app's existing BLE protocol only sends manual virtual-mode
roll/pitch/yaw commands with throttle set to zero. A validated Flight-board interface, coordinate
conventions, control behavior, and target-loss failsafe are still required before automatic flight
can be implemented. The current workspace does not include the Flight-board firmware or those
agreed interfaces.

## Test and tuning notes

`DotVisionTest.kt` contains synthetic-frame tests for detection, target acquisition, ambiguity,
loss, and refusing silent target switches. The project already includes JUnit. Run
`gradlew :app:testDebugUnitTest` in an environment with a configured JDK.

Test with recorded overhead camera footage before relying on detection. Include bright and dim
lighting, varying distances, camera motion, occlusion, multiple caps, and frames with no target.
Tune `DetectorConfig` for the actual camera stream and preserve a separate held-out recording set
to estimate false detections and missed detections.
