# ProtocolVoice

> **Professional offline voice transcription for Android.**
> Audit-grade quality, multi-speaker, fully on-device, with interview summarization.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_8.0%2B-green.svg)](https://www.android.com)
[![Privacy: 100%25 offline](https://img.shields.io/badge/Privacy-100%25%20offline-brightgreen.svg)](#privacy)

ProtocolVoice transcribes interviews, meetings, and conversations **directly on your phone** — without sending audio to any cloud service. Generates court-ready DOCX protocols with speaker identification, timestamps, and confidence indicators. Includes built-in interview summarization that extracts names, organizations, key quotes, risks, and numerical data — all on-device.

Designed for auditors, journalists, researchers, and other professionals who need transcription quality with privacy guarantees.

---

## Why ProtocolVoice

Most voice transcription apps send your audio to remote servers for processing. For confidential interviews — audits, internal investigations, sensitive research — this is a non-starter.

ProtocolVoice runs **everything on your device**:
- Recording → on device
- ASR (speech-to-text) → on device, using GigaAM-v3 (Russian) or Whisper base (English)
- Speaker diarization → on device, using ERes2Net / CAM++ embeddings
- Summarization → on device, using Slovnet NER + LexRank (no LLM needed)
- DOCX export → on device, generated locally

**No internet connection required after initial model download.** Audio never leaves your phone.

---

## Features

### Transcription

| Feature | Status |
|---|---|
| Russian voice transcription (GigaAM-v3) | ✅ |
| English voice transcription (Whisper base.en) | ✅ |
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

### Summarization (NEW in v0.2)

| Feature | Status |
|---|---|
| **Default tier** — NER + LexRank, ~6 sec on 18k words | ✅ |
| Russian named entity extraction (PER / ORG / LOC) | ✅ |
| Top quotes ranking by LexRank | ✅ |
| Numerical data extraction with context | ✅ |
| Risk/problem trigger detection | ✅ |
| Fact-only summary (zero hallucinations) | ✅ |
| **PRO tier** — narrative summary via QVikhr 1.5B LLM | 🟡 In progress |
| Optional 1 GB LLM download for narrative output | 🟡 Native library build needed |

---

## Summarization architecture

ProtocolVoice has a **two-tier summarization system**, designed so users always get a useful summary without an LLM, but can opt in to a narrative summary if they want one.

### Default tier (always available)

A custom Kotlin port of [natasha/slovnet](https://github.com/natasha/slovnet) NER plus a pure-Kotlin TF-IDF + LexRank implementation. Total model size: **28 MB** (Navec embeddings + Slovnet CNN+CRF weights). Inference: **~6 sec for a 17,900-word transcript** on Xiaomi 12T.

Output (validated against Natasha library on the same transcript):
- **Names**: extracted via Slovnet NER, deduplicated, sorted by mention count
- **Organizations** and **locations**: same pipeline
- **Key quotes**: top sentences by LexRank score with entity-presence boost
- **Numbers**: regex-based extraction with surrounding context window
- **Risk mentions**: sentences containing trigger keywords (проблема, риск, штраф, etc.)

This output has **zero hallucinations** — every output element is a direct citation or count from the input transcript. Suitable as audit evidence.

### PRO tier (optional)

Layered on top of the Default tier. Sends the extracted entities, top quotes, and trigger sentences to a local [QVikhr-2.5-1.5B-Instruct-r](https://huggingface.co/Vikhrmodels/QVikhr-2.5-1.5B-Instruct-r) (1.0 GB GGUF, runs via llama.cpp on-device) to produce a narrative-style summary across 6 sections (topic, participants, themes, risks, numbers, conclusions). Generation time: **~5 minutes** on Xiaomi 12T.

PRO tier requires:
1. A native llama.cpp library compiled for ARM64 (see [BUILD_NATIVE.md](app/src/main/java/app/protocolvoice/summary/pro_tier/BUILD_NATIVE.md))
2. User to manually download the QVikhr GGUF file

In current builds the native library is not yet bundled — the UI displays "AI summary unavailable in this build" and only the Default tier runs.

---

## Privacy

- **Zero network calls during operation.** The only network use is initial model download (~360 MB total for full feature set) on first launch.
- **No telemetry, no analytics, no crash reporting** to third parties.
- **No accounts, no sign-up, no user data collection.**
- **Audio files and transcripts stored only on device.** When you export to Downloads/, they're under your control.
- **Source code is open** — verify yourself what the app does and doesn't do.

---

## Hardware requirements

- **Android 8.0** (API 26) or newer
- **64-bit ARM CPU** (most modern phones)
- **~700 MB free storage** for core ASR + diarization + summarization models, **1.7 GB** if PRO tier (QVikhr) is also installed
- **2 GB RAM** minimum, 4 GB+ recommended

Tested on:
- Xiaomi 12T (Dimensity 8100) — RTF 0.07 ASR, RTF 0.5 diarization with CAM++, ~6 sec summarization on 18k-word transcript
- Pixel 6 — similar performance

---

## Quick start

### Option 1: Install from release

Download the latest APK from [Releases](../../releases/latest) and install. Allow installation from unknown sources if prompted.

### Option 2: Build from source

```bash
git clone https://github.com/conwerter1/protocolvoice.git
cd protocolvoice

# Install Android SDK 34, JDK 17 if not already
./gradlew assembleDebug

# APK will be at app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First launch

1. Grant microphone + notifications permissions
2. Tap "Скачать" (Download) — models will be fetched from Hugging Face (`protocolvoice/asr-models`)
3. Once complete, you're ready to record

---

## Usage

1. **Optionally fill metadata** (top-right buttons): interview title, location, participants
2. **Tap "Запись"** (Record) to start. App keeps recording even if minimized.
3. **Tap "Стоп"** (Stop) when done.
4. **Tap "Распознать"** (Transcribe) — ASR + diarization runs (typically 1-3 min for 30-min recording on modern hardware).
5. **Edit speaker names** in the result list if needed.
6. **Tap "Резюме"** (Summary) — extracts facts and key quotes (~6 seconds).
7. **Tap "Сохранить DOCX"** (Save DOCX) — protocol is written to `Downloads/ProtocolVoice/`.

---

## Tech stack

- **Language:** Kotlin 2.0
- **UI:** Jetpack Compose with Material 3
- **ASR runtime:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ONNX Runtime)
- **ASR models:**
  - Russian: GigaAM-v3 e2e CTC int8
  - English: Whisper base.en encoder/decoder int8
- **Speaker embeddings:** CAM++ / ERes2Net / ERes2Net V2 (3D-Speaker project)
- **VAD:** Silero VAD
- **Summarization (Default tier):** Custom Kotlin reimplementation of Slovnet NER (Navec embeddings + WordCNN + CRF Viterbi) + Kotlin TF-IDF + PageRank for LexRank — **zero third-party libraries**
- **Summarization (PRO tier):** llama.cpp + QVikhr-2.5-1.5B GGUF via custom JNI
- **DOCX:** Custom builder (no Apache POI — too heavy for mobile)
- **Audio:** AudioRecord + MediaCodec for WAV/AAC

---

## Models

All models hosted on Hugging Face: [protocolvoice/asr-models](https://huggingface.co/protocolvoice/asr-models)

The app downloads them on first launch and verifies SHA-256 against `manifest.json` in the same repository.

---

## Documentation

- [Architecture overview](docs/ARCHITECTURE.md)
- [Building from source](docs/BUILDING.md)
- [Building native llama.cpp library for PRO tier](app/src/main/java/app/protocolvoice/summary/pro_tier/BUILD_NATIVE.md)
- [Contributing](CONTRIBUTING.md)

---

## Roadmap

**v0.2 (current):**
- ✅ English ASR support
- ✅ Summarization Default tier (NER + LexRank)
- 🟡 Summarization PRO tier (QVikhr + llama.cpp on-device)

**v0.3:**
- Optional cloud sync for sessions (encrypted)
- Settings screen for advanced model configuration
- iOS port (if there's demand)

**v0.4:**
- Pro features: corporate templates, FTS5 search, signed DOCX
- Multi-language summarization

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

You're free to use, modify, and distribute this software, including for commercial purposes, as long as you preserve the copyright notice.

---

## Models attribution

ASR, speaker embedding, and NER models are derivatives of openly published research and pretrained weights. See [models/CREDITS.md](models/CREDITS.md) for full attribution.

- **GigaAM-v3** (Russian ASR): Sber AI ([Hugging Face](https://huggingface.co/salute-developers/GigaAM)) — MIT
- **Whisper** (English ASR): OpenAI ([GitHub](https://github.com/openai/whisper)) — MIT
- **CAM++ / ERes2Net** (speaker diarization): Alibaba DAMO / [3D-Speaker](https://github.com/modelscope/3D-Speaker) — Apache-2.0
- **Slovnet NER + Navec** (Russian NER): [natasha project](https://github.com/natasha/slovnet) — MIT
- **QVikhr-2.5-1.5B** (optional PRO tier): [Vikhrmodels](https://huggingface.co/Vikhrmodels/QVikhr-2.5-1.5B-Instruct-r) — Apache-2.0
- **Silero VAD:** Silero Team
- **sherpa-onnx:** Next-gen Kaldi (k2-fsa) — Apache-2.0

---

## Acknowledgements

This project would not exist without:
- The [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) team for ONNX-based on-device ASR
- The Sber AI team for GigaAM models
- The OpenAI team for Whisper
- The 3D-Speaker / DAMO team for speaker embeddings
- The [natasha project](https://github.com/natasha) team for Slovnet NER and Navec embeddings
- The [Vikhrmodels](https://huggingface.co/Vikhrmodels) team for QVikhr Russian LLM
- The [llama.cpp](https://github.com/ggerganov/llama.cpp) team for on-device LLM inference
- Anthropic Claude for development assistance

---

## Contact

Issues and feature requests: [GitHub Issues](../../issues)

---

*Built for professionals who can't compromise on privacy.*
