package com.typebit.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.typebit.data.SettingsRepository
import com.typebit.data.ThemeMode
import com.typebit.data.TorrentRepository
import com.typebit.engine.NativeTorrentEngine
import com.typebit.platform.PlatformBackHandler
import com.typebit.store.AppStore
import com.typebit.ui.screens.about.AboutScreen
import com.typebit.ui.screens.add.AddTorrentScreen
import com.typebit.ui.screens.main.MainScreen
import com.typebit.ui.screens.rss.RssScreen
import com.typebit.ui.screens.search.SearchScreen
import com.typebit.ui.screens.settings.SettingsScreen
import com.typebit.ui.theme.TypeBitTheme
import com.typebit.ui.wallpaper.averageBrightness
import com.typebit.ui.wallpaper.extractSeedColor
import com.typebit.ui.wallpaper.loadWallpaperBitmap
import com.typebit.ui.wallpaper.prepareWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Route {
    MAIN,
    ADD,
    SETTINGS,
    SEARCH,
    RSS,
    ABOUT
}

private const val DEFAULT_SEED = 0xFF0061A4.toInt()

@Composable
fun App() {
    val store = remember { createAppStore() }
    DisposableEffect(Unit) {
        store.start()
        onDispose { store.stop() }
    }

    val state by store.state.collectAsState()
    val appearance = state.settings.appearance

    val darkTheme =
            when (appearance.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.AMOLED -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(appearance.wallpaperPath, appearance.wallpaperEnabled) {
        wallpaper =
                if (appearance.wallpaperEnabled) {
                    withContext(Dispatchers.IO) { loadWallpaperBitmap(appearance.wallpaperPath) }
                } else {
                    null
                }
    }

    var blurredWallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(wallpaper, appearance.wallpaperEnabled, appearance.blurRadiusPx) {
        if (!appearance.wallpaperEnabled || wallpaper == null) {
            blurredWallpaper = null
        } else {
            blurredWallpaper =
                    withContext(Dispatchers.Default) {
                        prepareWallpaper(wallpaper!!, appearance.blurRadiusPx)
                    }
        }
    }

    val wallpaperBrightness =
            remember(wallpaper) { wallpaper?.let { averageBrightness(it) } ?: 0.5f }

    val seedArgb =
            remember(wallpaper, appearance.seedOverride) {
                appearance.seedOverride ?: wallpaper?.let { extractSeedColor(it) } ?: DEFAULT_SEED
            }

    TypeBitTheme(
            seedArgb = seedArgb,
            darkTheme = darkTheme,
            amoled = appearance.themeMode == ThemeMode.AMOLED,
            wallpaper = blurredWallpaper,
            wallpaperEnabled = appearance.wallpaperEnabled,
            wallpaperDim = appearance.dimAlpha,
            wallpaperFit = appearance.wallpaperFit,
            wallpaperOffsetY = appearance.wallpaperOffsetY,
            wallpaperBrightness = wallpaperBrightness,
    ) { AppRoot(store) }
}

internal fun createAppStore(): AppStore =
        AppStore(
                engine = NativeTorrentEngine(),
                settingsRepo = SettingsRepository(),
                torrentRepo = TorrentRepository(),
        )

@Composable
internal fun AppRoot(store: AppStore) {
    val state by store.state.collectAsState()
    var route by remember { mutableStateOf(Route.MAIN) }

    val onRoute: (Route) -> Unit = { route = it }
    PlatformBackHandler(enabled = route != Route.MAIN) { route = Route.MAIN }

    AnimatedContent(
            targetState = route,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally { it / 24 }).togetherWith(
                        fadeOut(tween(160)) + slideOutHorizontally { -it / 32 }
                )
            },
            label = "route",
    ) { r ->
        when (r) {
            Route.MAIN ->
                    MainScreen(
                            state = state,
                            store = store,
                            onRoute = onRoute,
                    )
            Route.ADD ->
                    AddTorrentScreen(
                            state = state,
                            store = store,
                            onBack = { onRoute(Route.MAIN) },
                    )
            Route.SETTINGS ->
                    SettingsScreen(
                            state = state,
                            store = store,
                            onBack = { onRoute(Route.MAIN) },
                    )
            Route.SEARCH ->
                    SearchScreen(
                            state = state,
                            store = store,
                            onBack = { onRoute(Route.MAIN) },
                    )
            Route.RSS ->
                    RssScreen(
                            state = state,
                            store = store,
                            onBack = { onRoute(Route.MAIN) },
                    )
            Route.ABOUT ->
                    AboutScreen(
                            onBack = { onRoute(Route.MAIN) },
                    )
        }
    }
}
