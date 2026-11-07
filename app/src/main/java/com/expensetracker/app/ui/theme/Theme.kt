package com.expensetracker.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.app.data.prefs.ThemeMode

data class Accent(val name: String, val primary: Color, val tertiary: Color)

val Accents = listOf(
    Accent("Indigo", Color(0xFF4F46E5), Color(0xFF7C3AED)),
    Accent("Emerald", Color(0xFF059669), Color(0xFF0EA5E9)),
    Accent("Ocean", Color(0xFF0369A1), Color(0xFF06B6D4)),
    Accent("Violet", Color(0xFF7C3AED), Color(0xFFDB2777)),
    Accent("Rose", Color(0xFFE11D48), Color(0xFFF97316)),
    Accent("Amber", Color(0xFFB45309), Color(0xFFDC2626)),
    Accent("Graphite", Color(0xFF334155), Color(0xFF0EA5E9)),
)

/** Money colours used across lists and charts, tuned per theme for contrast. */
@Immutable
data class FinanceColors(
    val income: Color,
    val expense: Color,
    val neutral: Color,
    val warning: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
)

val LocalFinanceColors = staticCompositionLocalOf {
    FinanceColors(Color(0xFF15803D), Color(0xFFDC2626), Color(0xFF64748B), Color(0xFFD97706), Color(0xFF4F46E5), Color(0xFF7C3AED), Color.White)
}

private fun lightScheme(a: Accent): ColorScheme = lightColorScheme(
    primary = a.primary,
    onPrimary = Color.White,
    primaryContainer = lerp(a.primary, Color.White, 0.86f),
    onPrimaryContainer = lerp(a.primary, Color.Black, 0.45f),
    secondary = lerp(a.primary, Color(0xFF475569), 0.55f),
    onSecondary = Color.White,
    secondaryContainer = lerp(a.primary, Color.White, 0.9f),
    onSecondaryContainer = lerp(a.primary, Color.Black, 0.55f),
    tertiary = a.tertiary,
    onTertiary = Color.White,
    tertiaryContainer = lerp(a.tertiary, Color.White, 0.86f),
    onTertiaryContainer = lerp(a.tertiary, Color.Black, 0.45f),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF15171C),
    surface = Color(0xFFF5F6FA),
    onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFE9EBF2),
    onSurfaceVariant = Color(0xFF5B6070),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F1F6),
    surfaceContainerHighest = Color(0xFFE7E9F0),
    outline = Color(0xFFC4C8D4),
    outlineVariant = Color(0xFFE3E5EC),
    error = Color(0xFFDC2626),
)

private fun darkScheme(a: Accent): ColorScheme {
    val primary = lerp(a.primary, Color.White, 0.35f)
    return darkColorScheme(
        primary = primary,
        onPrimary = lerp(a.primary, Color.Black, 0.6f),
        primaryContainer = lerp(a.primary, Color(0xFF0F1115), 0.55f),
        onPrimaryContainer = lerp(a.primary, Color.White, 0.75f),
        secondary = lerp(primary, Color(0xFF94A3B8), 0.5f),
        onSecondary = Color(0xFF0F1115),
        secondaryContainer = lerp(a.primary, Color(0xFF0F1115), 0.75f),
        onSecondaryContainer = lerp(a.primary, Color.White, 0.8f),
        tertiary = lerp(a.tertiary, Color.White, 0.3f),
        onTertiary = Color(0xFF0F1115),
        tertiaryContainer = lerp(a.tertiary, Color(0xFF0F1115), 0.6f),
        onTertiaryContainer = lerp(a.tertiary, Color.White, 0.75f),
        background = Color(0xFF0F1115),
        onBackground = Color(0xFFECEDF2),
        surface = Color(0xFF0F1115),
        onSurface = Color(0xFFECEDF2),
        surfaceVariant = Color(0xFF252932),
        onSurfaceVariant = Color(0xFFA2A7B5),
        surfaceContainerLowest = Color(0xFF0B0D10),
        surfaceContainerLow = Color(0xFF14161B),
        surfaceContainer = Color(0xFF181B21),
        surfaceContainerHigh = Color(0xFF20232B),
        surfaceContainerHighest = Color(0xFF2A2E37),
        outline = Color(0xFF3B404C),
        outlineVariant = Color(0xFF2A2E37),
        error = Color(0xFFF87171),
    )
}

private val AppTypography = Typography().let { t ->
    val tight = TextStyle(letterSpacing = (-0.2).sp)
    t.copy(
        displaySmall = t.displaySmall.merge(tight).copy(fontWeight = FontWeight.SemiBold, fontSize = 34.sp),
        headlineMedium = t.headlineMedium.merge(tight).copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.merge(tight).copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Tabular figures so amounts line up in lists. */
val AmountStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun AppTheme(themeMode: ThemeMode, accentIndex: Int, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = Accents.getOrElse(accentIndex) { Accents[0] }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkScheme(accent)
        else -> lightScheme(accent)
    }
    val finance = if (dark) {
        FinanceColors(Color(0xFF4ADE80), Color(0xFFF87171), Color(0xFF94A3B8), Color(0xFFFBBF24), lerp(scheme.primary, Color.Black, 0.35f), lerp(scheme.tertiary, Color.Black, 0.45f), Color.White)
    } else {
        FinanceColors(Color(0xFF15803D), Color(0xFFDC2626), Color(0xFF64748B), Color(0xFFD97706), scheme.primary, lerp(scheme.primary, scheme.tertiary, 0.7f), Color.White)
    }
    CompositionLocalProvider(LocalFinanceColors provides finance) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
