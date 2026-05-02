# ProtocolVoice v1.0.0 — Initial release

First public release of ProtocolVoice, an Android app for offline interview transcription.

## What it does

- Records audio interviews directly on your phone
- Transcribes speech to text using **GigaAM-v3** (Russian) — fully on-device, no cloud
- Identifies different speakers using **3D-Speaker** embeddings (CAM++ / ERes2Net)
- Generates a Word document (DOCX) with speaker labels, timestamps, and color-coded confidence

## Privacy

All processing happens on your device. Your audio never leaves your phone.

## Requirements

- Android 8.0+ (API 26)
- ~600 MB free space (for ML models, downloaded on first run)
- ARM64 device (most phones from 2017+)

## Models

The ML models are not included in the APK to keep the install size small (~36 MB).
On first run, the app downloads them from Hugging Face:
https://huggingface.co/protocolvoice/asr-models

Total download: ~332 MB. Takes 1-3 minutes on a normal connection.

## What's in the APK

- All UI in Russian and English
- Recording with pause/resume
- ASR with full punctuation
- Speaker diarization with 3 model choices (CAM++ default for speed)
- DOCX export
- History of past sessions

## What's NOT yet supported

- English ASR (only Russian for now — English models are being researched)
- Languages other than Russian/English UI
- Cloud sync (intentional — privacy first)
- Real-time live transcription (only batch mode after stop)

## Acknowledgments

Built on top of these excellent open-source projects:
- [GigaAM](https://github.com/salute-developers/GigaAM) by SaluteDevices (Sber AI) — MIT
- [3D-Speaker](https://github.com/modelscope/3D-Speaker) by ModelScope (Alibaba) — Apache 2.0
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) by k2-fsa — Apache 2.0

## Installation

Download `protocolvoice-v1.0.0.apk` from the assets below and install it:
- Allow "Install from unknown sources" in Android Settings → Security
- Open the APK, follow prompts
- On first launch, app will download ML models (~332 MB) from Hugging Face
- After download, the app is fully offline

## License

Apache 2.0. See [LICENSE](LICENSE) file.
