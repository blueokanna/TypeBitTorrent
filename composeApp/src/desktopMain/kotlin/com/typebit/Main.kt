package com.typebit

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.typebit.app.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "TypeBit — BitTorrent Client",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        App()
    }
}
