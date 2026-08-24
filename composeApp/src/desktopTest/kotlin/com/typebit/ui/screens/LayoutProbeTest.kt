package com.typebit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.typebit.ui.theme.TypeBitTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Isolates the desktop settings-pane layout bug: each candidate structure
 * draws a distinctly colored card; the PNG is then pixel-analyzed to see
 * which structures actually render.
 */
class LayoutProbeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun dump(name: String) {
        val dir = File("screenshots").apply { mkdirs() }
        ImageIO.write(rule.onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
    }

    @Test
    fun probeA_plainColumn() {
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                Column(Modifier.fillMaxSize().background(Color(0xFF000000))) {
                    Card(
                        Modifier.fillMaxWidth().height(120.dp).padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF00FF00)),
                    ) { Text("A-green", Modifier.padding(8.dp)) }
                }
            }
        }
        dump("probeA.png")
    }

    @Test
    fun probeB_scrollColumn() {
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF000000))
                        .verticalScroll(rememberScrollState()),
                ) {
                    Card(
                        Modifier.fillMaxWidth().height(120.dp).padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF00FF00)),
                    ) { Text("B-green", Modifier.padding(8.dp)) }
                }
            }
        }
        dump("probeB.png")
    }

    @Test
    fun probeC_scaffoldRow() {
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                Scaffold { padding ->
                    Row(Modifier.fillMaxSize().padding(padding).background(Color(0xFF000000))) {
                        Column(Modifier.width(200.dp).fillMaxHeight()) {
                            Text("side", Modifier.padding(8.dp))
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(Color(0xFF222222))
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            Card(
                                Modifier.fillMaxWidth().height(120.dp).padding(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF00FF00)),
                            ) { Text("C-green", Modifier.padding(8.dp)) }
                        }
                    }
                }
            }
        }
        dump("probeC.png")
    }

    @Test
    fun probeD_surfaceContent() {
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                Scaffold { padding ->
                    Row(Modifier.fillMaxSize().padding(padding).background(Color(0xFF000000))) {
                        Column(Modifier.width(200.dp).fillMaxHeight()) {
                            Text("side", Modifier.padding(8.dp))
                        }
                        // No verticalScroll this time.
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(Color(0xFF222222))
                                .padding(8.dp),
                        ) {
                            Card(
                                Modifier.fillMaxWidth().height(120.dp).padding(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF00FF00)),
                            ) { Text("D-green", Modifier.padding(8.dp)) }
                        }
                    }
                }
            }
        }
        dump("probeD.png")
    }

    @Test
    fun probeE_realBehaviorSection() {
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF000000))
                        .verticalScroll(rememberScrollState()),
                ) {
                    com.typebit.ui.screens.settings.BehaviorSection(
                        settings = com.typebit.data.AppSettings(),
                        onChange = {},
                    )
                }
            }
        }
        dump("probeE.png")
    }

    /** Full SettingsScreen structure (Scaffold+Row) with probe text. */
    @Test
    fun probeF_fullSettingsWithProbe() {
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                Scaffold { padding ->
                    Row(Modifier.fillMaxSize().padding(padding).background(Color(0xFF000000))) {
                        Column(Modifier.width(200.dp).fillMaxHeight().background(Color(0xFF111111))) {
                            Text("side-nav", Modifier.padding(8.dp), color = Color(0xFFFF00FF))
                        }
                        HorizontalDivider(Modifier.fillMaxHeight())
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface)
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                        ) {
                            Text("PROBE-RED", color = Color(0xFFFF0000))
                            com.typebit.ui.screens.settings.BehaviorSection(
                                settings = com.typebit.data.AppSettings(),
                                onChange = {},
                            )
                        }
                    }
                }
            }
        }
        dump("probeF.png")
    }

    /** Same as probeF but with the divider fixed to width(1.dp). */
    @Test
    fun probeG_fixedDivider() {
        rule.setContent {
            TypeBitTheme(seedArgb = 0xFF0061A4.toInt(), darkTheme = true) {
                Scaffold { padding ->
                    Row(Modifier.fillMaxSize().padding(padding).background(Color(0xFF000000))) {
                        Column(Modifier.width(200.dp).fillMaxHeight().background(Color(0xFF111111))) {
                            Text("side-nav", Modifier.padding(8.dp), color = Color(0xFFFF00FF))
                        }
                        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface)
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                        ) {
                            Text("PROBE-RED", color = Color(0xFFFF0000))
                            com.typebit.ui.screens.settings.BehaviorSection(
                                settings = com.typebit.data.AppSettings(),
                                onChange = {},
                            )
                        }
                    }
                }
            }
        }
        dump("probeG.png")
    }
}
