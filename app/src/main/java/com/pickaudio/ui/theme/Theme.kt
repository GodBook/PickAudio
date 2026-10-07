package com.pickaudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pickaudio.data.model.ThemeMode
import com.pickaudio.data.model.ThemeColor

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF15243A),
    primaryContainer = AccentPrimaryContainerDark,
    onPrimaryContainer = Color(0xFFC5DDF4),
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF253B52),
    onSecondaryContainer = Color(0xFFC5DDF4),
    tertiary = AccentBlue,
    tertiaryContainer = Color(0xFF253B52),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = Color(0xFF273041),
    surfaceContainerHighest = Color(0xFF303B4D),
    outline = Color(0xFF8494A9),
    outlineVariant = Color(0xFF364254),
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color.White,
    primaryContainer = AccentPrimaryContainerLight,
    onPrimaryContainer = Color(0xFF21496F),
    secondary = AccentBlue,
    secondaryContainer = Color(0xFFE2ECF8),
    onSecondaryContainer = Color(0xFF21496F),
    tertiary = AccentBlueDark,
    tertiaryContainer = Color(0xFFE2ECF8),
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE8EEF6),
    surfaceContainerHighest = LightSurfaceVariant,
    outline = Color(0xFF65758B),
    outlineVariant = Color(0xFFC6D1DF),
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant
)

fun pickAudioColorScheme(themeColor: ThemeColor, darkTheme: Boolean): ColorScheme {
    val palette = themeColor.palette()
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val primary = if (darkTheme) palette.darkPrimary else palette.lightPrimary
    val container = if (darkTheme) palette.darkContainer else palette.lightContainer
    val onPrimary = if (darkTheme) palette.onDarkPrimary else Color.White
    val onContainer = if (darkTheme) palette.darkPrimary else palette.onLightContainer
    fun tint(color: Color) = if (themeColor == ThemeColor.BLUE) color else
        lerp(color, primary, if (darkTheme) 0.04f else 0.025f)
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = container, onSecondaryContainer = onContainer,
        tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = container, onTertiaryContainer = onContainer,
        inversePrimary = if (darkTheme) palette.lightPrimary else palette.darkPrimary,
        surfaceTint = primary,
        background = tint(base.background), surface = tint(base.surface),
        surfaceVariant = tint(base.surfaceVariant),
        surfaceContainerLowest = tint(base.surfaceContainerLowest),
        surfaceContainerLow = tint(base.surfaceContainerLow),
        surfaceContainer = tint(base.surfaceContainer),
        surfaceContainerHigh = tint(base.surfaceContainerHigh),
        surfaceContainerHighest = tint(base.surfaceContainerHighest)
    )
}

private val MusicTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp)
)

@Composable
fun PickAudioTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeColor: ThemeColor = ThemeColor.BLUE,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val context = LocalContext.current
    androidx.compose.runtime.SideEffect {
        (context as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    val colorScheme = pickAudioColorScheme(themeColor, darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MusicTypography,
        content = content
    )
}
