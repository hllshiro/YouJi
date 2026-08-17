package cn.hllcloud.youji.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryOrange = Color(0xFFFF6B35)
val PrimaryOrangeLight = Color(0xFFFFDBCB)
val PrimaryOrangeDark = Color(0xFF4A1400)

val SecondaryPurple = Color(0xFF6750A4)
val SecondaryPurpleLight = Color(0xFFE8DEF8)

val BackgroundLight = Color(0xFFFFFBFF)
val SurfaceLight = Color(0xFFFFFBFF)
val OnSurfaceLight = Color(0xFF201A18)
val OutlineLight = Color(0xFF85736E)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryOrange,
    onPrimary = Color.White,
    primaryContainer = PrimaryOrangeLight,
    onPrimaryContainer = PrimaryOrangeDark,
    secondary = SecondaryPurple,
    onSecondary = Color.White,
    secondaryContainer = SecondaryPurpleLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    outline = OutlineLight,
    error = Color(0xFFBA1A1A)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF6B2200),
    primaryContainer = Color(0xFF913714),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFCBBFF7),
    onSecondary = Color(0xFF341B75),
    secondaryContainer = Color(0xFF4D388C),
    background = Color(0xFF201A18),
    onBackground = Color(0xFFEDE0DC),
    surface = Color(0xFF201A18),
    onSurface = Color(0xFFEDE0DC),
    outline = Color(0xFFA08F8A)
)

@Composable
fun YouJiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
