package com.typebit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.typebit.data.FontChoice
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource
import typebittorrent.composeapp.generated.resources.Res
import typebittorrent.composeapp.generated.resources.inter
import typebittorrent.composeapp.generated.resources.noto_sans_sc
import typebittorrent.composeapp.generated.resources.opensans
import typebittorrent.composeapp.generated.resources.roboto

/**
 * Bundled Google Font stacks. Each stack is one variable font per family
 * (wght axis) registered at every UI weight, so a single ~0.5-17 MB file
 * renders all weights — no static-font explosion. The Latin font comes
 * first in the family; CJK glyphs fall back to Noto Sans SC (registered
 * last), so Chinese text always renders from a bundled font.
 *
 * `DEFAULT` = Inter (Latin) + Noto Sans SC (CJK). Inter is compact for
 * Latin UI and Noto Sans SC is the reference CJK face — the pair stays
 * legible on every density, which is why it is the app default.
 */

/** The five UI weights a variable font registers (wght axis values). */
private val UI_WEIGHTS =
        listOf(
                FontWeight.Normal to 400f,
                FontWeight.Medium to 500f,
                FontWeight.SemiBold to 600f,
                FontWeight.Bold to 700f,
                FontWeight.ExtraBold to 800f,
        )

/** Build the [Font] list for one variable font resource at every UI weight. */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun variableFonts(resource: FontResource): List<Font> =
        UI_WEIGHTS.map { (weight, wght) ->
            Font(
                    resource,
                    weight,
                    variationSettings =
                            FontVariation.Settings(FontVariation.Setting("wght", wght)),
            )
        }

/**
 * Resolve the bundled [FontFamily] for a [FontChoice]. The Latin font (when
 * present) is registered first so Latin text shapes from it; Noto Sans SC is
 * always registered last as the CJK fallback. `SYSTEM` returns the platform
 * default.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun fontFamilyFor(choice: FontChoice): FontFamily =
        when (choice) {
            FontChoice.SYSTEM -> FontFamily.Default
            FontChoice.NOTO_SANS ->
                    FontFamily(*variableFonts(Res.font.noto_sans_sc).toTypedArray())
            FontChoice.ROBOTO ->
                    FontFamily(*(variableFonts(Res.font.roboto) + variableFonts(Res.font.noto_sans_sc)).toTypedArray())
            FontChoice.OPEN_SANS ->
                    FontFamily(*(variableFonts(Res.font.opensans) + variableFonts(Res.font.noto_sans_sc)).toTypedArray())
            FontChoice.DEFAULT ->
                    FontFamily(*(variableFonts(Res.font.inter) + variableFonts(Res.font.noto_sans_sc)).toTypedArray())
        }

@Composable
fun typebitTypography(fontChoice: FontChoice = FontChoice.DEFAULT): Typography {
    val ff = fontFamilyFor(fontChoice)
    return Typography(
    displayLarge = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = ff,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    )
}
