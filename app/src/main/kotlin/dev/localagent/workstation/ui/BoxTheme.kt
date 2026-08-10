package dev.localagent.workstation.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BoxGreen = Color(0xFF30A46C)
val BoxGreenLight = Color(0xFF8CE3B5)
val BoxInk = Color(0xFF181B19)
val BoxTerminal = Color(0xFF101412)

/**
 * The user's own turns. Green belongs to the *agent's* work — its status, its successes — so the
 * person typing gets the one non-green surface in the app, which is also what keeps a long
 * transcript scannable at arm's length.
 */
val BoxUserBubble = Color(0xFF33407E)
val BoxUserBubbleLight = Color(0xFFDDE1FB)

private val LightColors = lightColorScheme(
    primary = Color(0xFF146C48),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2F2D6),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6356),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8D7),
    onSecondaryContainer = Color(0xFF0A1F14),
    tertiary = Color(0xFF3D6372),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC1E9FA),
    onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF6F7F2),
    onBackground = BoxInk,
    surface = Color(0xFFF6F7F2),
    onSurface = BoxInk,
    surfaceVariant = Color(0xFFDFE4DE),
    onSurfaceVariant = Color(0xFF424842),
    outline = Color(0xFF727872),
    outlineVariant = Color(0xFFC1C8C1),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = BoxGreenLight,
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFC2F2D6),
    secondary = Color(0xFFB4CCBB),
    onSecondary = Color(0xFF20352A),
    secondaryContainer = Color(0xFF364B40),
    onSecondaryContainer = Color(0xFFD0E8D7),
    tertiary = Color(0xFFA5CDDE),
    onTertiary = Color(0xFF063543),
    tertiaryContainer = Color(0xFF244C5A),
    onTertiaryContainer = Color(0xFFC1E9FA),
    background = Color(0xFF101311),
    onBackground = Color(0xFFE1E4DF),
    surface = Color(0xFF101311),
    onSurface = Color(0xFFE1E4DF),
    surfaceVariant = Color(0xFF424842),
    onSurfaceVariant = Color(0xFFC1C8C1),
    outline = Color(0xFF8B928B),
    outlineVariant = Color(0xFF424842),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun BoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BoxTypography,
        content = content,
    )
}
