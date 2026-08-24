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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.typebit.data.SettingsRepository
import com.typebit.data.ThemeMode
import com.typebit.data.TorrentRepository
import com.typebit.engine.NativeTorrentEngine
import com.typebit.store.AppStore
import com.typebit.ui.screens.about.AboutScreen
import com.typebit.ui.screens.add.AddTorrentScreen
import com.typebit.ui.screens.main.MainScreen
import com.typebit.ui.screens.rss.RssScreen
import com.typebit.ui.screens.search.SearchScreen
import com.typebit.ui.screens.settings.SettingsScreen
import com.typebit.ui.theme.TypeBitTheme
import com.typebit.ui.wallpaper.extractSeedColor
import com.typebit.ui.wallpaper.loadWallpaperBitmap
import kotlinx.coroutines.CoroutineScope

/** Top-level destinations. Desktop uses a dialog for ADD; Android a screen. */
enum class Route { MAIN, ADD, SETTINGS, SEARCH, RSS, ABOUT }

/** Default Monet seed when there is no wallpaper and no manual override. */
private const val DEFAULT_SEED = 0xFF0061A4.toInt()

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val store = remember { createAppStore(scope) }
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

    // Load the wallpaper once per path (heavy decode stays off the UI thread
    // only on Android; desktop decodes are fast enough to be cheap here).
    val wallpaper = remember(appearance.wallpaperPath, appearance.wallpaperEnabled) {
        if (appearance.wallpaperEnabled) loadWallpaperBitmap(appearance.wallpaperPath) else null
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
        wallpaper = wallpaper,
        wallpaperEnabled = appearance.wallpaperEnabled,
        wallpaperDim = appearance.dimAlpha,
        wallpaperBlurPx = appearance.blurRadiusPx,
        wallpaperFit = appearance.wallpaperFit,
        wallpaperOffsetY = appearance.wallpaperOffsetY,
    ) {
        AppRoot(store)
    }
}

/** Wires the concrete engine/repositories into the store — the composition root. */
internal fun createAppStore(scope: CoroutineScope): AppStore =
    AppStore(
        engine = NativeTorrentEngine(),
        settingsRepo = SettingsRepository(),
        torrentRepo = TorrentRepository(),
        scope = scope,
    )

@Composable
internal fun AppRoot(store: AppStore) {
    val state by store.state.collectAsState()
    var route by remember { mutableStateOf(Route.MAIN) }

    val onRoute: (Route) -> Unit = { route = it }

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
