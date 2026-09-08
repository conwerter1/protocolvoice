# Building ProtocolVoice

Use JDK 17 and Android SDK platform 34. Set `ANDROID_HOME` to the SDK folder
or create an untracked `local.properties` with `sdk.dir`.

```sh
./gradlew assembleDebug testDebugUnitTest
```

On Windows use Git Bash for the wrapper. The debug APK is generated in
`app/build/outputs/apk/debug/`. Model fixtures and the sherpa-onnx AAR are
included in the repository. Large speech models download on first launch.

The source version 1.0.1 is the next patch version; the existing published APK
is tagged v1.0.0. Release signing and publishing are separate manual steps.
Do not distribute an unsigned release or change the app signing key.

Before publishing, test recording, import, transcription and DOCX export on
a physical phone, including a small viewport and enlarged system fonts.
