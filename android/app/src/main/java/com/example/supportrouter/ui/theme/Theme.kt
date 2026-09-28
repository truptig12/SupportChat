package com.example.supportrouter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette sampled from the mockups (dark app screens, Lloyds-style greens). */
object Brand {
    val Green = Color(0xFF1E8C4E)          // primary buttons
    val GreenBright = Color(0xFF2FBF6B)    // outlines / focus
    val GreenDark = Color(0xFF0B5E36)      // avatar, headers
    val Mint = Color(0xFFE4F4EA)           // Ask card, "no outages" card
    val MintBorder = Color(0xFFB9E2C8)
    val OnMint = Color(0xFF0E2E1D)
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF151515)
    val SurfaceHigh = Color(0xFF1F1F1F)
    val Outline = Color(0xFF7A7A7A)
    val OutlineSoft = Color(0xFF3A3A3A)
    val TextSecondary = Color(0xFFB5B5B5)
    val Error = Color(0xFFE5484D)
    val ErrorSoft = Color(0xFF3A1416)
    val Amber = Color(0xFFF5A524)
    val AmberSoft = Color(0xFFFFF3DC)
    val OnAmber = Color(0xFF3D2600)
    val Bubble = Color(0xFFF1F1F5)
    val OnBubble = Color(0xFF151515)
    val UserBubble = Color(0xFF1E8C4E)
    val Ai = Color(0xFFB7A6FF)             // suggestion tag
    val AiSoft = Color(0xFF231C3D)
}

private val scheme = darkColorScheme(
    primary = Brand.Green,
    onPrimary = Color.White,
    secondary = Brand.GreenBright,
    background = Brand.Background,
    onBackground = Color.White,
    surface = Brand.Background,
    onSurface = Color.White,
    surfaceVariant = Brand.Surface,
    onSurfaceVariant = Brand.TextSecondary,
    outline = Brand.Outline,
    error = Brand.Error,
)

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun LloydsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
