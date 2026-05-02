# Contributing to ProtocolVoice

Thanks for your interest! ProtocolVoice is built by professionals who needed an offline voice transcription tool that respects privacy. Contributions are welcome.

## Ways to contribute

- **Bug reports** — open an [Issue](../../issues) with steps to reproduce
- **Feature requests** — open an Issue describing your use case
- **Pull requests** — discuss the change in an Issue first, especially for larger changes
- **Documentation** — improvements to README, docs, code comments
- **Translations** — adding support for new languages (currently RU + EN)

## Development setup

Requirements:
- Android SDK 34
- JDK 17
- Android device (Android 8.0+) or emulator

```bash
git clone https://github.com/<owner>/protocolvoice.git
cd protocolvoice
./gradlew assembleDebug
```

For testing the downloader without re-installing on your device:
1. Build & install
2. In app: ⋮ menu → "Re-download models"
3. App restarts → goes through Downloader flow

## Code style

- Kotlin official style (4-space indent, trailing commas where appropriate)
- Compose: stateless composables where possible, ViewModel for state
- All user-visible strings via `stringResource()` — never hardcoded
- Comments in Russian or English (existing code uses both — match local style)

## Pull request checklist

- [ ] Code compiles (`./gradlew assembleDebug`)
- [ ] Tested on a real device (not just emulator) for any UI changes
- [ ] No new TODO/FIXME without explanation
- [ ] strings.xml updated for both `values/` (RU) and `values-en/` (EN)
- [ ] No models/binary blobs added to repo (use Releases instead)
- [ ] PR description explains *why*, not just *what*

## What we're cautious about

- **Network calls.** ProtocolVoice's selling point is "100% offline after setup". Any code adding telemetry, analytics, or remote features needs strong justification and an opt-in toggle.
- **Permissions.** Don't add permissions without explicit user benefit.
- **Dependency size.** Mobile users notice APK size. Justify any new heavy library.
- **Model changes.** Updating ONNX models requires re-publishing GitHub Release with new SHA-256 — coordinate with maintainers.

## Privacy & licensing notes

- All contributions are licensed under Apache 2.0 (matching project license)
- Don't include code from incompatible licenses (GPL, AGPL, etc.)
- Don't include user data, API keys, or credentials in commits

## Questions?

Open an Issue — preferred over email for transparency.
