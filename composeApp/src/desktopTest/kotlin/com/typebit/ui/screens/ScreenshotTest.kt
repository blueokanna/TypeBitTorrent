package com.typebit.ui.screens

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.typebit.data.SettingsRepository
import com.typebit.data.TorrentRepository
import com.typebit.engine.EngineEventDto
import com.typebit.engine.EngineSnapshotDto
import com.typebit.engine.LogEntryDto
import com.typebit.engine.TorrentEngine
import com.typebit.engine.TorrentInfoDto
import com.typebit.engine.TorrentStateDto
import com.typebit.store.AppStore
import com.typebit.ui.screens.main.MainScreen
import com.typebit.ui.screens.settings.SettingsScreen
import com.typebit.ui.theme.TypeBitTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the desktop screens inside the real MD3E theme and dumps PNGs so
 * the actual visuals can be inspected. Not a behavioral test — a visual
 * verification aid that also guards against blank/render failures.
 */
class ScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun AppStore.fake(): AppStore {
        // Store constructed with a no-op engine; start() is never called, so
        // no native lib is touched and no real state is loaded.
        return this
    }

    @Test
    fun settingsDark_rendersContent() {
        val store = makeStore()
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                SettingsScreen(state = store.state.value, store = store, onBack = {})
            }
        }
        // Behavior is the initial category — its section cards must exist.
        rule.onNodeWithText("行为").assertExists()
        rule.onNodeWithText("界面").assertExists()
        rule.onNodeWithText("语言").assertExists()
        // Switch to 外观 and confirm its cards render too.
        rule.onNodeWithText("外观").performClick()
        rule.onNodeWithText("主题模式").assertExists()
        rule.onNodeWithText("壁纸").assertExists()
        rule.onNodeWithText("动态色彩 (Monet)").assertExists()
        save(rule.onRoot().captureToImage(), "settings-dark.png")
    }

    @Test
    fun mainDark_rendersLayout() {
        val store = makeStore()
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                MainScreen(state = store.state.value, store = store, onRoute = {})
            }
        }
        rule.onNodeWithText("TypeBitTorrent").assertExists()
        rule.onNodeWithText("暂无种子").assertExists()
        save(rule.onRoot().captureToImage(), "main-dark.png")
    }

    @Test
    fun mainAmoled_pureBlackBackground() {
        val store = makeStore()
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true, amoled = true) {
                MainScreen(state = store.state.value, store = store, onRoute = {})
            }
        }
        rule.onNodeWithText("TypeBitTorrent").assertExists()
        rule.onNodeWithText("暂无种子").assertExists()
        save(rule.onRoot().captureToImage(), "main-amoled.png")
    }

    @Test
    fun mainLight_rendersLayout() {
        val store = makeStore()
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = false) {
                MainScreen(state = store.state.value, store = store, onRoute = {})
            }
        }
        rule.onNodeWithText("TypeBitTorrent").assertExists()
        rule.onNodeWithText("暂无种子").assertExists()
        rule.onNodeWithText("添加种子").assertExists()
        save(rule.onRoot().captureToImage(), "main-light.png")
    }

    @Test
    fun settingsLight_rendersContent() {
        val store = makeStore()
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = false) {
                SettingsScreen(state = store.state.value, store = store, onBack = {})
            }
        }
        rule.onNodeWithText("界面").assertExists()
        rule.onNodeWithText("语言").assertExists()
        rule.onNodeWithText("外观").performClick()
        rule.onNodeWithText("主题模式").assertExists()
        save(rule.onRoot().captureToImage(), "settings-light.png")
    }

    private fun makeStore(): AppStore =
        AppStore(
            engine = FakeEngine(),
            settingsRepo = SettingsRepository(),
            torrentRepo = TorrentRepository(),
        )

    private fun save(bitmap: ImageBitmap, name: String) {
        val dir = File("screenshots").apply { mkdirs() }
        val awt = bitmap.toAwtImage()
        ImageIO.write(awt, "png", File(dir, name))
    }
}

/** No-op engine for visual tests. */
private class FakeEngine : TorrentEngine {
    override fun start(configJson: String, saveDir: String): Boolean = false
    override fun stop() {}
    override val isRunning: Boolean = false
    override fun parseTorrent(data: ByteArray): TorrentInfoDto? = null
    override fun addTorrent(data: ByteArray, saveDir: String, filePriorities: List<Int>): String? = null
    override fun addMagnet(uri: String, saveDir: String): String? = null
    override fun start(hash: String): Boolean = false
    override fun pause(hash: String) {}
    override fun resume(hash: String) {}
    override fun remove(hash: String): Boolean = false
    override fun progress(hash: String): Double = 0.0
    override fun downloaded(hash: String): Long = 0L
    override fun isComplete(hash: String): Boolean = false
    override fun torrentInfo(hash: String): TorrentInfoDto? = null
    override fun torrentStates(): List<TorrentStateDto> = emptyList()
    override fun snapshot(): EngineSnapshotDto = EngineSnapshotDto()
    override fun torrentCount(): Int = 0
    override fun dhtNodeCount(): Int = 0
    override fun peerId(): String = ""
    override fun totals(): Pair<Long, Long> = 0L to 0L
    override fun setGlobalLimits(downBytesPerSec: Long, upBytesPerSec: Long) {}
    override fun setSessionConfig(configJson: String) {}
    override fun setFilePriority(hash: String, file: Int, priority: Int): Boolean = false
    override fun filePriorities(hash: String): List<Int>? = null
    override fun addTracker(hash: String, url: String): Boolean = false
    override fun removeTracker(hash: String, url: String): Boolean = false
    override fun trackers(hash: String): List<String>? = null
    override fun saveState(): ByteArray? = null
    override fun loadState(data: ByteArray) {}
    override fun takeEvents(): List<EngineEventDto> = emptyList()
    override fun takeLogs(): List<LogEntryDto> = emptyList()
}
