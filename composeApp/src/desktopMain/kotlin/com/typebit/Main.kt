package com.typebit

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.typebit.app.App
import java.awt.Dimension

/** Smallest window the layout stays correct at (sidebar + single-row bar). */
private const val MIN_W = 900
private const val MIN_H = 600

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "TypeBit — BitTorrent Client",
        state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
    ) {
        // Enforce a minimum window size: below it the single-row toolbar and
        // the sidebar collapse. The user can still resize freely above this.
        // `window` is the FrameWindowScope's ComposeWindow (AWT JFrame).
        DisposableEffect(Unit) {
            window.minimumSize = Dimension(MIN_W, MIN_H)
            onDispose {}
        }
        App()
    }
}
