// SPDX-License-Identifier: AGPL-3.0-only
package com.bitchat.android.ui.content

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.content.ChatReadingPreferences

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatReadingSettings() {
    val context = LocalContext.current
    val current by ChatReadingPreferences.options.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.chat_reading_font),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatReadingPreferences.fontChoices.forEach { size ->
                FilterChip(
                    selected = current.fontPercent == size,
                    onClick = { ChatReadingPreferences.save(context, size, current.use24Hour) },
                    label = { Text("$size%") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chat_reading_time), Modifier.weight(1f))
            Switch(
                checked = current.use24Hour,
                onCheckedChange = { ChatReadingPreferences.save(context, current.fontPercent, it) },
            )
        }
    }
}
