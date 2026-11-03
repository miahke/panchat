package com.bitchat.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily

@Composable
internal fun ModSettingsRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Settings, null, Modifier.size(22.dp), tint = colors.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Настройки мода", fontFamily = BitchatFontFamily, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = colors.onSurface)
            Text("Tor · qWDTT · сервер и подписки", fontFamily = BitchatFontFamily,
                fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}
