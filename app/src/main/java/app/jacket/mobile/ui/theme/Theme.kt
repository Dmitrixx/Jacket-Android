package app.jacket.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JacketColors = darkColorScheme(
    primary = Color(0xFFFFC45C),
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF563B13),
    onPrimaryContainer = Color(0xFFFFE4B0),
    secondary = Color(0xFF62D7C4),
    onSecondary = Color(0xFF062D29),
    secondaryContainer = Color(0xFF164640),
    onSecondaryContainer = Color(0xFFB8FFF3),
    tertiary = Color(0xFFA99BFF),
    onTertiary = Color(0xFF21165B),
    background = Color(0xFF080B11),
    onBackground = Color(0xFFE9EDF5),
    surface = Color(0xFF111722),
    onSurface = Color(0xFFE9EDF5),
    surfaceVariant = Color(0xFF1D2634),
    onSurfaceVariant = Color(0xFFADB8C8),
    outline = Color(0xFF3A4658),
    surfaceTint = Color(0xFFFFC45C),
    error = Color(0xFFFF8E8E),
    onError = Color(0xFF3E0808)
)

@Composable
fun JacketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JacketColors, content = content)
}
