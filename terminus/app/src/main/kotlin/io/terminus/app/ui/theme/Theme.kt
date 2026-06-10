package io.terminus.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE63946),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFA8DADC),
    onSecondary = Color(0xFF102027),
    tertiary = Color(0xFF457B9D),
    background = Color(0xFF101418),
    surface = Color(0xFF161B21),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC62F3B),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2E6F73),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF1D3557),
)

@Composable
fun TerminusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
