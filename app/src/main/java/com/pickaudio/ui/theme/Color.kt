package com.pickaudio.ui.theme

import androidx.compose.ui.graphics.Color
import com.pickaudio.data.model.ThemeColor

val AccentBlue = Color(0xFF8FB8E8)
val AccentBlueDark = Color(0xFF285E96)
val AccentCyan = Color(0xFF9AB1CB)
val AccentPrimaryContainerDark = Color(0xFF0D324D)
val AccentPrimaryContainerLight = Color(0xFFE1F5FE)

val DarkBackground = Color(0xFF0D0F14)
val DarkSurface = Color(0xFF151922)
val DarkSurfaceVariant = Color(0xFF1E2433)
val DarkOnSurface = Color(0xFFF1F5F9)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)

val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightOnSurface = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF64748B)

internal data class AccentPalette(
    val lightPrimary: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
    val onDarkPrimary: Color
)

internal fun ThemeColor.palette(): AccentPalette = when (this) {
    ThemeColor.BLUE -> AccentPalette(AccentBlueDark, AccentPrimaryContainerLight, Color(0xFF21496F),
        AccentBlue, AccentPrimaryContainerDark, Color(0xFF15243A))
    ThemeColor.GREEN -> AccentPalette(Color(0xFF286447), Color(0xFFD9EFDF), Color(0xFF123C29),
        Color(0xFF9AD4AD), Color(0xFF163D2B), Color(0xFF103523))
    ThemeColor.PURPLE -> AccentPalette(Color(0xFF705299), Color(0xFFEEDDFA), Color(0xFF40285E),
        Color(0xFFD2B5F2), Color(0xFF422B60), Color(0xFF342044))
    ThemeColor.ROSE -> AccentPalette(Color(0xFF9B4561), Color(0xFFFFDDE6), Color(0xFF65243C),
        Color(0xFFF2B1C5), Color(0xFF5C283D), Color(0xFF4D1D30))
    ThemeColor.AMBER -> AccentPalette(Color(0xFF825A12), Color(0xFFFFE6B7), Color(0xFF553900),
        Color(0xFFE9C078), Color(0xFF49350F), Color(0xFF3D2B08))
    ThemeColor.TEAL -> AccentPalette(Color(0xFF006A68), Color(0xFFCAEFEB), Color(0xFF004542),
        Color(0xFF8AD3CB), Color(0xFF004D48), Color(0xFF003734))
}
