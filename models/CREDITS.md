# Model and runtime attribution

Models and runtimes retain their upstream licenses. The application license
does not replace the terms for each downloaded model or bundled dependency.

| Component | Upstream |
|---|---|
| GigaAM | https://huggingface.co/salute-developers/GigaAM |
| Whisper | https://github.com/openai/whisper |
| CAM++ / ERes2Net | https://github.com/modelscope/3D-Speaker |
| Slovnet / Navec | https://github.com/natasha/slovnet and https://github.com/natasha/navec |
| Silero VAD | https://github.com/snakers4/silero-vad |
| sherpa-onnx | https://github.com/k2-fsa/sherpa-onnx |
| Optional QVikhr | https://huggingface.co/Vikhrmodels/QVikhr-2.5-1.5B-Instruct-r |

Model download registry: `app/src/main/java/app/protocolvoice/downloader/ModelRegistry.kt`.
Check the exact upstream model revision and license before redistributing weights.
