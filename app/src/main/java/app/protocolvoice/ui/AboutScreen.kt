package app.protocolvoice.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.protocolvoice.BuildConfig
import app.protocolvoice.R
import app.protocolvoice.ui.theme.BrandBlue
import app.protocolvoice.ui.theme.BrandPurple

/**
 * Экран «О программе» — содержит:
 *  - Логотип и название приложения
 *  - Версию (BuildConfig.VERSION_NAME)
 *  - Краткое описание
 *  - Acknowledgments — благодарности upstream-проектам с лицензиями
 *  - Информацию о лицензии самого приложения
 *
 * Каждая карточка благодарностей кликабельна и открывает соответствующий URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit = {},
) {
    val ctx = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://$url"))
            ctx.startActivity(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.about_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ------------------ Header: logo + name + version ------------------
            HeaderSection()

            Spacer(Modifier.height(32.dp))

            // ------------------ Description ------------------
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(Modifier.height(32.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Spacer(Modifier.height(24.dp))

            // ------------------ Acknowledgments ------------------
            SectionHeader(text = stringResource(R.string.about_section_acknowledgments))

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.about_ack_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(16.dp))

            AckCard(
                title = stringResource(R.string.about_ack_gigaam_title),
                desc = stringResource(R.string.about_ack_gigaam_desc),
                url = stringResource(R.string.about_ack_gigaam_url),
                onClick = { openUrl(it) },
            )
            Spacer(Modifier.height(12.dp))

            AckCard(
                title = stringResource(R.string.about_ack_speaker_title),
                desc = stringResource(R.string.about_ack_speaker_desc),
                url = stringResource(R.string.about_ack_speaker_url),
                onClick = { openUrl(it) },
            )
            Spacer(Modifier.height(12.dp))

            AckCard(
                title = stringResource(R.string.about_ack_sherpa_title),
                desc = stringResource(R.string.about_ack_sherpa_desc),
                url = stringResource(R.string.about_ack_sherpa_url),
                onClick = { openUrl(it) },
            )
            Spacer(Modifier.height(12.dp))

            AckCard(
                title = stringResource(R.string.about_ack_huggingface_title),
                desc = stringResource(R.string.about_ack_huggingface_desc),
                url = stringResource(R.string.about_ack_huggingface_url),
                onClick = { openUrl(it) },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(24.dp))

            // ------------------ License ------------------
            SectionHeader(text = stringResource(R.string.about_section_license))

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.about_license_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun HeaderSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Большой логотип приложения (используем mipmap-foreground)
        Icon(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(listOf(BrandBlue, BrandPurple)),
                ),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // Версия
        val versionText = "${stringResource(R.string.about_version_label)} ${BuildConfig.VERSION_NAME}"
        Text(
            text = versionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AckCard(
    title: String,
    desc: String,
    url: String,
    onClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(url) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = BrandBlue,
                fontSize = 12.sp,
            )
        }
    }
}
