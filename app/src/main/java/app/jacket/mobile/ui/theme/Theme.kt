package app.jacket.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LampaColors = darkColorScheme(
    primary = Color(0xFFFFC45C),
    onPrimary = Color(0xFF2A1A00),
    secondary = Color(0xFF62D7C4),
    onSecondary = Color(0xFF062D29),
    background = Color(0xFF080B11),
    onBackground = Color(0xFFE9EDF5),
    surface = Color(0xFF111722),
    onSurface = Color(0xFFE9EDF5),
    surfaceVariant = Color(0xFF1D2634),
    onSurfaceVariant = Color(0xFFADB8C8)
)

private val LampaTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.2).sp
        ),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold)
    )
}

@Composable
fun LampaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LampaColors,
        typography = LampaTypography,
        shapes = Shapes(),
        content = content
    )
}
