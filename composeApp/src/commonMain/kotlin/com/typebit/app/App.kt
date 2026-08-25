package com.typebit.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Top-level destinations. Desktop uses a dialog for ADD; Android a screen. */
enum class Route { MAIN, ADD, SETTINGS, SEARCH, RSS, ABOUT }

/** Default Monet seed when there is no wallpaper and no manual override. */
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

    // Resolve SYSTEM to the actual OS preference; AMOLED is dark + black.
    val darkTheme = when (appearance.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    // Wallpaper decode + luminance extraction happen OFF the main thread so
    // a large image never blocks the UI (ANR risk on Android). The decoded
    // bitmap is kept in a state, keyed by path, so switching wallpapers
    // cancels the stale load and releases the previous bitmap to GC.
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(appearance.wallpaperPath, appearance.wallpaperEnabled) {
        wallpaper = if (appearance.wallpaperEnabled) {
            withContext(Dispatchers.IO) { loadWallpaperBitmap(appearance.wallpaperPath) }
        } else {
            null
        }
    }

    // The blur is applied ONCE here, off the UI thread, and cached — the
    // theme's wallpaper layer only ever draws a static (pre-blurred) bitmap.
    // Previously the blur lived in the layer's `Modifier.blur`, which re-ran
    // a full-screen GPU blur on every animation frame (route transitions,
    // opening settings, slider drags) and was the source of the jank.
    var blurredWallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(wallpaper, appearance.wallpaperEnabled, appearance.blurRadiusPx) {
        if (!appearance.wallpaperEnabled || wallpaper == null) {
            blurredWallpaper = null
        } else {
            blurredWallpaper = withContext(Dispatchers.Default) {
                prepareWallpaper(wallpaper!!, appearance.blurRadiusPx)
            }
        }
    }

    // Average luminance of the wallpaper drives the auto-contrast scrim so
    // text stays readable on bright/dark images regardless of the theme.
    val wallpaperBrightness = remember(wallpaper) {
        wallpaper?.let { averageBrightness(it) } ?: 0.5f
    }

    // Dynamic-Color seed: manual override wins, then the wallpaper's
    // extracted seed, then a sensible default blue.
    val seedArgb = remember(wallpaper, appearance.seedOverride) {
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
    ) {
        AppRoot(store)
    }
}

/** Wires the concrete engine/repositories into the store — the composition root. */
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

    // Android back gesture: sub-screens return to MAIN instead of exiting to
    // the home screen. When MAIN is showing, the mobile screen consumes back
    // for its detail page first (inner handler wins); with nothing open the
    // system default (leave the app) applies.
    PlatformBackHandler(enabled = route != Route.MAIN) { route = Route.MAIN }

    // MD3E navigation transition: the outgoing screen fades/slides out
    // while the incoming one springs in from the right.
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInHorizontally { it / 24 })
                .togetherWith(fadeOut(tween(160)) + slideOutHorizontally { -it / 32 })
        },
        label = "route",
    ) { r ->
        when (r) {
            Route.MAIN -> MainScreen(
                state = state,
                store = store,
                onRoute = onRoute,
            )
            Route.ADD -> AddTorrentScreen(
                state = state,
                store = store,
                onBack = { onRoute(Route.MAIN) },
            )
            Route.SETTINGS -> SettingsScreen(
                state = state,
                store = store,
                onBack = { onRoute(Route.MAIN) },
            )
            Route.SEARCH -> SearchScreen(
                state = state,
                store = store,
                onBack = { onRoute(Route.MAIN) },
            )
            Route.RSS -> RssScreen(
                state = state,
                store = store,
                onBack = { onRoute(Route.MAIN) },
            )
            Route.ABOUT -> AboutScreen(
                onBack = { onRoute(Route.MAIN) },
            )
        }
    }
}
