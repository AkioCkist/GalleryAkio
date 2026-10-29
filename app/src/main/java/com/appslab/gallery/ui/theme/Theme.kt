package com.appslab.gallery.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.appslab.gallery.R

enum class AppTheme(val label: String) {
    DEFAULT("Default Red"),
    OCEAN("Ocean Blue"),
    FOREST("Forest Green"),
    SUNSET("Sunset Orange"),
    ROYAL("Royal Purple"),
    MIDNIGHT("Midnight Black"),
}

/** Dot-matrix font used for headers and tabs. */
val CqMono = FontFamily(Font(R.font.cq_mono))
val RobotoNumbers = FontFamily(Font(R.font.roboto_medium_numbers))

@Immutable
data class CustomColors(
    val cardBackground: Color,
    val divider: Color,
    val shimmer: Color,
    val topBar: Color,
    val selectedItem: Color,
    val unselectedItem: Color,
    val border: Color,
    val overlay: Color,
)

val LocalCustomColors = staticCompositionLocalOf {
    CustomColors(
        Color(0xFF2A2A2A), Color(0xFF3A3A3A), Color(0xFF2C2C2C), Color(0xFF1E1E1E),
        Color(0xFFE53935), Color(0xFF808080), Color(0xFF3A3A3A), Color(0x80000000),
    )
}

private data class Palette(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val card: Color,
    val divider: Color,
    val error: Color,
)

private data class ThemeSpec(
    val dark: Palette,
    val light: Palette,
    /** Grey used for unselected tabs/icons, dark then light. */
    val unselectedDark: Color,
    val unselectedLight: Color,
    val overlayDark: Color,
    val overlayLight: Color,
)

private val specs = mapOf(
    AppTheme.DEFAULT to ThemeSpec(
        dark = Palette(Color(0xFFE53935), Color(0xFFFF6B6B), Color(0xFF121212), Color(0xFF1E1E1E), Color(0xFF2A2A2A), Color(0xFF3A3A3A), Color(0xFFCF6679)),
        light = Palette(Color(0xFFEF5350), Color(0xFFFF8A80), Color(0xFFFAFAFA), Color(0xFFFFFFFF), Color(0xFFF5F5F5), Color(0xFFE0E0E0), Color(0xFFE53935)),
        unselectedDark = Color(0xFF808080), unselectedLight = Color(0xFF606060),
        overlayDark = Color(0x80000000), overlayLight = Color(0x40000000),
    ),
    AppTheme.OCEAN to ThemeSpec(
        dark = Palette(Color(0xFF2196F3), Color(0xFF64B5F6), Color(0xFF0A1929), Color(0xFF1E3A5F), Color(0xFF2C4966), Color(0xFF3D5A7C), Color(0xFF90CAF9)),
        light = Palette(Color(0xFF42A5F5), Color(0xFF90CAF9), Color(0xFFE3F2FD), Color(0xFFFFFFFF), Color(0xFFBBDEFB), Color(0xFF90CAF9), Color(0xFF2196F3)),
        unselectedDark = Color(0xFF90CAF9), unselectedLight = Color(0xFF2196F3),
        overlayDark = Color(0x800A1929), overlayLight = Color(0x402196F3),
    ),
    AppTheme.FOREST to ThemeSpec(
        dark = Palette(Color(0xFF4CAF50), Color(0xFF81C784), Color(0xFF0D2818), Color(0xFF1B5E20), Color(0xFF2E7D32), Color(0xFF388E3C), Color(0xFFA5D6A7)),
        light = Palette(Color(0xFF66BB6A), Color(0xFFA5D6A7), Color(0xFFE8F5E9), Color(0xFFFFFFFF), Color(0xFFC8E6C9), Color(0xFFA5D6A7), Color(0xFF4CAF50)),
        unselectedDark = Color(0xFF81C784), unselectedLight = Color(0xFF4CAF50),
        overlayDark = Color(0x800D2818), overlayLight = Color(0x404CAF50),
    ),
    AppTheme.SUNSET to ThemeSpec(
        dark = Palette(Color(0xFFFF6F00), Color(0xFFFFA726), Color(0xFF1A0E00), Color(0xFF3E2723), Color(0xFF5D4037), Color(0xFF6D4C41), Color(0xFFFFB74D)),
        light = Palette(Color(0xFFFF8F00), Color(0xFFFFB74D), Color(0xFFFFF3E0), Color(0xFFFFFFFF), Color(0xFFFFE0B2), Color(0xFFFFCC80), Color(0xFFFF6F00)),
        unselectedDark = Color(0xFFFFA726), unselectedLight = Color(0xFFFF6F00),
        overlayDark = Color(0x801A0E00), overlayLight = Color(0x40FF6F00),
    ),
    AppTheme.ROYAL to ThemeSpec(
        dark = Palette(Color(0xFF9C27B0), Color(0xFFBA68C8), Color(0xFF12005E), Color(0xFF4A148C), Color(0xFF6A1B9A), Color(0xFF7B1FA2), Color(0xFFCE93D8)),
        light = Palette(Color(0xFFAB47BC), Color(0xFFCE93D8), Color(0xFFF3E5F5), Color(0xFFFFFFFF), Color(0xFFE1BEE7), Color(0xFFCE93D8), Color(0xFF9C27B0)),
        unselectedDark = Color(0xFFBA68C8), unselectedLight = Color(0xFF9C27B0),
        overlayDark = Color(0x8012005E), overlayLight = Color(0x409C27B0),
    ),
    AppTheme.MIDNIGHT to ThemeSpec(
        dark = Palette(Color(0xFF9E9E9E), Color(0xFFBDBDBD), Color(0xFF000000), Color(0xFF212121), Color(0xFF424242), Color(0xFF616161), Color(0xFFE0E0E0)),
        light = Palette(Color(0xFF757575), Color(0xFF9E9E9E), Color(0xFFFAFAFA), Color(0xFFFFFFFF), Color(0xFFEEEEEE), Color(0xFFBDBDBD), Color(0xFF9E9E9E)),
        unselectedDark = Color(0xFF757575), unselectedLight = Color(0xFF424242),
        overlayDark = Color(0x80000000), overlayLight = Color(0x40000000),
    ),
)

/** Primary colour of a theme, used for the swatches in Settings. */
fun AppTheme.swatch(dark: Boolean): Color = specs.getValue(this).let { if (dark) it.dark.primary else it.light.primary }

private fun colorScheme(p: Palette, dark: Boolean): ColorScheme {
    val on = if (dark) Color.White else Color.Black
    return if (dark) {
        darkColorScheme(
            primary = p.primary, onPrimary = Color.White,
            secondary = p.secondary, onSecondary = Color.White,
            background = p.background, onBackground = on,
            surface = p.surface, onSurface = on,
            surfaceVariant = p.card, onSurfaceVariant = on,
            outline = p.divider,
            error = p.error, onError = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = Color.White,
            secondary = p.secondary, onSecondary = Color.White,
            background = p.background, onBackground = on,
            surface = p.surface, onSurface = on,
            surfaceVariant = p.card, onSurfaceVariant = on,
            outline = p.divider,
            error = p.error, onError = Color.White,
        )
    }
}

private val GalleryTypography = Typography().let { base ->
    base.copy(
        headlineMedium = TextStyle(fontFamily = CqMono, fontSize = 28.sp, letterSpacing = 1.sp),
        titleLarge = TextStyle(fontFamily = CqMono, fontSize = 22.sp, letterSpacing = 1.sp),
        titleMedium = TextStyle(fontFamily = CqMono, fontSize = 16.sp, letterSpacing = 1.sp),
        labelLarge = base.labelLarge.copy(fontFamily = CqMono, letterSpacing = 1.sp),
    )
}

@Composable
fun GalleryTheme(theme: AppTheme, darkMode: Boolean, content: @Composable () -> Unit) {
    val spec = specs.getValue(theme)
    val palette = if (darkMode) spec.dark else spec.light
    val custom = CustomColors(
        cardBackground = palette.card,
        divider = palette.divider,
        shimmer = if (darkMode) Color(0xFF2C2C2C) else palette.card,
        topBar = palette.surface,
        selectedItem = palette.primary,
        unselectedItem = if (darkMode) spec.unselectedDark else spec.unselectedLight,
        border = palette.divider,
        overlay = if (darkMode) spec.overlayDark else spec.overlayLight,
    )
    CompositionLocalProvider(LocalCustomColors provides custom) {
        MaterialTheme(colorScheme = colorScheme(palette, darkMode), typography = GalleryTypography, content = content)
    }
}
