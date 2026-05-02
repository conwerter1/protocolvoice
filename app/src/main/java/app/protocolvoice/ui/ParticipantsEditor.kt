package app.protocolvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.protocolvoice.R

/**
 * Bottom sheet редактор участников интервью.
 *
 * - До записи можно «забронировать» имена спикеров: просто нажать «+» N раз
 *   и заполнить — получится начальный список (Speaker 0, Speaker 1, …).
 * - После распознавания — список ID приходит из transcript (knownSpeakerIds),
 *   и пользователь может вписать имена напротив каждого SPK_X.
 *
 * Параметр [knownSpeakerIds] — фактически найденные распознавалкой ID. Если
 * пусто (до записи), показываем уже введённых пользователем + одну пустую
 * строку на «новый ID».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantsBottomSheet(
    initialNames: Map<Int, String>,
    knownSpeakerIds: List<Int>,
    onDismiss: () -> Unit,
    onSave: (Map<Int, String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Локальная копия — изменяется в полях, применяется по «Сохранить»
    val edits = remember {
        mutableStateMapOf<Int, String>().apply {
            putAll(initialNames)
        }
    }

    // Все ID, которые показываем: distinct(known + already-edited),
    // отсортированы. Если оба пустые — показываем строку для SPK_0.
    val displayIds = remember(knownSpeakerIds, edits.keys.toList()) {
        val merged = (knownSpeakerIds + edits.keys).distinct().sorted()
        if (merged.isEmpty()) listOf(0) else merged
    }
    var nextId by remember { mutableStateOf(displayIds.maxOrNull()?.plus(1) ?: 0) }
    val visibleIds = remember(displayIds, nextId) {
        // Если пользователь нажал «+», добавляем эфемерные ID до nextId
        val maxKnown = displayIds.maxOrNull() ?: -1
        if (nextId > maxKnown + 1) {
            displayIds + ((maxKnown + 1) until nextId).toList()
        } else displayIds
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.participants_title_alt),
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (knownSpeakerIds.isEmpty())
                    stringResource(R.string.participants_subtitle_pre_recognition)
                else
                    stringResource(R.string.participants_subtitle_recognized, knownSpeakerIds.size),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(visibleIds, key = { it }) { id ->
                    ParticipantRow(
                        id = id,
                        value = edits[id].orEmpty(),
                        onChange = { newValue ->
                            if (newValue.isBlank()) edits.remove(id) else edits[id] = newValue
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { nextId = (visibleIds.maxOrNull() ?: -1) + 2 },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.participants_add_more))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onSave(edits.toMap())
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
private fun ParticipantRow(
    id: Int,
    value: String,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SPK_$id",
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(stringResource(R.string.participants_name_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
        )
    }
}
