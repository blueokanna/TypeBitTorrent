package com.typebit.webui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.typebit.data.AppSettings
import com.typebit.data.RssRepository
import com.typebit.data.WebUiSettings
import com.typebit.data.decodeSettings
import com.typebit.data.fetchRssFeed
import com.typebit.engine.PeerDto
import com.typebit.model.Torrent
import com.typebit.platform.Platform
import com.typebit.search.MagnetIndexEngine
import com.typebit.search.NyaaEngine
import com.typebit.search.PirateBayEngine
import com.typebit.search.SearchHttpClient
import com.typebit.search.TorrentSearchClient
import com.typebit.search.X1337xEngine
import com.typebit.store.AppStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The built-in WebUI server — what makes TypeBitTorrent usable on a headless
 * box (飞牛 fnOS, Unraid, any Linux NAS) where a Compose window cannot exist.
 *
 * It is deliberately dependency-free (`com.sun.net.httpserver` ships with the
 * JDK) and drives the SAME [AppStore] the desktop UI drives, so a browser is a
 * full client: transfers, per-file priorities, renames, trackers, peers,
 * settings, engine statistics, search, RSS and torrent creation all execute
 * the identical engine commands. Nothing here is a read-only mirror and
 * nothing is faked.
 *
 * Security posture (the endpoint is reachable from the LAN by definition):
 * * every mutating request must carry `X-TypeBit: 1`; a cross-site form,
 *   `<img>` or `<script>` cannot set a custom header without a preflight we
 *   never answer, so that header *is* the CSRF defence — together with
 *   `SameSite=Strict`, `HttpOnly` session cookies;
 * * sessions are 256-bit opaque tokens with a TTL, never derived from the
 *   password;
 * * PBKDF2-HMAC-SHA256 password storage, constant-time comparison, per-address
 *   login throttling with a ban window;
 * * a strict static whitelist (no path traversal), a 32 MiB request cap,
 *   `X-Frame-Options: DENY` and a CSP without `'unsafe-inline'`.
 */
class WebUiServer(
    private val store: AppStore,
    /** Live settings accessor — the WebUI section can be edited at runtime. */
    private val settings: () -> WebUiSettings,
    private val bindAddress: String,
    private val port: Int,
    /**
     * Explicit `frame-ancestors` CSP source list for deployments that are
     * *meant* to be embedded: the 飞牛 fnOS App Center card is an iframe, and
     * its origin (the NAS UI port) is not known in advance, so `cmd/main`
     * starts the server with `--frame-ancestors=*`. `null` keeps the desktop
     * default — [WebUiSettings.clickjackingProtection] decides.
     */
    private val frameAncestors: String? = null,
    /** Startup banner / advisories. */
    private val log: (String) -> Unit = { println(it) },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    /**
     * Settings are the one request body where an unknown field is a *user*
     * error worth reporting: the WebUI form used to send `port` / `dhtEnabled`
     * instead of `listenPort` / `enableDht`, and because unknown keys were
     * dropped silently the switch appeared to reset itself after every save.
     */
    private val strictJson = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    private val rssRepo = RssRepository()

    private val sessions =
        WebUiSessions(
            timeoutMinutes = settings().sessionTimeoutMinutes,
            maxFailures = settings().maxAuthFailCount,
            banSeconds = settings().banDurationSec,
        )

    private val http: HttpServer =
        HttpServer.create(InetSocketAddress(bindAddress, port), 64).apply {
            executor =
                Executors.newFixedThreadPool(8) { r ->
                    Thread(r, "typebit-webui").apply { isDaemon = true }
                }
            createContext("/") { exchange -> handle(exchange) }
        }

    /** Files the SPA may fetch; nothing else is ever served from disk. */
    private val staticAssets =
        mapOf(
            "/" to "index.html",
            "/index.html" to "index.html",
            "/app.js" to "app.js",
            "/app.css" to "app.css",
            "/favicon.svg" to "favicon.svg",
        )

    fun start() {
        http.start()
        log("TypeBitTorrent WebUI: http://$bindAddress:$port")
        if (settings().httpsEnabled) {
            log(
                "WebUI: httpsEnabled=true, but this server speaks plain HTTP. " +
                    "Terminate TLS in a reverse proxy; enable reverseProxyEnabled so session cookies are flagged Secure."
            )
        }
    }

    fun stop() {
        runCatching { http.stop(0) }
        (http.executor as? ExecutorService)?.shutdownNow()
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path ?: "/"
            if (path.startsWith("/api/")) {
                exchange.responseHeaders.add("Cache-Control", "no-store")
                applyApiHeaders(exchange)
                when (exchange.requestMethod) {
                    "GET" -> handleGet(exchange, path)
                    "POST" -> handlePost(exchange, path)
                    else -> fail(exchange, 405, "method not allowed")
                }
            } else {
                serveStatic(exchange, path)
            }
        } catch (t: Throwable) {
            // A malformed request must never take the server or the engine down.
            runCatching { fail(exchange, 500, "internal error: ${t.message}") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun applyApiHeaders(exchange: HttpExchange) {
        // `X-Frame-Options` cannot express "any ancestor", so a framed
        // deployment gets the CSP directive only.
        when {
            frameAncestors != null ->
                exchange.responseHeaders.add(
                    "Content-Security-Policy",
                    "frame-ancestors $frameAncestors",
                )
            settings().clickjackingProtection -> {
                exchange.responseHeaders.add("X-Frame-Options", "DENY")
                exchange.responseHeaders.add("Content-Security-Policy", "frame-ancestors 'none'")
            }
        }
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
    }

    private fun serveStatic(exchange: HttpExchange, path: String) {
        val asset = staticAssets[path] ?: run {
            sendText(exchange, 404, "not found")
            return
        }
        val bytes =
            javaClass.getResourceAsStream("/webui/$asset")?.use { it.readBytes() } ?: run {
                sendText(exchange, 500, "webui resource missing: $asset")
                return
            }
        val headers = exchange.responseHeaders
        headers.add("Content-Type", contentTypeOf(asset))
        // Never let a stale SPA hide a server fix: a cached app.js once kept
        // sending settings field names the server had stopped accepting.
        headers.add("Cache-Control", "no-cache")
        headers.add("Content-Security-Policy", staticCsp())
        headers.add("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun contentTypeOf(name: String): String =
        when (name.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

    /**
     * Document CSP. `frame-ancestors` follows the same policy as
     * [applyApiHeaders]: an explicit [frameAncestors] wins, then the
     * clickjacking switch; with protection off the directive is omitted, which
     * CSP reads as "no restriction".
     */
    private fun staticCsp(): String {
        val base =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; form-action 'none'; base-uri 'none'"
        val directive =
            frameAncestors?.let { "frame-ancestors $it" }
                ?: if (settings().clickjackingProtection) "frame-ancestors 'none'" else null
        return if (directive == null) base else "$base; $directive"
    }

    /** Serializes any DTO/list/DTO-tree into a JSON element. */
    private inline fun <reified T> jsonOf(value: T): JsonElement = json.encodeToJsonElement(value)

    private fun sendJson(exchange: HttpExchange, code: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun ok(exchange: HttpExchange, message: String = "", code: Int = 200) =
        sendJson(exchange, code, jsonOf(OkResponse(true, message)))

    private fun fail(exchange: HttpExchange, code: Int, message: String) =
        sendJson(exchange, code, jsonOf(OkResponse(false, message)))

    private fun sendText(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Reads at most [maxBytes] of the request body; null when too large. */
    private fun readBody(exchange: HttpExchange, maxBytes: Int = 32 * 1024 * 1024): String? {
        exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()?.let {
            if (it > maxBytes) return null
        }
        val bytes = exchange.requestBody.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) return null
        return String(bytes, StandardCharsets.UTF_8)
    }

    private inline fun <reified T : Any> decode(body: String): T? =
        runCatching { json.decodeFromString<T>(body) }.getOrNull()

    private fun query(exchange: HttpExchange, key: String): String? =
        exchange.requestURI.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    /** Turns a strict-decode failure into an actionable sentence. */
    private fun unknownSettingField(json: Json, body: String): String {
        val message =
            runCatching { json.decodeFromString<AppSettings>(body) }
                .exceptionOrNull()
                ?.message
                .orEmpty()
        val key = Regex("Unexpected JSON key '([^']+)'").find(message)?.groupValues?.getOrNull(1)
        return if (key != null) {
            "设置未保存：不认识的设置项 “$key”。请按 Ctrl+F5 强制刷新页面后重试。"
        } else {
            "设置未保存：${message.take(160)}"
        }
    }

    private fun clientAddress(exchange: HttpExchange): String =
        exchange.remoteAddress?.address?.hostAddress ?: "unknown"

    private fun isLoopback(exchange: HttpExchange): Boolean =
        exchange.remoteAddress?.address?.isLoopbackAddress == true

    // ------------------------------------------------------------------
    // authentication / request hardening
    // ------------------------------------------------------------------

    private fun cookie(exchange: HttpExchange, name: String): String? =
        exchange.requestHeaders.getFirst("Cookie")
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun authorized(exchange: HttpExchange): Boolean {
        val s = settings()
        if (!s.enabled) return false
        if (sessions.resolve(cookie(exchange, COOKIE), System.currentTimeMillis()) != null) return true
        // qBittorrent parity: a browser ON the NAS may skip auth (configurable).
        return s.localHostAuth && isLoopback(exchange)
    }

    private fun requireAuth(exchange: HttpExchange): Boolean {
        if (authorized(exchange)) return true
        fail(exchange, 401, "未登录")
        return false
    }

    /** CSRF: only a same-origin caller can set the custom header. */
    private fun passesCsrf(exchange: HttpExchange): Boolean {
        if (settings().csrfProtection && exchange.requestHeaders.getFirst("X-TypeBit") != "1") {
            return false
        }
        val site = exchange.requestHeaders.getFirst("Sec-Fetch-Site")
        if (site != null && site != "same-origin" && site != "none") return false
        return true
    }

    private fun checkHostHeader(exchange: HttpExchange): Boolean {
        if (!settings().hostHeaderValidation) return true
        val host = exchange.requestHeaders.getFirst("Host")?.substringBefore(':') ?: return false
        return host == bindAddress ||
            host == "localhost" ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host.equals(localHostname(), ignoreCase = true)
    }

    private fun localHostname(): String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("")

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    private fun handleGet(exchange: HttpExchange, path: String) {
        if (!checkHostHeader(exchange)) {
            fail(exchange, 421, "Host header rejected")
            return
        }
        when (path) {
            "/api/session" -> {
                val s = settings()
                val now = System.currentTimeMillis()
                val session = sessions.resolve(cookie(exchange, COOKIE), now)
                sendJson(
                    exchange,
                    200,
                    jsonOf(
                        SessionDto(
                            authenticated = authorized(exchange),
                            passwordRequired = s.passwordHash.isNotBlank(),
                            localBypass = s.localHostAuth && isLoopback(exchange),
                            username = session?.username ?: "",
                            version = VERSION,
                            platform = Platform.name,
                            expiresAt = (session?.expiresAt ?: 0L) / 1000,
                        )
                    ),
                )
            }
            "/api/state" -> if (requireAuth(exchange)) sendJson(exchange, 200, jsonOf(stateDto()))
            "/api/stats" ->
                if (requireAuth(exchange)) {
                    sendJson(exchange, 200, jsonOf(runBlocking { store.fetchStats() }))
                }
            "/api/settings" ->
                if (requireAuth(exchange)) sendJson(exchange, 200, jsonOf(store.state.value.settings))
            "/api/detail" -> {
                if (requireAuth(exchange)) {
                    val detail = detailDto(query(exchange, "hash").orEmpty())
                    if (detail == null) fail(exchange, 404, "未知种子")
                    else sendJson(exchange, 200, jsonOf(detail))
                }
            }
            "/api/logs" ->
                if (requireAuth(exchange)) {
                    val after = query(exchange, "after")?.toIntOrNull() ?: 0
                    val logs =
                        store.state.value.logs
                            .withIndex()
                            .filter { it.index >= after }
                            .map { LogLineDto(it.index, levelName(it.value.l), it.value.m) }
                    sendJson(exchange, 200, jsonOf(logs))
                }
            "/api/make-torrent/progress" ->
                if (requireAuth(exchange)) {
                    val p = store.makeTorrentProgress()
                    sendJson(
                        exchange,
                        200,
                        jsonOf(MakeProgressDto(p.doneBytes, p.totalBytes, p.running, p.cancelled)),
                    )
                }
            "/api/search" -> {
                if (requireAuth(exchange)) {
                    val q = query(exchange, "q").orEmpty().trim()
                    if (q.isEmpty()) fail(exchange, 400, "缺少关键词")
                    else sendJson(exchange, 200, jsonOf(runSearch(q)))
                }
            }
            "/api/rss" -> if (requireAuth(exchange)) sendJson(exchange, 200, jsonOf(readRss()))
            "/api/rss/items" ->
                if (requireAuth(exchange)) sendJson(exchange, 200, jsonOf(readRssItems()))
            else -> fail(exchange, 404, "unknown endpoint")
        }
    }

    // ------------------------------------------------------------------
    // POST
    // ------------------------------------------------------------------

    private fun handlePost(exchange: HttpExchange, path: String) {
        if (!checkHostHeader(exchange)) {
            fail(exchange, 421, "Host header rejected")
            return
        }
        if (!passesCsrf(exchange)) {
            fail(exchange, 403, "CSRF check failed")
            return
        }
        val client = clientAddress(exchange)
        val now = System.currentTimeMillis()

        if (path == "/api/login") {
            if (sessions.isBanned(client, now)) {
                fail(exchange, 429, "登录失败次数过多，请稍后再试")
                return
            }
            val body = readBody(exchange, maxBytes = 64 * 1024)?.let { decode<LoginRequest>(it) }
            if (body == null) {
                fail(exchange, 400, "bad request")
                return
            }
            val s = settings()
            val accepted =
                s.passwordHash.isNotBlank() &&
                    body.username == s.username &&
                    WebUiCrypto.verify(body.password, s.passwordHash)
            if (!accepted) {
                val ban = sessions.recordFailure(client, now)
                fail(
                    exchange,
                    401,
                    if (ban > 0) "登录失败次数过多，已封禁 ${ban / 60} 分钟" else "用户名或密码错误",
                )
                return
            }
            sessions.clearFailures(client)
            val session = sessions.open(s.username, now)
            val secure = if (s.reverseProxyEnabled && s.httpsEnabled) "; Secure" else ""
            exchange.responseHeaders.add(
                "Set-Cookie",
                "$COOKIE=${session.token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=" +
                    "${s.sessionTimeoutMinutes.coerceAtLeast(1) * 60}$secure",
            )
            ok(exchange, "已登录")
            return
        }

        if (!requireAuth(exchange)) return

        when (path) {
            "/api/logout" -> {
                sessions.close(cookie(exchange, COOKIE))
                exchange.responseHeaders.add("Set-Cookie", "$COOKIE=; Path=/; HttpOnly; Max-Age=0")
                ok(exchange, "已退出")
            }
            "/api/torrents/add" -> {
                val req = readBody(exchange)?.let { decode<AddRequest>(it) } ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                handleAdd(exchange, req)
            }
            "/api/torrents/action" -> {
                val req = readBody(exchange)?.let { decode<ActionRequest>(it) } ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                if (req.hash.isBlank()) {
                    fail(exchange, 400, "缺少 hash")
                    return
                }
                when (req.action) {
                    "pause" -> store.pause(req.hash)
                    "resume" -> store.resume(req.hash)
                    "remove" -> store.remove(req.hash)
                    // Disk verification: turns files that are already on disk
                    // into a seed (the engine starts every session empty).
                    "recheck" -> store.recheck(req.hash)
                    "recheck-cancel" -> store.cancelRecheck()
                    else -> {
                        fail(exchange, 400, "未知操作")
                        return
                    }
                }
                ok(exchange, "已执行：${req.action}")
            }
            "/api/torrents/priorities" -> {
                val req = readBody(exchange)?.let { decode<PriorityRequest>(it) } ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                if (req.hash.isBlank()) {
                    fail(exchange, 400, "缺少 hash")
                    return
                }
                val updates =
                    req.priorities.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
                store.setFilePriorities(req.hash, updates)
                ok(exchange, "优先级已更新")
            }
            "/api/torrents/rename" -> {
                val req = readBody(exchange)?.let { decode<RenameRequest>(it) } ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                if (req.hash.isBlank() || req.name.isBlank()) {
                    fail(exchange, 400, "缺少 hash 或名称")
                    return
                }
                if (req.fileIndex >= 0) store.renameFile(req.hash, req.fileIndex, req.name)
                else store.renameTorrent(req.hash, req.name)
                ok(exchange, "已重命名")
            }
            "/api/torrents/trackers" -> {
                val req = readBody(exchange)?.let { decode<TrackerRequest>(it) } ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                if (req.hash.isBlank()) {
                    fail(exchange, 400, "缺少 hash")
                    return
                }
                if (req.add.isNotBlank()) store.addTracker(req.hash, req.add)
                if (req.remove.isNotBlank()) store.removeTracker(req.hash, req.remove)
                ok(exchange, "Tracker 已更新")
            }
            "/api/torrents/receipt" -> {
                val hash = query(exchange, "hash").orEmpty()
                val torrent = store.state.value.torrents.firstOrNull { it.hash == hash }
                if (torrent == null) {
                    fail(exchange, 404, "未知种子")
                    return
                }
                val result = runBlocking { store.exportReceipt(hash, torrent.downloadedBytes, torrent.addedAt) }
                if (result.isSuccess) ok(exchange, "回执已导出：${result.path}")
                else fail(exchange, 400, result.error ?: "导出失败")
            }
            "/api/settings" -> {
                val body = readBody(exchange) ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                // Strict on purpose (see [strictJson]): an unknown field is a
                // client bug the user must hear about, not a silent no-op.
                val incoming =
                    decodeSettings(strictJson, body)
                        ?: run {
                            fail(exchange, 400, unknownSettingField(strictJson, body))
                            return
                        }
                val before = store.state.value.settings
                // DHT / LSD / port / UPnP / proxy / resolver settings are read
                // once, when the engine is created — applying them means
                // rebuilding it. Transfers keep their resume data.
                val needsRestart = store.settingsNeedEngineRestart(before, incoming)
                store.updateSettings(incoming)
                val subscriptionChanged =
                    before.bitTorrent.trackerUpdateUrl != incoming.bitTorrent.trackerUpdateUrl ||
                        before.bitTorrent.trackerUpdateHours != incoming.bitTorrent.trackerUpdateHours
                if (subscriptionChanged) {
                    // Fire and forget: a dead subscription URL must not turn a
                    // settings save into a 15-second request.
                    store.requestTrackerSubscriptionRefresh()
                }
                val suffix = if (subscriptionChanged) "；正在更新 Tracker 订阅…" else ""
                if (needsRestart) {
                    store.restartEngine()
                    ok(exchange, "设置已保存；引擎正在重启以应用 DHT / 端口 / 解析器等设置（任务会自动恢复）$suffix")
                } else {
                    ok(exchange, "设置已保存$suffix")
                }
            }
            "/api/trackers/update" -> {
                store.requestTrackerSubscriptionRefresh()
                ok(exchange, "正在更新 Tracker 订阅…取回后会加入所有任务（本页会自动刷新结果）")
            }
            "/api/engine/restart" -> {
                store.restartEngine()
                ok(exchange, "引擎正在重启，任务会从续传数据继续")
            }
            "/api/settings/password" -> {
                val req = readBody(exchange, maxBytes = 64 * 1024)?.let { decode<PasswordRequest>(it) }
                    ?: run {
                        fail(exchange, 400, "bad request")
                        return
                    }
                if (req.password.length < 8) {
                    fail(exchange, 400, "密码至少 8 位")
                    return
                }
                val current = store.state.value.settings
                store.updateSettings(
                    current.copy(
                        webUi = current.webUi.copy(passwordHash = WebUiCrypto.hash(req.password)),
                    )
                )
                sessions.closeAll()
                ok(exchange, "密码已更新，请重新登录")
            }
            "/api/make-torrent/cancel" -> {
                val cancelled = store.cancelMakeTorrent()
                if (cancelled) ok(exchange, "已请求取消") else fail(exchange, 409, "当前没有制作任务")
            }
            "/api/make-torrent" -> {
                val req = readBody(exchange)?.let { decode<MakeTorrentRequest>(it) } ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                sendJson(exchange, 200, jsonOf(makeTorrent(req)))
            }
            "/api/rss" -> {
                val req = readBody(exchange)?.let { decode<RssRequest>(it) } ?: run {
                    fail(exchange, 400, "bad request")
                    return
                }
                val result = updateRss(req)
                sendJson(exchange, if (result.ok) 200 else 400, jsonOf(result))
            }
            else -> fail(exchange, 404, "unknown endpoint")
        }
    }

    // ------------------------------------------------------------------
    // handlers
    // ------------------------------------------------------------------

    private fun stateDto(): StateDto {
        val s = store.state.value
        return StateDto(
            engineRunning = s.engineRunning,
            platform = Platform.name,
            peerId = s.peerId,
            dhtNodes = s.dhtNodes,
            activeTrackers = s.trackerCount,
            recheck = RecheckDto(
                hash = s.recheckHash,
                running = s.recheckRunning,
                doneBytes = s.recheckDone,
                totalBytes = s.recheckTotal,
                message = s.recheckMessage,
            ),
            listenPort = s.listenPort,
            extIp = s.extIp,
            extPort = s.extPort,
            portMapPhase = s.portMapPhase,
            portMapPort = s.portMapPort,
            lsdSent = s.lsdSent,
            lsdRecv = s.lsdRecv,
            lsdPeers = s.lsdPeers,
            downRate = s.globalDownRate,
            upRate = s.globalUpRate,
            totalDownloaded = s.totalDownloaded,
            totalUploaded = s.totalUploaded,
            antiLeechCount = s.antiLeechCount,
            antiLeechClients = s.antiLeechClients,
            lastError = s.lastError.orEmpty(),
            torrents = s.torrents.map { it.toDto() },
        )
    }

    private fun Torrent.toDto(): TorrentDto =
        TorrentDto(
            hash = hash,
            name = name,
            status = status.name,
            progress = progress,
            sizeBytes = sizeBytes,
            selectedBytes = selectedBytes,
            downloadedBytes = downloadedBytes,
            uploadedBytes = uploadedBytes,
            downSpeed = downSpeed,
            upSpeed = upSpeed,
            etaSeconds = etaSeconds ?: -1L,
            ratio = ratio,
            seeds = seeds,
            peers = peers,
            pieceCount = pieceCount,
            havePieces = havePieces,
            trackerCount = trackers.size,
            saveDir = saveDir,
            category = category,
            tags = tags,
            addedAt = addedAt,
            isComplete = isComplete,
            metadataReady = metadataReady,
        )

    private fun detailDto(hash: String): DetailDto? {
        if (hash.isBlank()) return null
        val t = store.state.value.torrents.firstOrNull { it.hash == hash } ?: return null
        val peers = runBlocking { store.peers(hash) }
        return DetailDto(
            hash = t.hash,
            name = t.name,
            saveDir = t.saveDir,
            kind = t.kind,
            sizeBytes = t.sizeBytes,
            pieceLength = t.pieceLength,
            pieceCount = t.pieceCount,
            havePieces = t.havePieces,
            haveBitsHex = t.haveBitsHex,
            isPrivate = t.isPrivate,
            comment = t.comment.orEmpty(),
            createdBy = t.createdBy.orEmpty(),
            createdAt = t.createdAt ?: 0L,
            files =
                t.files.mapIndexed { i, f ->
                    FileDto(
                        index = i,
                        path = f.effectivePath,
                        length = f.length,
                        priority = (t.filePriorities.getOrNull(i) ?: 1).coerceIn(0, 2),
                    )
                },
            peers =
                peers
                    .sortedWith(compareByDescending<PeerDto> { it.phase == 2 }.thenByDescending { it.down })
                    .map { PeerRowDto(it.addr, it.client, it.cc, it.phase, it.seed, it.down, it.up, it.inflight) },
            trackers =
                t.trackers.map { TrackerRowDto(it.url, it.status.name, it.seeds, it.leeches, it.message) },
            receipts =
                store.listReceiptsFor(hash).map { r ->
                    ReceiptDto(
                        name = r.path.substringAfterLast(File.separatorChar),
                        path = r.path,
                        bytes = r.json.length.toLong(),
                    )
                },
        )
    }

    private fun handleAdd(exchange: HttpExchange, req: AddRequest) {
        val preferred =
            req.savePath.trim().ifBlank { store.state.value.settings.downloads.defaultSavePath }
        val savePath = Platform.resolveSaveDir(preferred)
        when {
            req.magnet.isNotBlank() -> {
                store.addMagnetEx(req.magnet.trim(), savePath, req.category, req.tags, req.paused)
                ok(exchange, "磁力链接已添加")
            }
            req.torrentBase64.isNotBlank() -> {
                val bytes =
                    runCatching { Base64.getDecoder().decode(req.torrentBase64) }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    fail(exchange, 400, "种子数据不是合法的 base64")
                    return
                }
                store.addTorrentFileEx(
                    bytes,
                    req.fileName.ifBlank { "upload.torrent" },
                    savePath,
                    req.category,
                    req.tags,
                    req.paused,
                )
                ok(exchange, "种子已添加")
            }
            else -> fail(exchange, 400, "缺少 magnet 或 torrentBase64")
        }
    }

    private fun makeTorrent(req: MakeTorrentRequest): TorrentBytesDto {
        val files =
            when {
                req.directory.isNotBlank() -> {
                    val root = File(req.directory.trim())
                    if (!root.isDirectory || !root.canRead()) {
                        return TorrentBytesDto(false, "目录不存在或不可读：${req.directory}")
                    }
                    walk(root)
                }
                else -> req.files.filter { it.abs.isNotBlank() && it.rel.isNotEmpty() }.map { it.abs to it.rel }
            }
        if (files.isEmpty()) return TorrentBytesDto(false, "没有可打包的文件")
        val total = files.sumOf { File(it.first).length().coerceAtLeast(0L) }
        val pieceLength =
            (if (req.pieceLength > 0) req.pieceLength else recommendPieceLength(total)).toInt()
        val name =
            req.name.trim().ifBlank {
                if (req.directory.isNotBlank()) File(req.directory.trim()).name else "torrent"
            }
        val options =
            com.typebit.engine.MakeTorrentOptions(
                files = files,
                pieceLength = pieceLength,
                name = name,
                announce = req.trackers.map { it.trim() }.filter { it.isNotEmpty() },
                comment = req.comment.trim().ifBlank { null },
                source = req.source.trim().ifBlank { null },
                isPrivate = req.isPrivate,
            )
        val bytes = runCatching { store.makeTorrent(options) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return TorrentBytesDto(false, "制作失败：文件无法读取、被修改或参数无效")
        }
        val info = runCatching { store.parseTorrentFile(bytes) }.getOrNull()
        return TorrentBytesDto(
            ok = true,
            message = "已生成 $name.torrent",
            base64 = Base64.getEncoder().encodeToString(bytes),
            name = "$name.torrent",
            hash = info?.hash.orEmpty(),
            sizeBytes = info?.size ?: total,
            pieceCount = info?.piece_count ?: 0L,
            pieceLength = info?.piece_length ?: pieceLength.toLong(),
            fileCount = info?.files?.size ?: files.size,
        )
    }

    /** Recursive server-side directory listing: (absolute path, relative path components). */
    private fun walk(root: File): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        fun rec(dir: File, prefix: List<String>) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                when {
                    child.isDirectory -> rec(child, prefix + child.name)
                    child.isFile -> out.add(child.absolutePath to (listOf(root.name) + prefix + child.name))
                }
            }
        }
        rec(root, emptyList())
        return out
    }

    private fun recommendPieceLength(totalBytes: Long): Long {
        if (totalBytes <= 0) return 256L * 1024
        var size = 16L * 1024
        while (size < 16L * 1024 * 1024 && totalBytes / size > 2048L) size *= 2
        return size
    }

    private fun runSearch(q: String): SearchResponseDto {
        val client = TorrentSearchClient(searchEngines())
        val engines = LinkedHashSet<String>()
        val results =
            runCatching {
                runBlocking {
                    client.search(q) { p ->
                        if (p.phase != com.typebit.search.SearchPhase.RUNNING) engines.add(p.name)
                    }
                }
            }.getOrDefault(emptyList())
        return SearchResponseDto(
            ok = true,
            message = if (results.isEmpty()) "没有结果（部分站点需代理或已限流）" else "共 ${results.size} 条结果",
            engines = engines.toList(),
            results =
                results.take(200).map {
                    SearchResultDto(it.title, it.magnet, it.size, it.seeds, it.leeches, it.source ?: "")
                },
        )
    }

    /** The same engine set the desktop search screen uses. */
    private fun searchEngines(): List<com.typebit.search.TorrentSearchEngine> =
        listOf(
            NyaaEngine(SearchHttpClient()),
            X1337xEngine(SearchHttpClient()),
            PirateBayEngine(SearchHttpClient()),
            MagnetIndexEngine(
                name = "黑马磁力",
                http = SearchHttpClient(),
                bases = listOf("https://heimaai.top"),
                searchPaths = listOf("/s/{q}", "/search/{q}", "/so/{q}", "/index.php?q={q}", "/?q={q}", "/list/{q}"),
            ),
            MagnetIndexEngine(
                name = "磁力多",
                http = SearchHttpClient(),
                bases = listOf("https://ug.cilido.top"),
                searchPaths = listOf("/s/{q}", "/search/{q}", "/so/{q}", "/?q={q}", "/index.php?q={q}", "/list/{q}"),
            ),
            MagnetIndexEngine(
                name = "搜番",
                http = SearchHttpClient(),
                bases = listOf("https://sc.sefan.cc"),
                searchPaths =
                    listOf(
                        "/s/{q}",
                        "/search/{q}",
                        "/so/{q}",
                        "/?q={q}",
                        "/index.php?q={q}",
                        "/list/{q}",
                        "/{q}",
                    ),
            ),
        )

    private fun readRss(): List<RssFeedDto> =
        rssRepo.loadFeedUrls().map { url ->
            val feed = runCatching { fetchRssFeed(url, 12_000) }.getOrNull()
            RssFeedDto(
                url = url,
                title = feed?.title.orEmpty(),
                items = feed?.items?.size ?: 0,
                error = if (feed == null) "抓取失败" else "",
            )
        }

    private fun readRssItems(): List<RssItemDto> {
        val out = ArrayList<RssItemDto>()
        for (url in rssRepo.loadFeedUrls()) {
            val feed = runCatching { fetchRssFeed(url, 12_000) }.getOrNull() ?: continue
            for (item in feed.items.take(50)) {
                val magnet =
                    MAGNET.find(item.description)?.value ?: MAGNET.find(item.link)?.value ?: ""
                out.add(RssItemDto(feed.title, item.title, item.link, magnet, item.pubDate))
            }
        }
        return out
    }

    private fun updateRss(req: RssRequest): OkResponse {
        val urls = rssRepo.loadFeedUrls()
        return when (req.action) {
            "add" -> {
                val url = req.url.trim()
                if (url.isBlank()) return OkResponse(false, "缺少订阅地址")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return OkResponse(false, "订阅地址必须是 http(s)")
                }
                if (url in urls) return OkResponse(true, "已存在")
                rssRepo.saveFeedUrls(urls + url)
                OkResponse(true, "已添加订阅")
            }
            "remove" -> {
                rssRepo.saveFeedUrls(urls.filterNot { it == req.url.trim() })
                OkResponse(true, "已移除订阅")
            }
            else -> OkResponse(false, "未知操作")
        }
    }

    private fun levelName(level: Int): String =
        when (level) {
            0 -> "trace"
            1 -> "debug"
            2 -> "info"
            3 -> "warn"
            else -> "error"
        }

    companion object {
        const val VERSION = "0.1.9"
        private const val COOKIE = "typebit_session"
        private val MAGNET = Regex("magnet:\\?xt=urn:btih:[A-Za-z0-9]+[^\"'<>\\s\\\\]*")

        /** Public for the CLI/settings screen: hash a password for storage. */
        fun hashPassword(password: String): String = WebUiCrypto.hash(password)

        /** Public for tests. */
        fun verifyPassword(password: String, stored: String): Boolean =
            WebUiCrypto.verify(password, stored)
    }
}
