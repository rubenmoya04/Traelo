package dev.rubenmoya.traelo.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B6D2F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F5A8),
    onPrimaryContainer = Color(0xFF002108),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F10),
    tertiary = Color(0xFF39656B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF2),
    onTertiaryContainer = Color(0xFF001F23),
    background = Color(0xFFF7FBF1),
    onBackground = Color(0xFF181D17),
    surface = Color(0xFFF7FBF1),
    onSurface = Color(0xFF181D17),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EB),
    surfaceContainer = Color(0xFFEBEFE5),
    surfaceContainerHigh = Color(0xFFE6E9E0),
    surfaceContainerHighest = Color(0xFFE0E4DA),
)

private val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF8BD88E),
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF005318),
    onPrimaryContainer = Color(0xFFA6F5A8),
    secondary = Color(0xFFB9CCB4),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFA1CED5),
    onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF1F4D54),
    onTertiaryContainer = Color(0xFFBCEBF2),
    background = Color(0xFF10150F),
    onBackground = Color(0xFFE0E4DA),
    surface = Color(0xFF10150F),
    onSurface = Color(0xFFE0E4DA),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0B0F0A),
    surfaceContainerLow = Color(0xFF181D17),
    surfaceContainer = Color(0xFF1C211B),
    surfaceContainerHigh = Color(0xFF262B25),
    surfaceContainerHighest = Color(0xFF313630),
)

/** Colores con significado fijo (verde = bien, ambar = ojo): no cambian con el color dinamico. */
@Immutable
data class StatusColors(
    val okContainer: Color,
    val onOk: Color,
    val warnContainer: Color,
    val onWarn: Color,
)

private val LightStatus = StatusColors(
    okContainer = Color(0xFFC8E6C9),
    onOk = Color(0xFF0D3B13),
    warnContainer = Color(0xFFFFE0B2),
    onWarn = Color(0xFF4A2C00),
)

private val DarkStatus = StatusColors(
    okContainer = Color(0xFF1F4A25),
    onOk = Color(0xFFC8F0CB),
    warnContainer = Color(0xFF5A3C00),
    onWarn = Color(0xFFFFDDB0),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun TraeloTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> FallbackDark
        else -> FallbackLight
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
