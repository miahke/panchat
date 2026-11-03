// SPDX-License-Identifier: AGPL-3.0-only
package com.bitchat.android.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.content.ContentPreferences
import com.bitchat.android.ui.theme.BitchatFontFamily

@Composable
internal fun ContentSettingsRow() {
    val context = LocalContext.current
    val saved by ContentPreferences.options.collectAsState()
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Default.FilterAlt,
            null,
            Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.content_settings_title), fontFamily = BitchatFontFamily)
            Text(
                stringResource(R.string.content_settings_summary, saved.rules.keywords.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.ChevronRight, null)
    }
    if (open) {
        var draft by remember { mutableStateOf(saved.rules.keywords.joinToString("\n")) }
        var hideImages by remember { mutableStateOf(saved.hideImages) }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.content_settings_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.content_settings_explanation))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            if (it.length <= 32_768) {
                                draft = it
                                invalid = false
                            } else invalid = true
                        },
                        label = { Text(stringResource(R.string.content_settings_words)) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        minLines = 3,
                        isError = invalid,
                        supportingText = { Text(stringResource(R.string.content_settings_limits)) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.content_settings_images), Modifier.weight(1f))
                        Switch(checked = hideImages, onCheckedChange = { hideImages = it })
                    }
                    if (invalid)
                        Text(
                            stringResource(R.string.content_settings_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            ContentPreferences.save(context, draft, hideImages)
                            com.bitchat.android.ui.NotificationManager.clearForContentFilterChange()
                            open = false
                        } catch (_: IllegalArgumentException) {
                            invalid = true
                        }
                    }
                ) {
                    Text(stringResource(R.string.content_settings_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun PanchatLegalNotices() {
    val links = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text("panchat · GNU AGPLv3", style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.nym_modified_notice),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { runCatching { links.openUri("https://github.com/Spl0itable/NYM") } }
        ) {
            Text("Based on Nymchat by 21 Million LLC")
        }
        TextButton(
            onClick = { runCatching { links.openUri("https://github.com/miahke/panchat") } }
        ) {
            Text(stringResource(R.string.panchat_corresponding_source))
        }
    }
}
