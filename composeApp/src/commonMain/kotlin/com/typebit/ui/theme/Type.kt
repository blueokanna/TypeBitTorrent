package com.typebit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.typebit.data.FontChoice
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource
import typebittorrent.composeapp.generated.resources.Res
import typebittorrent.composeapp.generated.resources.inter
import typebittorrent.composeapp.generated.resources.noto_sans_sc
import typebittorrent.composeapp.generated.resources.opensans
import typebittorrent.composeapp.generated.resources.roboto

/** Bundled font stacks: one variable font (wght axis) per family renders every UI weight,
 * with the Latin face first and Noto Sans SC last so Chinese text always resolves from
 * a bundled font. */

private val UI_WEIGHTS =
        listOf(
                FontWeight.Normal to 400f,
                FontWeight.Medium to 500f,
                FontWeight.SemiBold to 600f,
                FontWeight.Bold to 700f,
                FontWeight.ExtraBold to 800f,
        )

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
    val family = fontFamilyFor(fontChoice)
    return remember(family) { Typography(fontFamily = family) }
}
