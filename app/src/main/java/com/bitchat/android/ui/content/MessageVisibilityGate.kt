// SPDX-License-Identifier: AGPL-3.0-only
package com.bitchat.android.ui.content

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Hidden content is never composed or decoded until the user explicitly reveals it. */
@Composable
internal fun MessageVisibilityGate(
    identity: Any,
    hidden: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var revealed by remember(identity, hidden) { mutableStateOf(false) }
    if (hidden && !revealed) {
        OutlinedButton(onClick = { revealed = true }, modifier = modifier.fillMaxWidth()) {
            Text(label)
        }
    } else {
        content()
    }
}
