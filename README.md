# ProtocolVoice

> **Professional offline voice transcription for Android.**
> Audit-grade quality, multi-speaker, fully on-device.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_8.0%2B-green.svg)](https://www.android.com)
[![Privacy: 100%25 offline](https://img.shields.io/badge/Privacy-100%25%20offline-brightgreen.svg)](#privacy)

ProtocolVoice transcribes interviews, meetings, and conversations **directly on your phone** — without sending audio to any cloud service. Generates court-ready DOCX protocols with speaker identification, timestamps, and confidence indicators.

Designed for auditors, journalists, researchers, and other professionals who need transcription quality with privacy guarantees.

---

## Why ProtocolVoice

Most voice transcription apps send your audio to remote servers for processing. For confidential interviews — audits, internal investigations, sensitive research — this is a non-starter.

ProtocolVoice runs **everything on your device**:
- Recording → on device
- ASR (speech-to-text) → on device, using GigaAM-v3 (Russian)
- Speaker diarization → on device, using ERes2Net / CAM++ embeddings
- DOCX export → on device, generated locally

**No internet connection required after initial model download.** Audio never leaves your phone.

---

## Features

| Feature | Status |
|---|---|
| Russian voice transcription (GigaAM-v3) | ✅ |
| Multi-speaker diarization (1–8 speakers) | ✅ |
| Three diarization models (CAM++ / ERes2Net V1/V2) | ✅ |
| Auto speaker count detection | ✅ |
| Word-level confidence highlighting | ✅ |
| WAV recording with pause/resume | ✅ |
| Background recording (foreground service) | ✅ |
| In-app audio playback with segment navigation | ✅ |
| Session history with full editing | ✅ |
| DOCX export with speakers, timestamps, signatures | ✅ |
| WAV → M4A automatic compression for storage savings | ✅ |
| English transcription | 🟡 Planned (post-launch) |

---

## Privacy

- **Zero network calls during operation.** The only network use is initial model download (~332 MB) on first launch.
- **No telemetry, no analytics, no crash reporting** to third parties.
- **No accounts, no sign-up, no user data collection.**
- **Audio files stored only on device.** When you export to Downloads/, they're under your control.
- **Source code is open** — verify yourself what the app does and doesn't do.

---

## Hardware requirements

- **Android 8.0** (API 26) or newer
- **64-bit ARM CPU** (most modern phones)
- **~700 MB free storage** (332 MB models + APK + recordings)
- **2 GB RAM** minimum, 4 GB+ recommended for smooth diarization

Tested on:
- Xiaomi 12T (Dimensity 8100) — RTF 0.07 ASR, RTF 0.5 diarization with CAM++
- Pixel 6 — similar performance

---

## Quick start

### Option 1: Install from release

Download the latest APK from [Releases](../../releases/latest) and install. Allow installation from unknown sources if prompted.

### Option 2: Build from source

```bash
git clone https://github.com/<owner>/protocolvoice.git
cd protocolvoice

# Install Android SDK 34, JDK 17 if not already
# Then:
./gradlew assembleDebug

# APK will be at app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First launch

1. Grant microphone + notifications permissions
2. Tap "Скачать" (Download) — models will be fetched from GitHub Releases (~5-15 minutes depending on connection)
3. Once complete, you're ready to record

---

## Usage

1. **Optionally fill metadata** (top-right buttons): interview title, location, participants
2. **Tap "Запись"** (Record) to start. App keeps recording even if minimized.
3. **Tap "Стоп"** (Stop) when done.
4. **Tap "Распознать"** (Transcribe) — ASR + diarization runs (typically 1-3 min for 30-min recording on modern hardware).
5. **Edit speaker names** in the result list if needed.
6. **Tap "Сохранить DOCX"** (Save DOCX) — protocol is written to `Downloads/ProtocolVoice/`.

---

## Tech stack

- **Language:** Kotlin 2.0
- **UI:** Jetpack Compose with Material 3
- **ASR:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ONNX Runtime)
- **ASR Model:** GigaAM-v3 e2e CTC int8 (Russian)
- **Speaker Embeddings:** CAM++ / ERes2Net / ERes2Net V2 (Modelscope/Hugging Face)
- **VAD:** Silero VAD
- **DOCX:** Custom builder (no Apache POI — too heavy for mobile)
- **Audio:** AudioRecord + MediaCodec for WAV/AAC

---

## Documentation

- [Architecture overview](docs/ARCHITECTURE.md)
- [Building from source](docs/BUILDING.md)
- [Contributing](CONTRIBUTING.md)

---

## Roadmap

**v0.2:**
- English ASR support
- Optional cloud sync for sessions (encrypted)
- iOS port (if there's demand)

**v0.3:**
- Pro features: corporate templates, FTS5 search, signed DOCX
- Settings screen for advanced model configuration

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

You're free to use, modify, and distribute this software, including for commercial purposes, as long as you preserve the copyright notice.

---

## Models attribution

ASR and speaker embedding models are derivatives of openly published research and pretrained weights. See [models/CREDITS.md](models/CREDITS.md) for full attribution.

- **GigaAM-v3:** Sber AI ([Hugging Face](https://huggingface.co/salute-developers/GigaAM))
- **CAM++ / ERes2Net:** Alibaba DAMO Academy / 3D-Speaker project
- **Silero VAD:** Silero Team
- **Sherpa-ONNX:** Next-gen Kaldi (k2-fsa)

---

## Acknowledgements

This project would not exist without:
- The [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) team for ONNX-based on-device ASR
- The Sber AI team for GigaAM models
- The 3D-Speaker / DAMO team for speaker embeddings
- Anthropic Claude for development assistance

---

## Contact

Issues and feature requests: [GitHub Issues](../../issues)
Email for sensitive disclosures: TBD

---

*Built for professionals who can't compromise on privacy.*
