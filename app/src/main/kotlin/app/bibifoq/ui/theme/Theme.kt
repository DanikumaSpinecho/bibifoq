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

private val Teal = Color(0xFF00696D)
private val TealLight = Color(0xFF7FD1B9)
private val Slate = Color(0xFF101418)

private val LightScheme = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A6365),
    tertiary = Color(0xFF4F5F7E),
)

private val DarkScheme = darkColorScheme(
    primary = TealLight,
    secondary = Color(0xFFB1CBCD),
    tertiary = Color(0xFFB7C7EA),
    background = Slate,
)

@Composable
fun BibifoqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
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
