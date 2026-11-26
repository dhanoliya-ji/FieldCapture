# Engineering Challenges & Solutions

This document summarizes the real technical problems encountered while building **FieldCapture** on Meta DAT 1.1.0 and testing it on a **Samsung Galaxy M30s (Exynos 9611, Android 11 / API 30)**, and how each one was resolved.

## 1. DAT 1.1.0 API Differences vs. Older Documentation
- **Problem**: High-level web references showed older DAT 0.x APIs (`Wearables.startStreamSession`, `RegistrationState.Unavailable()`), which do not exist in `mwdat:1.1.0`. Additionally, `DatResult.fold` is not `inline`, causing Kotlin compiler errors when calling `suspend` functions (`withContext`) inside `onSuccess`.
- **Solution**: Cloned and inspected Meta's official `meta/meta-wearables-dat-android` sample (`CameraAccess`) to verify exact `1.1.0` symbols: two-step `Wearables.createSession(AutoDeviceSelector())` → wait for `DeviceSessionState.STARTED` → `session.addCamera(StreamConfiguration)` → `stream.start()`, enum `RegistrationState.UNAVAILABLE`/`REGISTERED`, and extracting `PhotoData` out of `fold` before calling `withContext(Dispatchers.Default)`.

## 2. Black Camera Preview on Galaxy M30s (Exynos 9611, Android 11)
- **Problem**: Using `compressVideo = true` with an HEVC `MediaCodec` decoder rendering to a `SurfaceView` inside a scrollable Jetpack Compose `Column` produced a black preview box on the Galaxy M30s (Exynos 9611).
- **Solution**: Switched `StreamConfiguration` to `compressVideo = false`, converted uncompressed I420 `VideoFrame` buffers (`Y + U + V` → `NV21` → `Bitmap`) in `DatSessionManager`, and rendered via Compose `Image(bitmap.asImageBitmap())`. This eliminated `SurfaceView` Z-order/Exynos codec issues and provided an instant fallback frame when `capturePhoto()` is unavailable.

## 3. Android 11 (`minSdk = 30`) Permission & Install Failure
- **Problem**: Initial builds targeted `minSdk = 31` (Android 12), which failed installation on the Android 11 test device (`INSTALL_FAILED_OLDER_SDK`), and `Manifest.permission.BLUETOOTH_CONNECT` does not exist on API 30.
- **Solution**: Lowered `minSdk` to `30` (verified `mwdat:1.1.0` compatibility) and guarded `BLUETOOTH_CONNECT` in `MainActivity.onStart()` behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`.

## 4. Missing Audio Path in MockDeviceKit
- **Problem**: DAT 1.1.0 does not expose a microphone stream API; real glasses route mic audio over Android Bluetooth HFP/SCO, which `MockDeviceKit` does not register in `AudioManager`.
- **Solution**: Built `AudioRouteDetector` to inspect `AudioManager.GET_DEVICES_INPUTS` for `TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET` and automatically fall back to the phone's `SpeechRecognizer` mic while recording the exact fallback reason in `JobRecord.sessionTelemetry`.

## 5. Session Race Conditions & Accidental Double-Taps
- **Problem**: Testing on-device revealed two UX edge cases: tapping `Start` before enabling Mock Glasses failed with `No eligible device available`, and tapping `Start` while a session was already open threw `A session already exists for this device`.
- **Solution**: Added one-tap auto-pairing of Mock Glasses inside `startWalkthroughSession()`, guarded `DatSessionManager.startSession()` against existing active sessions, and made `Start`, `Pause`/`Resume`, and `Stop` buttons state-aware in `FieldCaptureScreen`.
