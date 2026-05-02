package app.protocolvoice.asr

/**
 * Язык распознавания речи.
 *
 * Каждый язык использует свой ASR-движок:
 *   - RU: GigaAM-v3 e2e_ctc (Sber AI) через nemo_ctc API в sherpa-onnx
 *   - EN: Whisper base.en (OpenAI) через whisper API в sherpa-onnx
 *
 * Диаризация (разделение спикеров) language-agnostic — одна и та же
 * pipeline (pyannote + 3D-Speaker) работает для обоих языков.
 *
 * RU модели поставляются с приложением (или скачиваются при первом запуске),
 * EN модели всегда опциональны и скачиваются по явному запросу пользователя.
 */
enum class AsrLanguage {
    /** Русский — основной язык приложения. */
    RU,

    /** Английский — опциональный, требует скачать ~152 МБ моделей. */
    EN,
}
