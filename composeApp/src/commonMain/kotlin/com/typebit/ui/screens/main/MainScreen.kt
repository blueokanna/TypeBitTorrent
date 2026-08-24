package com.typebit.ui.screens.main

import androidx.compose.runtime.Composable
import com.typebit.app.Route
import com.typebit.platform.Platform
import com.typebit.store.AppState
import com.typebit.store.AppStore

/** Adaptive entry: desktop gets the sidebar+table layout, mobile gets bottom nav. */
@Composable
fun MainScreen(
    state: AppState,
    store: AppStore,
    onRoute: (Route) -> Unit,
) {
    if (Platform.isDesktop) {
        DesktopMainScreen(state = state, store = store, onRoute = onRoute)
    } else {
        MobileMainScreen(state = state, store = store, onRoute = onRoute)
    }
}
