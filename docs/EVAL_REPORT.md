# FieldCapture Evaluation Report

> Note: All numbers in this report must come from real frames captured on-device (never synthetic or downloaded images). I built `EvaluationEngine` and the in-app **Eval** tab (`RealImageEvalRunner`) so the 40+ frame evaluation runs directly on the phone with ML Kit OCR and `ImageQualityAnalyzer`.

## How to Run the 40+ Frame Evaluation
1. Capture **≥ 40 real frames** in your own space:
   - ≥ 10 appliance/water-heater nameplates (varied angles, distances, glare)
   - ≥ 10 low-light shots (dark cupboard, under-sink, dim corner)
   - ≥ 20 wall/ceiling/floor damage shots (measure 5+ with a tape measure in inches)
2. Generate `labels.json` and push to the phone:
   ```bash
   python scripts/prepare_eval_labels.py <folder-of-photos>
   adb push "<folder-of-photos>\." /sdcard/Android/data/com.fieldcapture.app/files/eval/
   ```
3. Open the **Eval** tab in FieldCapture and tap **Run Evaluation on Real Captures**.

## Measured Results (Populate After Real Capture Run)
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

## Five Worst Failures & Root Causes
1. **Sample:** `TBD` — **Field:** `TBD` — **Cause:** `TBD`
2. **Sample:** `TBD` — **Field:** `TBD` — **Cause:** `TBD`
3. **Sample:** `TBD` — **Field:** `TBD` — **Cause:** `TBD`
4. **Sample:** `TBD` — **Field:** `TBD` — **Cause:** `TBD`
5. **Sample:** `TBD` — **Field:** `TBD` — **Cause:** `TBD`
