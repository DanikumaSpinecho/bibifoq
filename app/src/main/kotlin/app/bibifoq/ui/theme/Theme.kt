package app.bibifoq.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.bibifoq.data.ThemeChoice

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00696D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF6FF6FC),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4A6365),
    tertiary = Color(0xFF4F5F7E),
    surfaceTint = Color(0xFF00696D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4CD9E0),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF6FF6FC),
    secondary = Color(0xFFB1CBCD),
    tertiary = Color(0xFFB7C7EA),
    background = Color(0xFF0E1415),
    surface = Color(0xFF0E1415),
)

@Composable
fun BibifoqTheme(
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }

    val context = LocalContext.current
    // Material You where the platform offers it; a fixed palette everywhere else, so the app
    // looks deliberate rather than accidental on older devices.
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = colors, content = content)
}
