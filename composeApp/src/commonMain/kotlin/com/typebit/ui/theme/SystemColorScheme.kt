package com.typebit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal expect fun systemColorScheme(dark: Boolean): ColorScheme?

@Composable
internal expect fun ApplySystemBarAppearance(darkTheme: Boolean)
