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

private const val MIN_W = 900
private const val MIN_H = 600

/** Two front ends: the Compose window by default, the WebUI server (`--headless`) for a NAS. */
fun main(args: Array<String>) {
    if (Headless.requested(args)) {
        kotlin.system.exitProcess(Headless.run(args))
    }
    desktopGui()
}

private fun desktopGui() = application {
    val webUi = remember { WebUiHost(appStore) }
    Window(
        // Flush resume data and join the engine worker before the JVM exits: a
        // fire-and-forget teardown can be cut off mid-write.
        onCloseRequest = {
            webUi.dispose()
            appStore.stopBlocking()
            exitApplication()
        },
        title = "TypeBit — BitTorrent Client",
        state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
    ) {
        DisposableEffect(Unit) {
            window.minimumSize = Dimension(MIN_W, MIN_H)
            webUi.start()
            onDispose {}
        }
        App()
    }
}
