package app.jacket.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JacketColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFFFFB86B),
    background = Color(0xFF0B1220),
    surface = Color(0xFF121D2E),
    surfaceVariant = Color(0xFF202D40),
    error = Color(0xFFFCA5A5)
)

@Composable
fun JacketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JacketColors, content = content)
}
