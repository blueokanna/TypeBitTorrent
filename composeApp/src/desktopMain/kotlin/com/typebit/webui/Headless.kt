package com.typebit.webui

import com.typebit.app.createAppStore
import com.typebit.data.SettingsRepository
import com.typebit.data.WebUiSettings
import com.typebit.platform.Platform
import com.typebit.store.AppStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless mode — the full client with no window.
 *
 * This is the entry point the fnOS `.fpk` and the Unraid/Docker images run:
 * the same `AppStore` + Rust engine, driven from the browser through
 * [WebUiServer]. It exists because a NAS has no display but absolutely can
 * run everything else (the engine is the same `no_std`-style worker, the disk
 * and socket code is the same `NativeHost`).
 *
 * Usage (see `docs/nas.md`):
 * ```
 * typebittorrent --headless --bind=0.0.0.0 --port=18881 \
 *                --data=/config --downloads=/downloads \
 *                --username=admin --password=secret
 * ```
 *
 * Password resolution order (first non-empty wins):
 * 1. `--password=…` on the command line,
 * 2. `TYPEBIT_PASSWORD` in the environment (the container-friendly path),
 * 3. the PBKDF2 hash already stored in settings,
 * 4. otherwise a random one is generated and printed once at startup —
 *    an unauthenticated WebUI is never started.
 */
object Headless {

    /** True when [args] ask for headless mode (or the env var is set). */
    fun requested(args: Array<String>): Boolean =
        args.any { it == "--headless" || it == "-H" } ||
            System.getenv("TYPEBIT_HEADLESS")?.let { it == "1" || it.equals("true", true) } == true

    fun run(args: Array<String>): Int {
        val opts = parse(args)

        opts.dataDir?.let {
            val dir = File(it).absoluteFile
            dir.mkdirs()
            System.setProperty(DATA_DIR_PROPERTY, dir.path)
        }

        println("TypeBitTorrent ${WebUiServer.VERSION} — headless mode")
        println("  data dir : ${Platform.appDataDir()}")

        val store = createAppStore()
        val settingsRepo = SettingsRepository()
        bootEngine(store, settingsRepo, opts)

        val settings = store.state.value.settings
        val webUi = resolveWebUiSettings(settings.webUi, opts)
        if (webUi != settings.webUi) {
            store.updateSettings(settings.copy(webUi = webUi))
        }

        val server =
            WebUiServer(
                store = store,
                settings = { store.state.value.settings.webUi },
                bindAddress = opts.bind,
                port = opts.port ?: webUi.port,
                frameAncestors = opts.frameAncestors,
            )
        try {
            server.start()
        } catch (t: Throwable) {
            System.err.println("无法监听 ${opts.bind}:${opts.port ?: webUi.port} — ${t.message}")
            store.stopBlocking()
            return 2
        }

        println("  保存目录 : ${settings.downloads.defaultSavePath.ifBlank { Platform.defaultDownloadDir() }}")
        println("  用户名   : ${webUi.username}")
        opts.frameAncestors?.let { println("  允许嵌套 : frame-ancestors $it（NAS 应用中心内嵌 WebUI）") }
        opts.generatedPassword?.let {
            println("  初始密码 : $it   ← 首次运行随机生成，请登录后在设置中修改")
        }
        println("按 Ctrl+C 退出（会先落盘续传数据并关闭引擎）")

        val stopped = AtomicBoolean(false)
        val shutdown = {
            if (stopped.compareAndSet(false, true)) {
                println("正在关闭…")
                server.stop()
                store.stopBlocking()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread(shutdown, "typebit-shutdown"))
        // Park forever; the shutdown hook does the teardown (SIGTERM from
        // Docker / `appcenter-cli stop` lands there too).
        try {
            while (!stopped.get()) Thread.sleep(1_000)
        } catch (_: InterruptedException) {
            shutdown()
        }
        return 0
    }

    /** Starts the engine and waits until it reports running (bounded). */
    private fun bootEngine(store: AppStore, settingsRepo: SettingsRepository, opts: Options) {
        runBlocking { runCatching { settingsRepo.load() } }
        store.start()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (store.state.value.engineRunning) break
            Thread.sleep(200)
        }
        if (!store.state.value.engineRunning) {
            System.err.println("引擎未能在 20 秒内启动（原生库缺失或端口被占用）：${store.state.value.lastError}")
        }
        // Container convention: the mounted downloads volume becomes the
        // default save path when the user has not chosen one.
        opts.downloadsDir?.let { dir ->
            val path = File(dir).absoluteFile.path
            val current = store.state.value.settings
            if (current.downloads.defaultSavePath.isBlank()) {
                File(path).mkdirs()
                store.updateSettings(
                    current.copy(downloads = current.downloads.copy(defaultSavePath = path))
                )
            }
        }
    }

    /** Applies CLI/env overrides on top of the persisted WebUI settings. */
    private fun resolveWebUiSettings(current: WebUiSettings, opts: Options): WebUiSettings {
        val envPassword = System.getenv("TYPEBIT_PASSWORD")?.takeIf { it.isNotBlank() }
        val password = opts.password ?: envPassword
        val username = opts.username ?: current.username.ifBlank { "admin" }
        val base =
            current.copy(
                enabled = true,
                username = username,
                port = opts.port ?: current.port,
            )
        if (password != null) {
            return base.copy(passwordHash = WebUiServer.hashPassword(password))
        }
        if (base.passwordHash.isNotBlank()) return base
        val generated = generatePassword()
        opts.generatedPassword = generated
        return base.copy(passwordHash = WebUiServer.hashPassword(generated))
    }

    /** 20-char URL-safe password (CSPRNG). */
    private fun generatePassword(): String {
        val bytes = ByteArray(15)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------

    private class Options {
        var bind: String = "0.0.0.0"
        var port: Int? = null
        var username: String? = null
        var password: String? = null
        var dataDir: String? = null
        var downloadsDir: String? = null
        var frameAncestors: String? = null
        var generatedPassword: String? = null
    }

    private fun parse(args: Array<String>): Options {
        val o = Options()
        for (raw in args) {
            val arg = raw.trim()
            val (key, value) = if (arg.startsWith("--") && arg.contains('=')) {
                arg.substringBefore('=') to arg.substringAfter('=')
            } else {
                arg to ""
            }
            when (key) {
                "--bind", "--host" -> if (value.isNotBlank()) o.bind = value
                "--port" -> value.toIntOrNull()?.let { o.port = it }
                "--username", "--user" -> if (value.isNotBlank()) o.username = value
                "--password" -> if (value.isNotEmpty()) o.password = value
                "--data", "--data-dir", "--config" -> if (value.isNotBlank()) o.dataDir = value
                "--downloads", "--save" -> if (value.isNotBlank()) o.downloadsDir = value
                "--frame-ancestors" -> normalizeFrameAncestors(value)?.let { o.frameAncestors = it }
            }
        }
        // Environment fallbacks keep the container command short.
        System.getenv("TYPEBIT_BIND")?.takeIf { it.isNotBlank() }?.let { if (o.bind == "0.0.0.0") o.bind = it }
        System.getenv("TYPEBIT_PORT")?.toIntOrNull()?.let { if (o.port == null) o.port = it }
        System.getenv("TYPEBIT_USERNAME")?.takeIf { it.isNotBlank() }?.let { if (o.username == null) o.username = it }
        System.getenv("TYPEBIT_DATA")?.takeIf { it.isNotBlank() }?.let { if (o.dataDir == null) o.dataDir = it }
        System.getenv("TYPEBIT_DOWNLOADS")?.takeIf { it.isNotBlank() }?.let {
            if (o.downloadsDir == null) o.downloadsDir = it
        }
        System.getenv("TYPEBIT_FRAME_ANCESTORS")?.takeIf { it.isNotBlank() }?.let {
            if (o.frameAncestors == null) normalizeFrameAncestors(it)?.let { v -> o.frameAncestors = v }
        }
        System.getProperty(DATA_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let {
            if (o.dataDir == null) o.dataDir = it
        }
        return o
    }

    /** Set to relocate the app data dir (containers mount `/config`). */
    const val DATA_DIR_PROPERTY = "typebit.data.dir"

    /** One `frame-ancestors` source: `*`, a keyword, or an http(s) origin. */
    private const val FRAME_SOURCE = """\*|'self'|'none'|https?://[A-Za-z0-9.\-]+(?::\d{1,5})?"""

    private val FRAME_SOURCES = Regex("^($FRAME_SOURCE)( ($FRAME_SOURCE))*$")

    /**
     * Normalizes `--frame-ancestors` (e.g. `*`, `'self'`, `http://nas:5666`)
     * into a CSP source list. The value ends up verbatim in a response header,
     * so anything that is not a plain source list is dropped — that rules out
     * header injection, not just typos.
     */
    private fun normalizeFrameAncestors(raw: String): String? {
        val value =
            raw.trim().split(',', ' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        if (value.isEmpty()) return null
        if (!FRAME_SOURCES.matches(value)) {
            System.err.println("忽略非法的 --frame-ancestors 值: $raw")
            return null
        }
        return value
    }
}
