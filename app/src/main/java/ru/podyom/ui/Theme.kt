package ru.podyom.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Night = darkColorScheme(primary = Color(0xFF6BE4C4), onPrimary = Color(0xFF00382D),
    background = Color(0xFF0D1419), surface = Color(0xFF152128), surfaceVariant = Color(0xFF213239),
    onBackground = Color(0xFFE7F2F2), onSurface = Color(0xFFE7F2F2), secondary = Color(0xFF8AD8CB),
    primaryContainer = Color(0xFF174C41), onPrimaryContainer = Color(0xFFBDFAE7),
    secondaryContainer = Color(0xFF233F43), onSecondaryContainer = Color(0xFFB9EDE5),
    surfaceContainerLowest = Color(0xFF0B1216), surfaceContainerLow = Color(0xFF142028),
    surfaceContainer = Color(0xFF18272D), surfaceContainerHigh = Color(0xFF1E3035), surfaceContainerHighest = Color(0xFF273D43),
    onSurfaceVariant = Color(0xFFADC5C6), outline = Color(0xFF607C7D), outlineVariant = Color(0xFF344A4E))
private val Day = lightColorScheme(primary = Color(0xFF006B58), onPrimary = Color.White,
    background = Color(0xFFF1F7F5), surface = Color.White, surfaceVariant = Color(0xFFDCEBE5),
    secondary = Color(0xFF32675D), primaryContainer = Color(0xFFA4F2D9), onPrimaryContainer = Color(0xFF00382D),
    secondaryContainer = Color(0xFFD4EAE3), onSecondaryContainer = Color(0xFF163F36),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FCFA),
    surfaceContainer = Color(0xFFE9F3EF), surfaceContainerHigh = Color(0xFFE0EEE8), surfaceContainerHighest = Color(0xFFD6E8DF),
    onSurface = Color(0xFF142A24), onSurfaceVariant = Color(0xFF405C53), outlineVariant = Color(0xFFB9D1C6))
@Composable fun PodyomTheme(light: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (light) Day else Night,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}
