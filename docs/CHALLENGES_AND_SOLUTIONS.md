# Engineering Challenges & Solutions

Notes on the real issues I ran into while building **FieldCapture** on Meta DAT 1.1.0 and testing on my **Samsung Galaxy M30s (Exynos 9611, Android 11 / API 30)**, and how I fixed each one.

## 1. DAT 1.1.0 API Drift vs. Online Examples
Generic search snippets referenced older DAT 0.x methods (`Wearables.startStreamSession`, `RegistrationState.Unavailable()`) that don't exist in `mwdat:1.1.0`. On top of that, `DatResult.fold` isn't `inline`, so calling `suspend` functions like `withContext` inside `onSuccess` failed to compile.

I pulled Meta's official `meta/meta-wearables-dat-android` `CameraAccess` sample to verify the exact `1.1.0` lifecycle (`Wearables.createSession` → wait for `DeviceSessionState.STARTED` → `session.addCamera` → `stream.start()`), `RegistrationState` enum values, and moved `withContext` outside the `fold` block.

## 2. Black Camera Preview on Galaxy M30s (Exynos 9611, Android 11)
When using `compressVideo = true` with an HEVC `MediaCodec` decoder rendering to a `SurfaceView` inside a scrollable Compose layout, the preview box stayed black on my Galaxy M30s even while `Session: STREAMING` was active.

I switched `StreamConfiguration` to `compressVideo = false`, converted the uncompressed I420 `VideoFrame` buffers (`Y + U + V` → `NV21` → `Bitmap`) in `DatSessionManager`, and rendered them directly with Compose `Image(bitmap.asImageBitmap())`. That fixed the black preview immediately and also gave `capturePhoto()` a live preview-frame fallback.

## 3. Android 11 (`minSdk = 30`) Install Failure
The initial build targeted `minSdk = 31`, which failed on my Android 11 phone with `INSTALL_FAILED_OLDER_SDK`. Also, `Manifest.permission.BLUETOOTH_CONNECT` didn't exist before Android 12.

I lowered `minSdk` to `30` (verified `mwdat:1.1.0` supports API 30) and guarded `BLUETOOTH_CONNECT` in `MainActivity.onStart()` behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`.

## 4. Missing Audio Path in MockDeviceKit
DAT 1.1.0 doesn't provide an audio stream API, and `MockDeviceKit` doesn't register a virtual Bluetooth SCO device in Android's `AudioManager`.

I added `AudioRouteDetector` to check `AudioManager.GET_DEVICES_INPUTS` for `TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET` and automatically fall back to the phone mic (`SpeechRecognizer`), recording the fallback reason in `JobRecord.sessionTelemetry`.

## 5. Session Race Conditions & Button Confusion
During device testing, tapping **Start** before enabling Mock Glasses threw `No eligible device available`, and tapping **Start** while a session was already open threw `A session already exists for this device`.

I updated `startWalkthroughSession()` to auto-pair Mock Glasses on first **Start**, added a guard in `DatSessionManager.startSession()` against duplicate sessions, and made `Start`, `Pause`/`Resume`, and `Stop` state-aware with immediate banner feedback.
