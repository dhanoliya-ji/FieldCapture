# FieldCapture Evaluation Report

> **Honest Measurement Policy**: No real-world accuracy numbers are fabricated in this repository. All real-frame metrics must be generated from your own 40+ captured frames (10+ nameplates, 10+ low-light shots, and damage frames with tape-measure ground truth).

## Evaluation Harness
The project includes `com.fieldcapture.app.eval.EvaluationEngine`, which evaluates a labeled dataset (`List<EvalSample>`) across:
- **On-device Quality Gate** (`ImageQualityAnalyzer`: blur, low-light, overexposure, bad framing)
- **Nameplate Extraction** (`NameplateParser`: `make`, `model`, `serialNumber`, `manufactureDate`)
- **Confidence Calibration** (mean confidence on correct predictions vs. incorrect predictions)
- **Monocular Size Estimation** (`MonocularSizeEstimator`: area % error vs. tape-measure ground truth)
- **Top 5 Worst Failures** (ranked with root-cause diagnostics)

## How to Run Your 40+ Frame Evaluation
1. Capture **≥ 40 real frames** using your phone camera or MockDeviceKit video feed:
   - ≥ 10 appliance/water-heater nameplates (varied angles, distances, glare)
   - ≥ 10 low-light shots (under-sink cupboard, dark hallway/closet, dim basement)
   - ≥ 20 wall/ceiling/floor damage or stain shots (record tape-measure width × height in inches for at least 5)
2. Label each frame in a test suite or pass them to `EvaluationEngine.evaluate(samples)`.
3. Record the output below:

### Results Table (Populate After Real Capture Run)
| Metric | Result | Notes |
|---|---|---|
| Total Frames | `TBD (≥ 40)` | |
| Nameplate Frames | `TBD (≥ 10)` | |
| Low-Light Frames | `TBD (≥ 10)` | |
| Quality Gate Accuracy | `TBD` | |
| Nameplate `make` Accuracy | `TBD` | |
| Nameplate `model` Accuracy | `TBD` | |
| Nameplate `serialNumber` Accuracy | `TBD` | |
| Nameplate `manufactureDate` Accuracy | `TBD` | |
| Avg Confidence (Correct vs Wrong) | `TBD` | |
| Mean Monocular Area Error (%) | `TBD` | |

### Five Worst Failures (Populate From `EvaluationEngine` Output)
1. **Sample:** `TBD` — **Field:** `TBD` — **Root Cause:** `TBD`
2. **Sample:** `TBD` — **Field:** `TBD` — **Root Cause:** `TBD`
3. **Sample:** `TBD` — **Field:** `TBD` — **Root Cause:** `TBD`
4. **Sample:** `TBD` — **Field:** `TBD` — **Root Cause:** `TBD`
5. **Sample:** `TBD` — **Field:** `TBD` — **Root Cause:** `TBD`
