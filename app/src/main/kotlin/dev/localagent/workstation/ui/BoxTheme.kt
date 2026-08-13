package dev.localagent.workstation.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A seam of the app icon's cube at full strength: the one saturated colour Box owns. */
val BoxGreen = Color(0xFF2AB80C)

/** The hot edge of that seam, where the glow meets a lit face. Reads on dark surfaces. */
val BoxGreenLight = Color(0xFF7FE868)

/** The ground the icon's cube sits on, and the ground anything icon-like sits on here. */
val BoxVoid = Color(0xFF0A0B0C)

val BoxInk = Color(0xFF161A16)
val BoxTerminal = Color(0xFF0A0C0B)

/**
 * The user's own turns. Green belongs to the *agent's* work — its status, its successes — so the
 * person typing gets the one non-green surface in the app, which is also what keeps a long
 * transcript scannable at arm's length.
 */
val BoxUserBubble = Color(0xFF33407E)
val BoxUserBubbleLight = Color(0xFFDDE1FB)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F7D08),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F3B4),
    onPrimaryContainer = Color(0xFF0A2600),
    secondary = Color(0xFF52634A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8C9),
    onSecondaryContainer = Color(0xFF111F0A),
    tertiary = Color(0xFF3D6372),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC1E9FA),
    onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF6F7F2),
    onBackground = BoxInk,
    surface = Color(0xFFF6F7F2),
    onSurface = BoxInk,
    surfaceVariant = Color(0xFFDFE5D9),
    onSurfaceVariant = Color(0xFF43483F),
    outline = Color(0xFF73796D),
    outlineVariant = Color(0xFFC2C8BB),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = BoxGreenLight,
    onPrimary = Color(0xFF0A3300),
    primaryContainer = Color(0xFF17590A),
    onPrimaryContainer = Color(0xFFC6F3B4),
    secondary = Color(0xFFBACCAC),
    onSecondary = Color(0xFF263520),
    secondaryContainer = Color(0xFF3C4B34),
    onSecondaryContainer = Color(0xFFD5E8C9),
    tertiary = Color(0xFFA5CDDE),
    onTertiary = Color(0xFF063543),
    tertiaryContainer = Color(0xFF244C5A),
    onTertiaryContainer = Color(0xFFC1E9FA),
    background = Color(0xFF0C0F0D),
    onBackground = Color(0xFFE2E5DE),
    surface = Color(0xFF0C0F0D),
    onSurface = Color(0xFFE2E5DE),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC2C8BB),
    outline = Color(0xFF8C9287),
    outlineVariant = Color(0xFF43483F),
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
