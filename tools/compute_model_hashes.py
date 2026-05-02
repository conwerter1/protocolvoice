"""
Compute SHA-256 hashes for ProtocolVoice ONNX models.

Usage:
  python tools/compute_model_hashes.py [models_dir]

Reads all .onnx files from given directory (or app/src/main/assets/asr/ by default)
and prints SHA-256 hashes ready to paste into ModelRegistry.kt.

Also generates manifest.json file with all hashes for upload to GitHub Release.
"""
import sys
import io
import os
import hashlib
import json
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# Default location: AAR-compatible local assets
DEFAULT_DIR = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "asr"

# Known model filenames (must match ModelRegistry.kt)
KNOWN_MODELS = {
    "gigaam_v3_e2e_ctc_int8.onnx":      ("ASR_MAIN",         "asr_gigaam_v3"),
    "speaker_embedding_camplus.onnx":   ("EMBEDDING_CAMPLUS", "embedding_camplus"),
    "speaker_embedding.onnx":           ("EMBEDDING_V1",      "embedding_v1"),
    "speaker_embedding_v2.onnx":        ("EMBEDDING_V2",      "embedding_v2"),
}


def sha256_of(path: Path) -> str:
    """Compute SHA-256 of a file by streaming 64KB chunks."""
    md = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            chunk = f.read(64 * 1024)
            if not chunk:
                break
            md.update(chunk)
    return md.hexdigest()


def main():
    models_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DIR
    if not models_dir.is_dir():
        print(f"ERROR: Directory not found: {models_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Scanning: {models_dir}")
    print()

    results = {}
    for filename, (constant, model_id) in KNOWN_MODELS.items():
        path = models_dir / filename
        if not path.exists():
            print(f"  MISSING: {filename}")
            continue
        size = path.stat().st_size
        print(f"  Computing SHA-256 for {filename} ({size:,} bytes)...")
        hash_val = sha256_of(path)
        results[filename] = {
            "constant": constant,
            "model_id": model_id,
            "size_bytes": size,
            "sha256": hash_val,
        }
        print(f"    {hash_val}")
        print()

    if not results:
        print("ERROR: No known models found.", file=sys.stderr)
        sys.exit(1)

    # Print Kotlin code to paste into ModelRegistry.kt
    print("=" * 70)
    print("Paste these into app/src/main/java/app/protocolvoice/downloader/ModelRegistry.kt:")
    print("=" * 70)
    print()
    for filename, info in results.items():
        print(f"  // {filename}")
        print(f'  // sizeBytes = {info["size_bytes"]}L,')
        print(f'  // sha256    = "{info["sha256"]}",')
        print()

    # Generate manifest.json (for upload to GitHub Release alongside .onnx files)
    manifest = {
        "version": "models-v1.0",
        "generated_with": "tools/compute_model_hashes.py",
        "models": {
            info["model_id"]: {
                "filename": filename,
                "size_bytes": info["size_bytes"],
                "sha256": info["sha256"],
            }
            for filename, info in results.items()
        }
    }
    manifest_path = Path(__file__).parent / "manifest.json"
    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"Manifest written to: {manifest_path}")
    print(f"  → upload this file to GitHub Release as 'manifest.json'")
    print()
    print(f"Done. {len(results)} model(s) hashed.")


if __name__ == "__main__":
    main()
