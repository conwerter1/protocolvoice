package app.protocolvoice.asr

import androidx.annotation.StringRes
import app.protocolvoice.R

/**
 * Выбор модели для speaker embedding в диаризации.
 *
 * Каждая модель — отдельный ONNX-файл в assets/asr/. Размер и скорость
 * сильно отличаются. Качество (DER — diarization error rate) тоже,
 * но для аудиторских интервью с 1-4 собеседниками все три приемлемы.
 *
 * Sherpa-onnx позволяет менять embedding-модель только через пересоздание
 * OfflineSpeakerDiarization — это занимает 1-2 сек, но не блокирует UI.
 *
 * Текстовые метки (displayName, sizeLabel, description) — ресурсы strings.xml,
 * резолвятся в UI через stringResource(…).
 */
enum class EmbeddingModel(
    val assetPath: String,
    @StringRes val displayNameRes: Int,
    @StringRes val sizeLabelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    /**
     * 3D-Speaker ERes2Net base 200k. Эталон по качеству, самый медленный.
     * Это была дефолтная модель в первой версии приложения.
     */
    ERes2Net(
        assetPath = "asr/speaker_embedding.onnx",
        displayNameRes = R.string.embedding_model_eres2net_name,
        sizeLabelRes = R.string.embedding_model_eres2net_size,
        descriptionRes = R.string.embedding_model_eres2net_desc,
    ),

    /**
     * 3D-Speaker ERes2NetV2. По заявлению авторов — улучшенная V2:
     * меньше параметров, быстрее inference, качество сравнимо с V1.
     * Хороший компромисс по умолчанию.
     */
    ERes2NetV2(
        assetPath = "asr/speaker_embedding_v2.onnx",
        displayNameRes = R.string.embedding_model_eres2netv2_name,
        sizeLabelRes = R.string.embedding_model_eres2netv2_size,
        descriptionRes = R.string.embedding_model_eres2netv2_desc,
    ),

    /**
     * 3D-Speaker CAM++. D-TDNN backbone, самая компактная и самая быстрая
     * из трёх. Качество чуть ниже ERes2Net на сложных сценариях, но для
     * интервью с 1-4 спикерами разница практически незаметна.
     */
    CAMPlus(
        assetPath = "asr/speaker_embedding_camplus.onnx",
        displayNameRes = R.string.embedding_model_camplus_name,
        sizeLabelRes = R.string.embedding_model_camplus_size,
        descriptionRes = R.string.embedding_model_camplus_desc,
    );

    companion object {
        /** Дефолт при первом запуске. ERes2NetV2 — баланс скорости и качества. */
        val DEFAULT = ERes2NetV2

        /** Все варианты в порядке для UI. */
        val all: List<EmbeddingModel>
            get() = listOf(ERes2Net, ERes2NetV2, CAMPlus)
    }
}
