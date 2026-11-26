# Research Memo: Meta AI Glasses & DAT 1.1.0 for Field Estimation

## 1. What Today's Meta Glasses Can and Can't Do for Field Estimation
In practice, Meta AI glasses are genuinely useful for first-person, hands-free capture in tight spaces (under sinks, utility closets, attics) where holding a phone and a flashlight at the same time slows a tech down. The 12MP still path (`PhotoData`) is sharp enough in normal room lighting for appliance nameplate OCR and water-stain documentation.

Where they fall short today:
- **No depth sensor**: Monocular RGB frames give no direct distance to a wall or ceiling. Without a known-size reference in view (outlet cover, tile, Aruco marker), size estimates rely on an assumed standoff distance.
- **No controllable work light**: Under-sink cabinets and crawlspaces routinely fall below `48` mean luminance. The glasses have a capture indicator LED, not a high-lumen illumination LED.
- **Wireless link fragility**: Streaming 24fps video over Bluetooth/Wi-Fi Direct drains battery fast and drops behind metal ductwork or concrete walls, so local queuing and session recovery are essential.

## 2. What I Found in DAT 1.1.0 Beyond the High-Level Docs
Working directly with `mwdat-core:1.1.0`, `mwdat-camera:1.1.0`, and `mwdat-mockdevice:1.1.0`—and testing on a **Samsung Galaxy M30s (Exynos 9611, Android 11 / API 30)**—surfaced five details that aren't obvious from the quickstart docs:

1. **Two-step session attachment & duplicate session errors**: `DeviceSession.start()` returns `Unit` immediately. Calling `session.addCamera(...)` right away fails with `SESSION_IDLE` unless you wait for `DeviceSessionState.STARTED`. Likewise, calling `Wearables.createSession(...)` before an old session finishes tearing down throws `"A session already exists for this device"`, so the app has to guard and reuse active sessions.
2. **No DAT audio stream API**: DAT 1.1.0 exposes `Camera` and `Display`, not a microphone stream. Real glasses route mic audio over standard Android Bluetooth HFP/SCO (`AudioDeviceInfo.TYPE_BLUETOOTH_SCO`). `MockDeviceKit` doesn't register a virtual SCO device in `AudioManager`, so apps must detect the missing SCO route and fall back to the phone mic. (On my Galaxy M30s, `AudioRouteDetector` automatically picked up a paired BT headset over SCO and fell back to the phone mic when disconnected.)
3. **HEVC `SurfaceView` black screen vs. uncompressed `VideoFrame`s**: Meta's `CameraAccess` sample sets `compressVideo = true` and decodes HEVC onto a `SurfaceView` while blocking `OMX.Exynos.hevc.dec` and `c2.mtk.hevc.decoder`. On my Exynos 9611 Android 11 phone inside a scrollable Compose layout, `SurfaceView` + HEVC rendered a black box. Switching to `compressVideo = false` and decoding uncompressed I420 frames into a Compose `ImageBitmap` fixed the preview and gave me a fallback frame if `capturePhoto()` fails.
4. **EXIF rotation on HEIC stills**: `PhotoData.HEIC` stores orientation in EXIF metadata that `BitmapFactory.decodeByteArray` ignores, so `ExifInterface` rotation has to run before quality checks or OCR.
5. **Android 11 (`minSdk = 30`)**: `mwdat:1.1.0` works cleanly on API 30 as long as `BLUETOOTH_CONNECT` runtime permission requests are guarded for Android 12+ (`Build.VERSION_CODES.S`).

## 3. What MockDeviceKit Cannot Simulate (What Changes on Real Glasses)
| Area | MockDeviceKit | Real Ray-Ban Meta Glasses |
|---|---|---|
| Microphone / Voice | No virtual BT SCO device in `AudioManager`; uses phone mic | Audio routes over BT HFP/SCO (8–16 kHz), which degrades speech recognition in noisy mechanical rooms |
| Thermal & Battery | Runs on phone power with no temple heat | Continuous 24fps streaming heats the frame and drains battery (~30–45 min); stream needs pausing between rooms |
| RF / Disconnects | Instant `powerOff()` / `fold()` toggle | Gradual RSSI drop and stalled frames behind ductwork before `DeviceSessionState.STOPPED` fires |
| App Attestation | `mwdat_application_id=0` works in mock mode | Production requires registered Developer Center `APPLICATION_ID`, `CLIENT_TOKEN`, and Meta AI app approval |
| Optics & Exposure | Phone camera AF or static video/photo | Fixed-focus wide lens (close nameplates < 15 cm can blur) and slower auto-exposure in dark cupboards |

## 4. First Hardware Change I Would Make
**Add a narrow-beam, voice-togglable LED work light (≥ 150 lumens) paired with a single-point Time-of-Flight (ToF) rangefinder (such as a VL53L4CD) aligned with the camera axis.**

Low light is the #1 cause of both motion blur and unreadable nameplates in cabinets and crawlspaces, and lack of depth is the #1 cause of area-estimation error. A co-aligned ToF sensor gives exact standoff distance $D$ at the center reticle so $\text{width} = 2 D \tan(\text{HFOV}/2) \cdot (\Delta x / W)$ becomes accurate without needing a tape measure or reference card in the frame.
