package com.typebit.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.typebit.data.AppSettings
import com.typebit.data.AppearanceSettings
import com.typebit.data.FontChoice
import com.typebit.data.ThemeMode
import com.typebit.platform.rememberWallpaperPicker
import com.typebit.ui.wallpaper.WallpaperLayer
import com.typebit.ui.wallpaper.loadWallpaperBitmap
import com.typebit.ui.wallpaper.prepareWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// 外观
// ---------------------------------------------------------------------------

@Composable
fun AppearanceSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.appearance
    val update: (AppearanceSettings) -> Unit = { ns -> onChange(settings.copy(appearance = ns)) }

    var showPreview by remember { mutableStateOf(false) }

    SectionCard("主题模式") {
        SettingDropdown(
            label = "主题",
            options = ThemeMode.entries,
            selected = s.themeMode,
            onSelect = { update(s.copy(themeMode = it)) },
            labelOf = { when (it) {
                ThemeMode.SYSTEM -> "跟随系统"
                ThemeMode.LIGHT -> "亮色（黑色文字）"
                ThemeMode.DARK -> "暗色（亮色文字）"
                ThemeMode.AMOLED -> "AMOLED（纯黑）"
            } },
        )
        Text(
            "Monet 动态色彩由壁纸（或手动种子色）经 HCT 色彩空间实时生成整套 MD3 配色。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("字体") {
        SettingDropdown(
            label = "字体（Google Font）",
            options = FontChoice.entries,
            selected = s.fontChoice,
            onSelect = { update(s.copy(fontChoice = it)) },
            labelOf = { when (it) {
                FontChoice.DEFAULT -> "默认（Inter + 思源黑体）"
                FontChoice.ROBOTO -> "Roboto + 思源黑体"
                FontChoice.OPEN_SANS -> "Open Sans + 思源黑体"
                FontChoice.NOTO_SANS -> "思源黑体（Noto Sans SC）"
                FontChoice.SYSTEM -> "系统默认字体"
            } },
        )
        Text(
            "拉丁文字使用所选字体，中文回退到思源黑体（Noto Sans SC）。默认组合兼顾拉丁 UI 密度与中文可读性。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("壁纸") {
        SettingSwitch(
            "启用壁纸", "高斯模糊 + DIM 遮罩铺满整个界面",
            s.wallpaperEnabled, { update(s.copy(wallpaperEnabled = it)) },
        )
        if (s.wallpaperEnabled) {
            WallpaperButtons(s, update)
            SettingDropdown(
                label = "显示模式",
                options = listOf(false, true),
                selected = s.wallpaperFit,
                onSelect = { update(s.copy(wallpaperFit = it)) },
                labelOf = { if (it) "完整显示（Fit）" else "填充窗口（Crop）" },
            )
            CommitSlider(
                label = "垂直位置",
                value = s.wallpaperOffsetY,
                valueRange = -1f..1f,
                // The slider maps -1..1 onto the full pan band (±95% of the
                // window height), so 100% = show the very top of the image.
                suffix = {
                    val pct = (it * 95).toInt()
                    if (pct > 0) "下移 $pct%" else if (pct < 0) "上移 ${-pct}%" else "居中"
                },
                onCommit = { update(s.copy(wallpaperOffsetY = it)) },
            )
            CommitSlider(
                label = "模糊半径",
                value = s.blurRadiusPx,
                valueRange = 0f..80f,
                suffix = { "%.0f px".format(it) },
                onCommit = { update(s.copy(blurRadiusPx = it)) },
            )
            CommitSlider(
                label = "DIM 遮罩强度",
                value = s.dimAlpha,
                valueRange = 0f..0.85f,
                suffix = { "%.0f%%".format(it * 100) },
                onCommit = { update(s.copy(dimAlpha = it)) },
            )
            OutlinedButton(
                onClick = { showPreview = true },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("预览壁纸效果")
            }
        }
    }

    SectionCard("动态色彩 (Monet)") {
        SeedOverrideRow(s, update)
        Text(
            "留空 = 从壁纸自动提取种子色（类似 Android 的动态取色）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPreview) {
        WallpaperPreviewDialog(
            appearance = s,
            onDismiss = { showPreview = false },
        )
    }
}

/** Wallpaper picker + clear buttons. */
@Composable
private fun WallpaperButtons(
    s: AppearanceSettings,
    update: (AppearanceSettings) -> Unit,
) {
    val pick = rememberWallpaperPicker { path ->
        update(s.copy(wallpaperPath = path, wallpaperEnabled = true))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = pick) { Text("选择壁纸…") }
        Spacer(Modifier.width(12.dp))
        if (s.wallpaperPath.isNotBlank()) {
            OutlinedButton(onClick = { update(s.copy(wallpaperPath = "", wallpaperEnabled = false)) }) {
                Text("清除")
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (s.wallpaperPath.isBlank()) "尚未选择" else s.wallpaperPath.substringAfterLast('/').substringAfterLast('\\'),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Label + Material 3 slider row that only reports the value when the drag
 * ENDS. While the thumb moves, only the local value changes, so the
 * expensive global recomposition (and the one-shot wallpaper re-blur) runs
 * once per drag instead of on every frame — this is what keeps the
 * wallpaper sliders buttery instead of janky.
 */
@Composable
private fun CommitSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    suffix: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                suffix(local),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = valueRange,
        )
    }
}

/** Preset Material-You seed swatches + a hex field for a manual override. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedOverrideRow(
    s: AppearanceSettings,
    update: (AppearanceSettings) -> Unit,
) {
    val presets = listOf(
        "自动(壁纸)" to (s.seedOverride?.toString(16) ?: ""),
        "蓝色" to "0xFF0061A4",
        "红色" to "0xFFB3261E",
        "橙色" to "0xFFB8490A",
        "绿色" to "0xFF386A20",
        "青色" to "0xFF006A6A",
        "紫色" to "0xFF6750A4",
        "粉色" to "0xFF9E315C",
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        presets.forEach { (name, hex) ->
            val color = parseArgb(hex)
            val selected = if (name == "自动(壁纸)") {
                s.seedOverride == null
            } else {
                color != null && s.seedOverride == color
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color?.let { Color(it) } ?: Color.Gray)
                    .then(
                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                    )
                    .clickable {
                        update(if (name == "自动(壁纸)") s.copy(seedOverride = null) else s.copy(seedOverride = color))
                    },
            )
        }
    }
    // Simple hex input as an alternative to the swatches.
    var hex by remember(s.seedOverride) {
        mutableStateOf(s.seedOverride?.let { "#%06X".format(it and 0xFFFFFF) } ?: "")
    }
    SettingTextField(
        label = "种子色 (hex)",
        value = hex,
        onValueChange = { input ->
            hex = input
            parseArgb(input)?.let { update(s.copy(seedOverride = it)) }
        },
        placeholder = "#RRGGBB 或留空自动",
    )
}

/** "0xFFRRGGBB" / "#RRGGBB" / "RRGGBB" → ARGB Int, null when invalid. */
internal fun parseArgb(text: String): Int? {
    val t = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (t.length != 6 && t.length != 8) return null
    val v = t.toLongOrNull(16) ?: return null
    return if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
}

// ---------------------------------------------------------------------------
// 预览
// ---------------------------------------------------------------------------

/**
 * Live preview of the wallpaper treatment: the exact [WallpaperLayer] the app
 * uses, with sample surfaces + text, so the user sees blur, DIM and text
 * contrast before committing.
 */
@Composable
private fun WallpaperPreviewDialog(
    appearance: AppearanceSettings,
    onDismiss: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> systemDark
    }
    // Decode + prepare (downscale + blur) off the UI thread; keyed by path
    // and radius so a stale load is cancelled and the old bitmap is released
    // to GC. The blur is applied here (one-shot) because WallpaperLayer only
    // draws static, pre-blurred bitmaps — identical to the app background.
    val bitmap by produceState<ImageBitmap?>(null, appearance.wallpaperPath, appearance.blurRadiusPx) {
        val raw = withContext(Dispatchers.IO) { loadWallpaperBitmap(appearance.wallpaperPath) }
        value = raw?.let {
            withContext(Dispatchers.Default) { prepareWallpaper(it, appearance.blurRadiusPx) }
        }
    }
    val previewBg = if (dark) Color(0xFF121316) else Color(0xFFFBF9FA)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("壁纸预览") },
        text = {
            Box(Modifier.fillMaxWidth().height(320.dp).clip(MaterialTheme.shapes.medium)) {
                WallpaperLayer(
                    bitmap = bitmap,
                    dimAmount = appearance.dimAlpha,
                    scrimColor = if (dark) Color.Black else Color.White,
                    // Always paint a solid backdrop so a failed decode still
                    // shows a clean panel instead of a transparent hole.
                    backgroundColor = previewBg,
                    contentScale = if (appearance.wallpaperFit) ContentScale.Fit else ContentScale.Crop,
                    verticalOffsetRatio = appearance.wallpaperOffsetY,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        if (appearance.wallpaperEnabled && bitmap == null) {
                            Text(
                                "壁纸加载失败：${appearance.wallpaperPath}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            "主标题（${if (dark) "亮色文字" else "黑色文字"}）",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (dark) Color(0xFFE6E1E9) else Color(0xFF1C1B1F),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正文示例：在任意壁纸上文字都保持清晰可读，得益于 DIM 遮罩与字体对比度。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (dark) Color(0xFFCBC5CF) else Color(0xFF44474E),
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    (if (dark) Color(0xFF1C1B1F) else Color(0xFFFFFFFF))
                                        .copy(alpha = if (appearance.wallpaperEnabled) 0.86f else 1f),
                                ),
                        ) {
                            Text(
                                "表面卡片",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (dark) Color(0xFFE6E1E9) else Color(0xFF1C1B1F),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
        },
    )
}
