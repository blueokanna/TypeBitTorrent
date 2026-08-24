package com.typebit.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typebit.app.Route
import com.typebit.platform.Platform
import com.typebit.store.AppState
import com.typebit.store.AppStore

private enum class SettingsCategory(val label: String) {
    BEHAVIOR("行为"),
    APPEARANCE("外观"),
    DOWNLOADS("下载"),
    CONNECTION("连接"),
    SPEED("速度"),
    BIT_TORRENT("BitTorrent"),
    WEBUI("WebUI"),
    ADVANCED("高级"),
    RSS("RSS"),
}

/**
 * MD3-Expressive settings screen. Desktop shows a navigation sidebar
 * (large-radius items with icons + secondaryContainer selection) beside a
 * surfaceContainerLowest editor pane; mobile uses a dropdown switcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppState,
    store: AppStore,
    onBack: () -> Unit,
) {
    var category by remember { mutableIntStateOf(0) }
    val categories = SettingsCategory.entries
    val settings = state.settings
    val onChange: (com.typebit.data.AppSettings) -> Unit = store::updateSettings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        if (Platform.isDesktop) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier
                        .width(256.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "分类",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    categories.forEachIndexed { i, c ->
                        NavigationDrawerItem(
                            label = { Text(c.label) },
                            selected = category == i,
                            onClick = { category = i },
                            icon = { Icon(categoryIcon(c), contentDescription = null) },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                // Vertical divider between the sidebar and the editor pane.
                // `HorizontalDivider` always applies its own `fillMaxWidth`
                // + `height(thickness)` internally, so pinning the width is
                // not enough — it collapses to a 1x1 dot instead of a line.
                // `VerticalDivider` is unavailable in this M3 version, so a
                // 1.dp Box draws the true vertical separator.
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                // Editor pane on a distinct container tone. The scrollable
                // Column fills the remaining width/height directly so the
                // section content is always laid out (no nested-Surface
                // measurement surprises).
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Category switch fades instead of snapping. The section
                    // MUST be wrapped in an explicit Column: AnimatedContent
                    // only lays out the first top-level child it measures, so
                    // a bare multi-card section would render just its first
                    // card and swallow the rest of the settings.
                    AnimatedContent(
                        targetState = categories[category],
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "settingsCategory",
                    ) { cat ->
                        Column {
                            CategoryContent(cat, settings, onChange)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        } else {
            // Mobile: simple category switcher row + scrollable section.
            Column(Modifier.fillMaxSize().padding(padding)) {
                SettingDropdown(
                    label = "分类",
                    options = categories.toList(),
                    selected = categories[category],
                    onSelect = { category = categories.indexOf(it).coerceAtLeast(0) },
                    labelOf = { it.label },
                )
                SettingsScaffold {
                    AnimatedContent(
                        targetState = categories[category],
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "settingsCategoryMobile",
                    ) { cat ->
                        // Same single-Column requirement as the desktop pane.
                        Column {
                            CategoryContent(cat, settings, onChange)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryContent(
    category: SettingsCategory,
    settings: com.typebit.data.AppSettings,
    onChange: (com.typebit.data.AppSettings) -> Unit,
) {
    when (category) {
        SettingsCategory.BEHAVIOR -> BehaviorSection(settings, onChange)
        SettingsCategory.APPEARANCE -> AppearanceSection(settings, onChange)
        SettingsCategory.DOWNLOADS -> DownloadsSection(settings, onChange)
        SettingsCategory.CONNECTION -> ConnectionSection(settings, onChange)
        SettingsCategory.SPEED -> SpeedSection(settings, onChange)
        SettingsCategory.BIT_TORRENT -> BitTorrentSection(settings, onChange)
        SettingsCategory.WEBUI -> WebUiSection(settings, onChange)
        SettingsCategory.ADVANCED -> AdvancedSection(settings, onChange)
        SettingsCategory.RSS -> RssSection(settings, onChange)
    }
}

private fun categoryIcon(c: SettingsCategory): androidx.compose.ui.graphics.vector.ImageVector = when (c) {
    SettingsCategory.BEHAVIOR -> Icons.Default.Psychology
    SettingsCategory.APPEARANCE -> Icons.Default.Palette
    SettingsCategory.DOWNLOADS -> Icons.Default.Download
    SettingsCategory.CONNECTION -> Icons.Default.Dns
    SettingsCategory.SPEED -> Icons.Default.Speed
    SettingsCategory.BIT_TORRENT -> Icons.Default.Link
    SettingsCategory.WEBUI -> Icons.Default.Language
    SettingsCategory.ADVANCED -> Icons.Default.Settings
    SettingsCategory.RSS -> Icons.Default.RssFeed
}
