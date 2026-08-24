package com.typebit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import typebittorrent.composeapp.generated.resources.Res
import typebittorrent.composeapp.generated.resources.inter
import typebittorrent.composeapp.generated.resources.noto_sans_sc

/**
 * App font stack: Google **Inter** (Latin UI, variable wght axis) plus
 * **Noto Sans SC** (CJK, variable wght axis) — one variable file per family
 * renders every weight without bundling 18 static fonts.
 */
/**
 * App font stack: Google **Inter** (Latin UI, variable wght axis) plus
 * **Noto Sans SC** (CJK, variable wght axis) — one variable file per family
 * renders every weight without bundling 18 static fonts.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun typeBitFontFamily(): FontFamily = FontFamily(
    Font(Res.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 400f))),
    Font(Res.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 500f))),
    Font(Res.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 600f))),
    Font(Res.font.inter, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 700f))),
    Font(Res.font.inter, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 800f))),
    Font(Res.font.noto_sans_sc, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 400f))),
    Font(Res.font.noto_sans_sc, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 500f))),
    Font(Res.font.noto_sans_sc, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 600f))),
    Font(Res.font.noto_sans_sc, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 700f))),
    Font(Res.font.noto_sans_sc, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.Setting("wght", 800f))),
)

/**
 * MD3-Expressive typography: bolder weights and a slightly larger scale
 * than classic M3 (titles at 600-800 weight, tighter leading). Text sits on
 * the theme surface — the wallpaper is always dimmed/blurred behind it, so
 * contrast is guaranteed by the surface ramp, not by chance.
 */
@Composable
fun typebitTypography(): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.ExtraBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.ExtraBold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = typeBitFontFamily(),
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
