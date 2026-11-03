package com.bitchat.android.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeAccent(val label: String, val dark: Color, val light: Color) {
    Green("Зелёная", Color(0xFF32D74B), Color(0xFF248A3D)),
    Blue("Синяя", Color(0xFF70B7FF), Color(0xFF005CAD)),
    Purple("Фиолетовая", Color(0xFFC59AFF), Color(0xFF6D34A1)),
    Amber("Янтарная", Color(0xFFFFCA62), Color(0xFF865500)),
    Pink("Розовая", Color(0xFFFF95BF), Color(0xFFA6275D)),
}

fun panchatColorScheme(dark: Boolean, accent: ThemeAccent): ColorScheme {
    val base = if (dark) DarkBitchatColorScheme else LightBitchatColorScheme
    if (accent == ThemeAccent.Green) return base
    val primary = if (dark) accent.dark else accent.light
    return base.copy(
        primary = primary,
        onPrimary = if (dark) Color.Black else Color.White,
        primaryContainer = lerp(base.background, primary, if (dark) 0.22f else 0.13f),
        onPrimaryContainer = if (dark) Color.White else Color.Black,
        surface = lerp(base.background, primary, 0.035f),
        surfaceVariant = lerp(base.background, primary, 0.08f),
        outline = lerp(base.background, primary, 0.32f),
        outlineVariant = lerp(base.background, primary, 0.16f),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeAccentPicker() {
    val context = LocalContext.current
    val selected by ThemePreferenceManager.accentFlow.collectAsState()
    Column {
        Text("Цветовая тема", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeAccent.entries.forEach { accent ->
                FilterChip(
                    selected = selected == accent,
                    onClick = { ThemePreferenceManager.setAccent(context, accent) },
                    label = { Text(accent.label) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            }
        }
    }
}
