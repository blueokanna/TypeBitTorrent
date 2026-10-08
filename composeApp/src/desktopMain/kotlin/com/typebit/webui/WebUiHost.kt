package com.typebit.webui

import com.typebit.data.WebUiSettings
import com.typebit.store.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the embedded WebUI server aligned with the stored [WebUiSettings] in
 * the desktop build, so toggling "启用 WebUI", changing the port or allowing
 * LAN access takes effect immediately instead of "after a restart".
 *
 * Restart policy: the socket is rebound only when something that affects it
 * changes (enabled / port / LAN access). Username and password are read live
 * through the settings accessor, so editing a credential does not drop the
 * connections of the other browsers.
 *
 * Failure is never fatal: if the port is taken we log it, the desktop UI stays
 * fully usable, and the next settings change retries.
 */
internal class WebUiHost(private val store: AppStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: WebUiServer? = null
    private var boundTo: Endpoint? = null

    private data class Endpoint(val host: String, val port: Int)

    fun start() {
        scope.launch {
            store.state.collectLatest { state ->
                val settings = state.settings.webUi
                if (!settings.enabled) {
                    stop()
                    return@collectLatest
                }
                val endpoint =
                    Endpoint(
                        host = if (settings.remoteAccess) BIND_ALL else BIND_LOOPBACK,
                        port = settings.port.coerceIn(1, 65_535),
                    )
                if (endpoint == boundTo) return@collectLatest
                stop()
                bind(settings, endpoint)
            }
        }
    }

    private fun bind(settings: WebUiSettings, endpoint: Endpoint) {
        val server =
            WebUiServer(
                store = store,
                settings = { store.state.value.settings.webUi },
                bindAddress = endpoint.host,
                port = endpoint.port,
                log = { println(it) },
            )
        runCatching { server.start() }
            .onSuccess {
                running = server
                boundTo = endpoint
            }
            .onFailure { error ->
                // A clear, actionable message beats a stack trace in the log.
                System.err.println(
                    "WebUI 无法监听 ${endpoint.host}:${endpoint.port} — " +
                        "${error.message ?: error.javaClass.simpleName}。" +
                        "若是端口占用，请在 设置 → WebUI 中更换端口；桌面客户端本身不受影响。"
                )
                runCatching { server.stop() }
                boundTo = null
            }
    }

    fun stop() {
        running?.let { runCatching { it.stop() } }
        running = null
        boundTo = null
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private companion object {
        const val BIND_LOOPBACK = "127.0.0.1"
        const val BIND_ALL = "0.0.0.0"
    }
}
