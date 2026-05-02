# How to publish ProtocolVoice on GitHub

This folder contains a clean, publishable copy of the ProtocolVoice Android source code.
The large ML models are NOT included here — they are hosted separately on Hugging Face
and downloaded by the app on first run.

## Pre-publication checklist

- [ ] Review `README.md` — make sure it accurately describes the project.
- [ ] Review `LICENSE` — Apache 2.0. Make sure your name / org is in any copyright notices.
- [ ] Review `CONTRIBUTING.md` — adjust contact info if needed.
- [ ] Verify there are no secrets in the code (search for `hf_`, `sk-`, `password`, `token`, etc.).
- [ ] Verify `local.properties` is in `.gitignore` — it contains your Android SDK path which is machine-specific.

## Step 1: Create a GitHub repository

1. Go to https://github.com/new
2. Repository name: `protocolvoice` (or `protocolvoice-android`)
3. Description: "Offline voice transcription with on-device ASR and speaker diarization"
4. **Public** (to establish open-source timestamp)
5. **Do NOT** initialize with README, .gitignore, or license — we have our own
6. Click "Create repository"

## Step 2: Initialize git locally and push

Open PowerShell in this folder (`C:\Work_Claude\Output\ProtocolVoice_GitHub_ready`):

```powershell
cd C:\Work_Claude\Output\ProtocolVoice_GitHub_ready

git init
git branch -M main
git add .
git commit -m "Initial public release of ProtocolVoice"

# Replace YOUR_USERNAME with your GitHub username
git remote add origin https://github.com/YOUR_USERNAME/protocolvoice.git
git push -u origin main
```

The first push will ask you to authenticate. Use a Personal Access Token (not password):
- Go to https://github.com/settings/tokens/new
- Scope: `repo` (full)
- Copy the token, paste when git asks for password.

## Step 3: Verify the upload

After push, visit your repo URL. You should see:
- README rendered with badges
- LICENSE file recognized as "Apache-2.0"
- All source code under `app/src/`

## Step 4: (Recommended) Create a release for the APK

To distribute the cleaned APK (~36 MB):
1. Go to https://github.com/YOUR_USERNAME/protocolvoice/releases/new
2. Tag: `v1.0.0`
3. Title: "ProtocolVoice v1.0.0 — initial release"
4. Description: paste from `RELEASE_NOTES.md` (in this folder)
5. Attach the APK file: `C:\Work_Claude\Output\ProtocolVoice_internal\releases\protocolvoice-clean-2026-05-01_2303.apk`
6. Click "Publish release"

## Step 5: Add badges and screenshots

Once pushed, the README has placeholders for badges. Update with your repo URL:
- Build status: GitHub Actions
- Latest release: shields.io badge

You can add screenshots to a `docs/screenshots/` folder and reference them in README.

## Why we exclude the large ML models

The 4 ONNX model files (~511 MB total) are NOT in this repository because:
1. GitHub limits files to 100 MB
2. Even with git-lfs, this would slow down `git clone` enormously
3. ML models are conventionally hosted on Hugging Face

The models are at: https://huggingface.co/protocolvoice/asr-models
The app downloads them on first run via the built-in Downloader.

## What's still missing for true publication readiness

1. **Screenshots** in `docs/screenshots/` — needed for Google Play and good README
2. **Privacy policy** — needed for Google Play (mention "all processing on-device, no data collection")
3. **Google Play account** — needed to publish to Play Store ($25 one-time fee)
4. **Signed release APK** — for Play Store you need a release-signed APK with proper signing key

These are not blockers for GitHub publication, but they are blockers for Play Store.
