# Research Memo: Meta AI Glasses & DAT 1.1.0 for Field Estimation

## 1. What Today's Meta Glasses Can and Can't Do for Field Estimation
- **Strengths**: Hands-free first-person POV capture in tight spaces (under sinks, utility closets, attics) where holding a phone + flashlight + tape measure is awkward; 12MP stills (`PhotoData`) are sharp enough in good light for appliance nameplate OCR and documenting water-stain extent.
- **Hard Constraints**:
  1. **No depth sensor**: Monocular RGB frames cannot measure distance to a wall or ceiling directly. Without a known-size reference in the frame (e.g., outlet cover, tile, or Aruco card), area estimates depend on an assumed standoff distance prior.
  2. **No controllable camera flash/torch in DAT**: Water heaters, crawlspaces, and under-sink cabinets are frequently under-lit (`< 48` mean luminance). Glasses have an LED indicator, not a high-lumen work light.
  3. **Bluetooth link sensitivity**: Live 24fps HEVC video streaming over Bluetooth + Wi-Fi Direct drains battery quickly and can drop when a tech steps behind metal ductwork or concrete walls, making local queuing and automatic session recovery mandatory.

## 2. What We Found in DAT 1.1.0 That High-Level Docs Gloss Over
Inspecting the official `meta/meta-wearables-dat-android` SDK (`mwdat-core:1.1.0`, `mwdat-camera:1.1.0`, `mwdat-mockdevice:1.1.0`) and testing live on a **Samsung Galaxy M30s (Exynos 9611, Android 11 / API 30)** revealed five critical implementation details:
1. **Two-Step Capability Attachment & Duplicate Session Guard**: `DeviceSession.start()` is fire-and-forget (`Unit`). Calling `session.addCamera(...)` immediately after `start()` fails with `SESSION_IDLE`; the app must collect `session.state` and wait for `DeviceSessionState.STARTED` before attaching `Camera` and calling `stream.start()`. Additionally, calling `Wearables.createSession(...)` before a prior session finishes tearing down throws `"A session already exists for this device"`.
2. **No DAT Audio Stream API**: DAT 1.1.0 exposes `Camera` and `Display`, not a microphone stream. Real glasses mic audio arrives via standard Android Bluetooth HFP/SCO (`AudioDeviceInfo.TYPE_BLUETOOTH_SCO`). Because `MockDeviceKit` does not register a virtual SCO device in `AudioManager`, apps must explicitly detect the missing SCO route and fall back to the phone mic (verified on-device: `AudioRouteDetector` switched to BT HFP when a BT headset was paired and fell back to phone mic otherwise).
3. **HEVC `SurfaceView` Black-Screen Issue vs. Uncompressed `VideoFrame` Rendering**: Meta's `CameraAccess` sample sets `compressVideo = true` and decodes HEVC onto a `SurfaceView` while blocking `OMX.Exynos.hevc.dec` and `c2.mtk.hevc.decoder`. On our Exynos 9611 Android 11 test device inside a scrollable Compose container, `SurfaceView` + HEVC rendered a black preview. Switching to `compressVideo = false` and converting uncompressed I420 `VideoFrame`s to `ImageBitmap` resolved the black preview and also provides an instant fallback frame if `capturePhoto()` fails.
4. **EXIF Orientation on HEIC Stills**: `PhotoData.HEIC` stores rotation in EXIF tags that Android's `BitmapFactory.decodeByteArray` ignores, so `ExifInterface` rotation is required before running quality checks or OCR.
5. **Android 11 (`minSdk = 30`) Compatibility**: `mwdat:1.1.0` runs cleanly on API 30, provided `BLUETOOTH_CONNECT` runtime permission requests are guarded behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`.

## 3. What MockDeviceKit Cannot Simulate (What Changes on Real Glasses)
| Area | MockDeviceKit Behavior | Real Ray-Ban Meta Glasses |
|---|---|---|
| **Microphone / Voice** | No virtual BT SCO/HFP device in `AudioManager`; falls back to phone mic | Audio routes over BT HFP/SCO (8–16 kHz narrowband/wideband), which degrades ASR in noisy mechanical rooms |
| **Thermal & Battery** | Runs indefinitely on phone power | Continuous 24fps HEVC streaming heats the temple and drains battery (~30–45 min continuous stream); must pause stream between rooms |
| **RF / Disconnects** | Instant `powerOff()` / `fold()` toggle | Gradual RSSI drop, packet loss, and stalled frames behind ductwork/concrete before `DeviceSessionState.STOPPED` fires |
| **App Attestation** | `mwdat_application_id=0` works in mock/dev mode | Production requires registered Meta Wearables Developer Center `APPLICATION_ID`, `CLIENT_TOKEN`, and Meta AI companion app approval |
| **Optics & Exposure** | Phone camera sensor/AF or static video/image | Fixed-focus wide-angle lens (close-up nameplates < 15 cm can defocus) with slower AE adaptation when stepping into dark cupboards |

## 4. First Hardware Change We Would Make
**Add a narrow-beam, voice-togglable LED work light (≥ 150 lumens) paired with a single-point Time-of-Flight (ToF) laser rangefinder (e.g., VL53L4CD) aligned with the camera optical axis.**
- **Why**: The #1 failure mode in mechanical/plumbing/under-sink inspection is low light (`< 48` luminance) causing both motion blur (long exposure) and unreadable nameplates. A co-aligned ToF sensor solves the #2 failure mode—monocular scale ambiguity—by giving exact standoff distance $D$ at the center reticle so $\text{width} = 2 D \tan(\text{HFOV}/2) \cdot (\Delta x / W)$ becomes accurate without any tape measure or reference card.
