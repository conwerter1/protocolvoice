# Architecture

`InterviewViewModel` coordinates recording, import, recognition and export.
`InterviewScreen` renders the current phase. Recording/transcription controls
are anchored in the Scaffold bottom bar; setup content scrolls independently.

`audio/` owns recording, playback, import and foreground services. `asr/`
owns recognition and speaker identification. `downloader/` fetches models.
`summary/` provides NER and extractive summaries; the optional PRO tier needs
an additional native library (see its BUILD_NATIVE.md).

`SessionStore` saves session JSON and recordings under private app files.
Automatic cloud backup and device transfer are excluded in the Android backup
rules. User-directed export to Downloads or sharing remains available.
`docx/DocxBuilder.kt` builds the exported Word document.
