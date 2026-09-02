package com.starlink.diagnostic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.starlink.diagnostic.R

// ── Space palette (same identity as V1 / the PWA) ────────────────────────
val Navy = Color(0xFF0B1026)
val NavyCard = Color(0xFF121A38)
val GlassSurface = Color(0xD9101833)
val Hairline = Color(0x1F38BDF8)
val SkyBlue = Color(0xFF38BDF8)
val SkySoft = Color(0xFF7DD3FC)
val GoodGreen = Color(0xFF4ADE80)
val WarnAmber = Color(0xFFFBBF24)
val BadRed = Color(0xFFF87171)
val NeutralGrey = Color(0xFF64748B)
val StrongText = Color(0xFFE2E8F0)
val MutedText = Color(0xFF94A3B8)

val CairoFamily = FontFamily(
    Font(R.font.cairo, FontWeight.Normal),
    Font(R.font.cairo, FontWeight.Medium),
    Font(R.font.cairo, FontWeight.SemiBold),
    Font(R.font.cairo, FontWeight.Bold),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = CairoFamily, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = CairoFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = CairoFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = CairoFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = CairoFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = CairoFamily, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp,
    ),
)

private val SpaceColors = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFF06263B),
    primaryContainer = Color(0xFF14486B),
    onPrimaryContainer = SkySoft,
    secondary = SkySoft,
    onSecondary = Color(0xFF06263B),
    background = Navy,
    onBackground = StrongText,
    surface = NavyCard,
    onSurface = StrongText,
    surfaceVariant = Color(0xFF1B2547),
    onSurfaceVariant = MutedText,
    error = BadRed,
    onError = Color(0xFF3B0A0A),
    outline = Color(0xFF2A3A63),
)

@Composable
fun StarlinkTheme(content: @Composable () -> Unit) {
    // The app is dark-space by design; keep it dark regardless of system.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = SpaceColors,
        typography = AppTypography,
        content = content,
    )
}
