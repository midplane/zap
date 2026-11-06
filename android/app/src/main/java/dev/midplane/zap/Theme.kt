package dev.midplane.zap

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * The duplicate book. Light is the top sheet: white ground, carbon-blue impression ink, red printed serials,
 * faint blue ruling. Dark is the carbon sheet itself: blue-black, with pale carbon-blue impressions.
 * Each kind of content wears one of the book's copy tints on its stub.
 */

private val topSheet = lightColorScheme(
    primary = Color(0xFF2E3A8C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE1F7),
    onPrimaryContainer = Color(0xFF1B2466),
    secondaryContainer = Color(0xFFDDE1F7),
    onSecondaryContainer = Color(0xFF1B2466),
    background = Color(0xFFFCFCFA),
    surface = Color(0xFFFCFCFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F3),
    surfaceContainer = Color(0xFFF1F1ED),
    surfaceContainerHigh = Color(0xFFEBEBE7),
    surfaceContainerHighest = Color(0xFFE5E5E1),
    onSurface = Color(0xFF1C1D24),
    onSurfaceVariant = Color(0xFF575A66),
    outline = Color(0xFF8A8FA3),
    outlineVariant = Color(0xFFD3D8E8),
    error = Color(0xFFC2352B),
    onError = Color(0xFFFFFFFF)
)

private val carbonSheet = darkColorScheme(
    primary = Color(0xFFAAB6FF),
    onPrimary = Color(0xFF141A4D),
    primaryContainer = Color(0xFF2A3373),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondaryContainer = Color(0xFF2A3373),
    onSecondaryContainer = Color(0xFFDDE1FF),
    background = Color(0xFF11131B),
    surface = Color(0xFF11131B),
    surfaceContainerLowest = Color(0xFF0C0E14),
    surfaceContainerLow = Color(0xFF161822),
    surfaceContainer = Color(0xFF1A1D28),
    surfaceContainerHigh = Color(0xFF21242F),
    surfaceContainerHighest = Color(0xFF282C38),
    onSurface = Color(0xFFE6E7EE),
    onSurfaceVariant = Color(0xFFA4A8B9),
    outline = Color(0xFF6E7387),
    outlineVariant = Color(0xFF2C3142),
    error = Color(0xFFFF8A7A),
    onError = Color(0xFF4A0E08)
)

/** Colors the Material scheme has no role for. */
@Immutable
data class SheetColors(
    /** Printed serial numbers and rubber stamps. */
    val serial: Color,
    /** Ruled lines and perforations. */
    val ruling: Color,
    val onStub: Color,
    val textStub: Color,
    val linkStub: Color,
    val codeStub: Color,
    val imageStub: Color,
    val colorStub: Color
)

private val topSheetExtras = SheetColors(
    serial = Color(0xFFC2352B),
    ruling = Color(0xFFC9D2EC),
    onStub = Color(0xFF3A3D4A),
    textStub = Color(0xFFF0F0EB),
    linkStub = Color(0xFFE0E9F7),
    codeStub = Color(0xFFE1EDDA),
    imageStub = Color(0xFFF6E0E5),
    colorStub = Color(0xFFF6EDC2)
)

private val carbonSheetExtras = SheetColors(
    serial = Color(0xFFFF8A7A),
    ruling = Color(0xFF2B3144),
    onStub = Color(0xFFC9CCDA),
    textStub = Color(0xFF1B1E29),
    linkStub = Color(0xFF1A2438),
    codeStub = Color(0xFF192A22),
    imageStub = Color(0xFF2E1C25),
    colorStub = Color(0xFF2D2916)
)

val LocalSheetColors = staticCompositionLocalOf { topSheetExtras }

/** True when the system's Remove animations setting is on; motion then cuts instead of moving. */
val LocalReducedMotion = staticCompositionLocalOf { false }

private val sheetShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable fun ZapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(
        LocalSheetColors provides if (dark) carbonSheetExtras else topSheetExtras,
        LocalReducedMotion provides reducedMotion
    ) {
        MaterialTheme(colorScheme = if (dark) carbonSheet else topSheet, shapes = sheetShapes, content = content)
    }
}
