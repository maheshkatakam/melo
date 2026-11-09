package com.maheshkatakam.melo.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.maheshkatakam.melo.R
import com.maheshkatakam.melo.data.Settings
import com.maheshkatakam.melo.data.ThemeMode

val Coral = Color(0xFFFF7A59)
val Violet = Color(0xFF8B5CF6)

private val DarkColors = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF240B04),
    primaryContainer = Color(0xFF4A1F12),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E2350),
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFF38BDF8),
    background = Color(0xFF0F1014),
    onBackground = Color(0xFFF3F3F6),
    surface = Color(0xFF0F1014),
    onSurface = Color(0xFFF3F3F6),
    surfaceVariant = Color(0xFF23252D),
    onSurfaceVariant = Color(0xFFA3A6B1),
    surfaceContainerLowest = Color(0xFF0B0C0F),
    surfaceContainerLow = Color(0xFF14151A),
    surfaceContainer = Color(0xFF181A20),
    surfaceContainerHigh = Color(0xFF1F2128),
    surfaceContainerHighest = Color(0xFF282A32),
    outline = Color(0xFF3A3D47),
    outlineVariant = Color(0xFF2A2C34),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE2552F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3B0E02),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE5FF),
    onSecondaryContainer = Color(0xFF240A5C),
    tertiary = Color(0xFF0284C7),
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF15161A),
    surface = Color(0xFFFAFAFB),
    onSurface = Color(0xFF15161A),
    surfaceVariant = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFF5D606B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F6F8),
    surfaceContainer = Color(0xFFF1F1F4),
    surfaceContainerHigh = Color(0xFFEBEBEF),
    surfaceContainerHighest = Color(0xFFE4E4E9),
    outline = Color(0xFFC4C6CF),
    outlineVariant = Color(0xFFE0E1E6),
)

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val base = Typography()

private val MeloTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 30.sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = base.bodyLarge.copy(fontFamily = Poppins, fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Poppins, fontSize = 13.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Poppins, fontSize = 12.sp),
    labelLarge = base.labelLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
)

@Composable
fun isDarkTheme(settings: Settings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
fun MeloTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val context = LocalContext.current
    val colors = when {
        settings.materialYou && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = MeloTypography, content = content)
}
