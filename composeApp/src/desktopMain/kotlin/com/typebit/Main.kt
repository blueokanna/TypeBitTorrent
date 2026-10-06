package com.typebit

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.typebit.app.App
import com.typebit.app.appStore
import com.typebit.webui.Headless
import com.typebit.webui.WebUiHost
import java.awt.Dimension

/** Smallest window the layout stays correct at (sidebar + single-row bar). */
private const val MIN_W = 900
private const val MIN_H = 600

/**
 * Two front ends from one binary:
 * * default → the Compose desktop window,
 * * `--headless` (or `TYPEBIT_HEADLESS=1`) → the WebUI server for a NAS
 *   (飞牛 fnOS / Unraid / any Linux box without a display). See [Headless].
 */
fun main(args: Array<String>) {
    if (Headless.requested(args)) {
        kotlin.system.exitProcess(Headless.run(args))
    }
    desktopGui()
}

private fun desktopGui() = application {
    // The embedded WebUI follows 设置 → WebUI live (enable/disable, port, LAN).
    val webUi = remember { WebUiHost(appStore) }
    Window(
        // Flush resume data and join the engine worker BEFORE the JVM exits:
        // a fire-and-forget teardown could be cut off mid-write here, while
        // the Android path deliberately keeps it off the main thread.
        onCloseRequest = {
            webUi.dispose()
            appStore.stopBlocking()
            exitApplication()
        },
        title = "TypeBit — BitTorrent Client",
        state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
    ) {
        // Enforce a minimum window size: below it the single-row toolbar and
        // the sidebar collapse. The user can still resize freely above this.
        // `window` is the FrameWindowScope's ComposeWindow (AWT JFrame).
        DisposableEffect(Unit) {
            window.minimumSize = Dimension(MIN_W, MIN_H)
            webUi.start()
            onDispose {}
        }
        App()
    }
}
