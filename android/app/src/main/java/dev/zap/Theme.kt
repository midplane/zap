package dev.zap

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val lightColors = lightColorScheme(
    primary = Color(0xFF245AC5),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF123A73),
    secondaryContainer = Color(0xFFE6EAF1),
    onSecondaryContainer = Color(0xFF394453),
    surface = Color(0xFFFAFAFC),
    background = Color(0xFFFAFAFC)
)
private val darkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    primaryContainer = Color(0xFF173E78),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondaryContainer = Color(0xFF343D4B),
    onSecondaryContainer = Color(0xFFE0E6F0),
    surface = Color(0xFF121419),
    background = Color(0xFF121419)
)

@Composable fun ZapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors, content = content)
}
