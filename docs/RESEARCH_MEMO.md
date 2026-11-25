# Research Memo: Meta AI Glasses & DAT 1.1.0 for Field Estimation

## 1. What Today's Meta Glasses Can and Can't Do for Field Estimation
- **Strengths**: Hands-free first-person POV capture in tight spaces (under sinks, utility closets, attics) where holding a phone + flashlight + tape measure is awkward; 12MP stills (`PhotoData`) are sharp enough in good light for appliance nameplate OCR and documenting water-stain extent.
- **Hard Constraints**:
  1. **No depth sensor**: Monocular RGB frames cannot measure distance to a wall or ceiling directly. Without a known-size reference in the frame (e.g., outlet cover, tile, or Aruco card), area estimates depend on an assumed standoff distance prior.
  2. **No controllable camera flash/torch in DAT**: Water heaters, crawlspaces, and under-sink cabinets are frequently under-lit (`< 48` mean luminance). Glasses have an LED indicator, not a high-lumen work light.
  3. **Bluetooth link sensitivity**: Live 24fps HEVC video streaming over Bluetooth + Wi-Fi Direct drains battery quickly and can drop when a tech steps behind metal ductwork or concrete walls, making local queuing and automatic session recovery mandatory.

## 2. What We Found in DAT 1.1.0 That High-Level Docs Gloss Over
Inspecting the official `meta/meta-wearables-dat-android` SDK (`mwdat-core:1.1.0`, `mwdat-camera:1.1.0`, `mwdat-mockdevice:1.1.0`) and sample apps revealed four critical implementation details:
1. **Two-Step Capability Attachment**: `DeviceSession.start()` is fire-and-forget (`Unit`). Calling `session.addCamera(...)` immediately after `start()` fails with `SESSION_IDLE`; the app must collect `session.state` and wait for `DeviceSessionState.STARTED` before attaching `Camera` and calling `stream.start()`.
2. **No DAT Audio Stream API**: DAT 1.1.0 exposes `Camera` and `Display`, not a microphone stream. Real glasses mic audio arrives via standard Android Bluetooth HFP/SCO (`AudioDeviceInfo.TYPE_BLUETOOTH_SCO`). Because `MockDeviceKit` does not register a virtual SCO device in `AudioManager`, apps must explicitly detect the missing SCO route and fall back to the phone mic.
3. **HEVC NAL Unit Priming & Blocked Hardware Decoders**: When `compressVideo = true`, `VideoFrame` delivers raw H.265 NAL units. Previewing on a `SurfaceView` requires caching VPS/SPS/PPS config NALs (types 32/33/34) until the first IRAP keyframe, and avoiding known broken vendor decoders (`OMX.Exynos.hevc.dec`, `c2.mtk.hevc.decoder`).
4. **EXIF Orientation on HEIC Stills**: `PhotoData.HEIC` stores rotation in EXIF tags that Android's `BitmapFactory.decodeByteArray` ignores, so `ExifInterface` rotation is required before running quality checks or OCR.

## 3. First Hardware Change We Would Make
**Add a narrow-beam, voice-togglable LED work light (≥ 150 lumens) paired with a single-point Time-of-Flight (ToF) laser rangefinder (e.g., VL53L4CD) aligned with the camera optical axis.**
- **Why**: The #1 failure mode in mechanical/plumbing/under-sink inspection is low light (`< 48` luminance) causing both motion blur (long exposure) and unreadable nameplates. A co-aligned ToF sensor solves the #2 failure mode—monocular scale ambiguity—by giving exact standoff distance $D$ at the center reticle so $\text{width} = 2 D \tan(\text{HFOV}/2) \cdot (\Delta x / W)$ becomes accurate without any tape measure or reference card.
