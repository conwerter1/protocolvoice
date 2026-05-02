package app.protocolvoice.downloader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.protocolvoice.R
import app.protocolvoice.ui.theme.BrandBlue
import app.protocolvoice.ui.theme.BrandPurple
import app.protocolvoice.ui.theme.RecordingRed

/**
 * Экран скачивания моделей при первом запуске.
 *
 * Состояния (соответствуют DownloaderViewModel.Phase):
 *  - CHECKING        : показываем спиннер "Проверяем модели..."
 *  - NEEDS_DOWNLOAD  : welcome-экран с описанием и кнопкой "Скачать"
 *  - DOWNLOADING     : прогресс-бар + текущая скорость + кнопка "Отмена"
 *  - SUCCESS         : кратко "Готово!" + onComplete()
 *  - ERROR           : описание ошибки + кнопки "Повторить" / "Закрыть"
 *
 * @param onComplete — вызывается когда все модели скачаны (переход на главный экран).
 */
@Composable
fun DownloaderScreen(
    vm: DownloaderViewModel = viewModel(),
    onComplete: () -> Unit,
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val bytesDownloaded by vm.bytesDownloaded.collectAsStateWithLifecycle()
    val bytesTotal by vm.bytesTotal.collectAsStateWithLifecycle()
    val speedBps by vm.speedBps.collectAsStateWithLifecycle()
    val currentModelId by vm.currentModelId.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()

    // Авто-переход когда модели уже скачаны при старте
    LaunchedEffect(phase) {
        if (phase == DownloaderViewModel.Phase.SUCCESS) {
            onComplete()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (phase) {
                DownloaderViewModel.Phase.CHECKING -> CheckingState()
                DownloaderViewModel.Phase.NEEDS_DOWNLOAD -> WelcomeState(
                    onStart = { vm.startFirstRunDownload() },
                )
                DownloaderViewModel.Phase.DOWNLOADING -> DownloadingState(
                    progress = progress,
                    bytesDownloaded = bytesDownloaded,
                    bytesTotal = bytesTotal,
                    speedBps = speedBps,
                    currentModelId = currentModelId,
                    onCancel = { vm.cancel() },
                )
                DownloaderViewModel.Phase.SUCCESS -> SuccessState()
                DownloaderViewModel.Phase.ERROR -> ErrorState(
                    message = errorMessage,
                    onRetry = { vm.retry() },
                )
            }
        }
    }
}

@Composable
private fun CheckingState() {
    val cs = MaterialTheme.colorScheme
    CircularProgressIndicator(
        color = BrandBlue,
        strokeWidth = 3.dp,
        modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.downloader_checking),
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant,
    )
}

@Composable
private fun WelcomeState(onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme

    // Иллюстрация (используем иллюстрацию для onboarding offline — она про "ваше устройство")
    Image(
        painter = painterResource(R.drawable.illustration_onboarding_offline),
        contentDescription = null,
        modifier = Modifier
            .size(220.dp)
            .padding(bottom = 16.dp),
        contentScale = ContentScale.Fit,
    )

    Text(
        text = stringResource(R.string.downloader_welcome_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = cs.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.downloader_welcome_message),
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    Spacer(Modifier.height(20.dp))

    // Карточка с информацией о размере
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cs.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                tint = BrandBlue,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.downloader_size_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.downloader_size_value,
                        formatBytes(ModelRegistry.FIRST_RUN_TOTAL_BYTES),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandBlue,
            contentColor = Color.White,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp,
        ),
    ) {
        Icon(
            Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.downloader_start_action),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun DownloadingState(
    progress: Float,
    bytesDownloaded: Long,
    bytesTotal: Long,
    speedBps: Long,
    currentModelId: String?,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // Иллюстрация: transcribing — самая динамичная, подходит для "идёт процесс"
    Image(
        painter = painterResource(R.drawable.illustration_empty_transcribing),
        contentDescription = null,
        modifier = Modifier
            .size(180.dp)
            .padding(bottom = 16.dp),
        contentScale = ContentScale.Fit,
    )

    Text(
        text = stringResource(R.string.downloader_downloading_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = cs.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))

    // Прогресс-бар
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = BrandBlue,
        trackColor = cs.surfaceContainerHighest,
    )

    Spacer(Modifier.height(12.dp))

    // Цифры под прогрессом
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${formatBytes(bytesDownloaded)} / ${formatBytes(bytesTotal)}",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurfaceVariant,
        )
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = BrandBlue,
            fontWeight = FontWeight.SemiBold,
        )
    }

    Spacer(Modifier.height(16.dp))

    // Скорость и текущая модель
    val modelLabel = currentModelId?.let { id ->
        when (id) {
            "asr_gigaam_v3" -> stringResource(R.string.downloader_label_asr)
            "embedding_camplus" -> stringResource(R.string.downloader_label_camplus)
            "embedding_v1" -> stringResource(R.string.downloader_label_v1)
            "embedding_v2" -> stringResource(R.string.downloader_label_v2)
            else -> id
        }
    }
    if (modelLabel != null) {
        Text(
            text = modelLabel,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    if (speedBps > 0) {
        Text(
            text = stringResource(
                R.string.downloader_speed,
                formatBytes(speedBps),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = BrandPurple,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(28.dp))

    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .height(48.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.downloader_cancel),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SuccessState() {
    val cs = MaterialTheme.colorScheme
    Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = BrandBlue,
        modifier = Modifier.size(72.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.downloader_success_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = cs.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.downloader_success_message),
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ErrorState(message: String?, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme

    // Иллюстрация: no internet или no results
    Image(
        painter = painterResource(R.drawable.illustration_empty_no_internet),
        contentDescription = null,
        modifier = Modifier
            .size(200.dp)
            .padding(bottom = 16.dp),
        contentScale = ContentScale.Fit,
    )

    Icon(
        Icons.Default.ErrorOutline,
        contentDescription = null,
        tint = RecordingRed,
        modifier = Modifier.size(36.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.downloader_error_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = cs.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = message ?: stringResource(R.string.downloader_error_generic),
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    Spacer(Modifier.height(28.dp))

    Button(
        onClick = onRetry,
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandBlue,
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = stringResource(R.string.downloader_retry),
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Форматирование байт в человекочитаемый размер.
 * 1.4 МБ / 305 МБ / 1.2 ГБ
 */
private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f ГБ".format(gb)
        mb >= 10.0 -> "%.0f МБ".format(mb)
        mb >= 1.0 -> "%.1f МБ".format(mb)
        else -> "%.0f КБ".format(bytes / 1024.0)
    }
}
