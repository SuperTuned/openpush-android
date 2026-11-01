package ai.openpush.example

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

object OpenPushPalette {
    val Signal = Color(0xFF006B3D)
    val SignalBright = Color(0xFF00C267)
    val SignalTextDark = Color(0xFF65E59D)
    val Paper = Color(0xFFF7F5F1)
    val PaperRaised = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0A0A0A)
    val Muted = Color(0xFF686761)
    val Hairline = Color(0xFFD7D4CD)
    val DarkPaper = Color(0xFF0A0A0A)
    val DarkRaised = Color(0xFF171717)
    val DarkInk = Color(0xFFF7F5F1)
    val DarkMuted = Color(0xFFAAA8A1)
    val DarkHairline = Color(0xFF3A3936)
    val Warning = Color(0xFF9A6700)
    val WarningDark = Color(0xFFFFD166)
}

private val LightScheme = lightColorScheme(
    primary = OpenPushPalette.Signal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F8E3),
    onPrimaryContainer = Color(0xFF00391F),
    secondary = OpenPushPalette.Ink,
    onSecondary = OpenPushPalette.Paper,
    secondaryContainer = Color(0xFFE9E6E0),
    onSecondaryContainer = OpenPushPalette.Ink,
    tertiary = OpenPushPalette.Warning,
    background = OpenPushPalette.Paper,
    onBackground = OpenPushPalette.Ink,
    surface = OpenPushPalette.Paper,
    onSurface = OpenPushPalette.Ink,
    surfaceVariant = OpenPushPalette.PaperRaised,
    onSurfaceVariant = OpenPushPalette.Muted,
    outline = OpenPushPalette.Hairline,
    outlineVariant = Color(0xFFE8E5DE),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = OpenPushPalette.SignalTextDark,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF00522F),
    onPrimaryContainer = Color(0xFFB6F2CE),
    secondary = OpenPushPalette.DarkInk,
    onSecondary = OpenPushPalette.DarkPaper,
    secondaryContainer = Color(0xFF292927),
    onSecondaryContainer = OpenPushPalette.DarkInk,
    tertiary = OpenPushPalette.WarningDark,
    background = OpenPushPalette.DarkPaper,
    onBackground = OpenPushPalette.DarkInk,
    surface = OpenPushPalette.DarkPaper,
    onSurface = OpenPushPalette.DarkInk,
    surfaceVariant = OpenPushPalette.DarkRaised,
    onSurfaceVariant = OpenPushPalette.DarkMuted,
    outline = OpenPushPalette.DarkHairline,
    outlineVariant = Color(0xFF292927),
    error = Color(0xFFFFB4AB),
)

private val OpenPushShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OpenPushTheme(appearance: AppAppearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
    val scheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = OpenPushShapes,
        content = content,
    )
}
