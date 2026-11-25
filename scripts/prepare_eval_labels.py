"""Generate a labels.json template for RealImageEvalRunner from a folder of real captured images.

Usage:
    python scripts/prepare_eval_labels.py <path-to-folder-of-40+-images>
"""

import json
import sys
from pathlib import Path

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python scripts/prepare_eval_labels.py <image_folder>")
        sys.exit(1)

    folder = Path(sys.argv[1]).resolve()
    if not folder.is_dir():
        print(f"Error: not a directory: {folder}")
        sys.exit(1)

    images = sorted(p for p in folder.iterdir() if p.suffix.lower() in IMAGE_EXTS)
    if not images:
        print(f"No images found in {folder}")
        sys.exit(1)

    labels = []
    for img in images:
        name = img.name.lower()
        is_np = "nameplate" in name or "plate" in name or "heater" in name
        is_dark = "dark" in name or "low" in name or "cupboard" in name
        labels.append(
            {
                "filename": img.name,
                "captureType": "NAMEPLATE" if is_np else "DAMAGE",
                "isLowLight": is_dark,
                "expectedQualityPass": not is_dark and "blur" not in name,
                "expectedMake": None,
                "expectedModel": None,
                "expectedSerial": None,
                "expectedManufactureDate": None,
                "tapeWidthIn": None,
                "tapeHeightIn": None,
            }
        )

    out_file = folder / "labels.json"
    out_file.write_text(json.dumps(labels, indent=2), encoding="utf-8")
    print(f"Wrote {len(labels)} entries to {out_file}")
    print("Fill in ground-truth fields in labels.json, then push to your phone:")
    print(f'  adb push "{folder}\\." /sdcard/Android/data/com.fieldcapture.app/files/eval/')


if __name__ == "__main__":
    main()
