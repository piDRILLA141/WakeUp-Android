package ru.podyom.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Night = darkColorScheme(primary = Color(0xFF6BE4C4), onPrimary = Color(0xFF00382D),
    background = Color(0xFF0D1419), surface = Color(0xFF152128), surfaceVariant = Color(0xFF213239),
    onBackground = Color(0xFFE7F2F2), onSurface = Color(0xFFE7F2F2), secondary = Color(0xFFA8C9FF))
private val Day = lightColorScheme(primary = Color(0xFF006B58), onPrimary = Color.White,
    background = Color(0xFFF1F7F5), surface = Color.White, surfaceVariant = Color(0xFFDCEBE5))
@Composable fun PodyomTheme(light: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (light) Day else Night,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}
