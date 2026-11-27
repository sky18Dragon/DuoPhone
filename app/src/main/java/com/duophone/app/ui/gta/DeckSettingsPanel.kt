package com.duophone.app.ui.gta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.BuildConfig
import com.duophone.app.stage.AssetSource
import com.duophone.app.stage.GtaStageState
import com.duophone.app.stage.PhoneOverlayConfig
import com.duophone.app.stage.StageThemeMode

@Composable
fun DeckSettingsPanel(
    stageState: GtaStageState,
    onSelectThemeMode: (StageThemeMode) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
    onToggleCalibrationGrid: (Boolean) -> Unit,
    onUpdateOverlay: ((PhoneOverlayConfig) -> PhoneOverlayConfig) -> Unit,
    onResetOverlay: () -> Unit,
    onRescanAssets: () -> Unit,
    onReplayOpening: () -> Unit,
    onOpenLegacyDiagnostics: () -> Unit,
    showLegacyDiagnosticsEntry: Boolean = true,
    onRelaunchDualDisplay: (() -> Unit)? = null,
) {
    val palette = LocalGtaDeckPalette.current
    val cfg = stageState.stageConfig.phoneOverlay
    val assets = stageState.resolvedAssets
    var showManualSliders by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SettingsCard(tag = "APPEARANCE", title = "主题与交互体验") {
                Text(
                    text = "昼夜主题模式（双屏实时同步）",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StageThemeMode.entries.forEach { mode ->
                        val selected = stageState.themeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) palette.accentPrimary else palette.surfaceElevated)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) palette.accentPrimary else palette.border,
                                    shape = RoundedCornerShape(7.dp),
                                )
                                .clickable { onSelectThemeMode(mode) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.label,
                                color = if (selected) palette.accentOnPrimary else palette.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                ToggleRow(
                    label = "GTA UI 按键与短信音效（通话中自动避让静音）",
                    checked = stageState.soundEnabled,
                    onCheckedChange = onToggleSound,
                )
                ToggleRow(
                    label = "掌机按键触觉反馈",
                    checked = stageState.hapticEnabled,
                    onCheckedChange = onToggleHaptic,
                )
                ToggleRow(
                    label = "待机手持呼吸与物理微倾反馈",
                    checked = cfg.breathingEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateOverlay { it.copy(breathingEnabled = enabled) }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButtonPill(
                        label = "重播开场画面",
                        containerColor = palette.accentPrimary,
                        contentColor = palette.accentOnPrimary,
                        borderColor = palette.accentPrimary,
                        onClick = onReplayOpening,
                    )
                    if (onRelaunchDualDisplay != null) {
                        SettingsButtonPill(
                            label = "重同步双屏窗口",
                            containerColor = palette.surfaceElevated,
                            contentColor = palette.accentTeal,
                            borderColor = palette.borderStrong,
                            onClick = onRelaunchDualDisplay,
                        )
                    }
                }
            }
        }

        item {
            SettingsCard(tag = "CALIBRATION", title = "上屏透视贴合校准") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("当前对齐模式", color = palette.textSecondary, fontSize = 11.sp)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(palette.surfaceHighlight)
                            .border(1.dp, palette.borderStrong, RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (cfg.quadCorners != null) "四点透视精校模式" else "手动矩形微调模式",
                            color = if (cfg.quadCorners != null) palette.accentTeal else palette.accentAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                ToggleRow(
                    label = "在上屏显示校准网格与坐标 HUD",
                    checked = stageState.isCalibrationGridVisible,
                    onCheckedChange = onToggleCalibrationGrid,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButtonPill(
                        label = "恢复默认精校",
                        containerColor = palette.surfaceElevated,
                        contentColor = palette.accentAmber,
                        borderColor = palette.borderStrong,
                        onClick = onResetOverlay,
                    )
                    SettingsButtonPill(
                        label = if (showManualSliders) "收起手动微调滑块" else "展开手动微调滑块",
                        containerColor = palette.surfaceElevated,
                        contentColor = palette.textPrimary,
                        borderColor = palette.borderStrong,
                        onClick = { showManualSliders = !showManualSliders },
                    )
                }

                if (showManualSliders) {
                    Spacer(Modifier.height(4.dp))
                    CalibrationSlider(
                        label = "水平位置 X",
                        value = cfg.leftFraction,
                        range = 0.05f..0.80f,
                        valueText = "%.3f".format(cfg.leftFraction),
                        onValueChange = { v -> onUpdateOverlay { it.copy(leftFraction = v) } },
                    )
                    CalibrationSlider(
                        label = "垂直位置 Y",
                        value = cfg.topFraction,
                        range = 0.02f..0.75f,
                        valueText = "%.3f".format(cfg.topFraction),
                        onValueChange = { v -> onUpdateOverlay { it.copy(topFraction = v) } },
                    )
                    CalibrationSlider(
                        label = "屏幕宽度 W",
                        value = cfg.widthFraction,
                        range = 0.12f..0.55f,
                        valueText = "%.3f".format(cfg.widthFraction),
                        onValueChange = { v -> onUpdateOverlay { it.copy(widthFraction = v) } },
                    )
                    CalibrationSlider(
                        label = "屏幕高度 H",
                        value = cfg.heightFraction,
                        range = 0.30f..0.90f,
                        valueText = "%.3f".format(cfg.heightFraction),
                        onValueChange = { v -> onUpdateOverlay { it.copy(heightFraction = v) } },
                    )
                    CalibrationSlider(
                        label = "仰俯透视 RotX",
                        value = cfg.rotationX,
                        range = -30f..30f,
                        valueText = "${cfg.rotationX.toInt()}°",
                        onValueChange = { v -> onUpdateOverlay { it.copy(rotationX = v) } },
                    )
                    CalibrationSlider(
                        label = "左右偏航 RotY",
                        value = cfg.rotationY,
                        range = -30f..30f,
                        valueText = "${cfg.rotationY.toInt()}°",
                        onValueChange = { v -> onUpdateOverlay { it.copy(rotationY = v) } },
                    )
                    CalibrationSlider(
                        label = "平面倾角 RotZ",
                        value = cfg.rotationZ,
                        range = -25f..25f,
                        valueText = "${cfg.rotationZ.toInt()}°",
                        onValueChange = { v -> onUpdateOverlay { it.copy(rotationZ = v) } },
                    )
                    CalibrationSlider(
                        label = "屏幕圆角 Radius",
                        value = cfg.cornerRadiusDp,
                        range = 4f..44f,
                        valueText = "${cfg.cornerRadiusDp.toInt()}dp",
                        onValueChange = { v -> onUpdateOverlay { it.copy(cornerRadiusDp = v) } },
                    )
                }
            }
        }

        item {
            SettingsCard(tag = "ASSETS & MODEM", title = "素材目录与加载状态") {
                Text(
                    text = assets.assetsDirPath,
                    color = palette.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                AssetStatusRow("日间开场 (opening_light.mp4)", if (assets.openingLightVideoUri != null) "已加载" else "程序化占位")
                AssetStatusRow("夜间开场 (opening_dark.mp4)", if (assets.openingDarkVideoUri != null) "已加载" else "程序化占位")
                AssetStatusRow("日间底图 (idle_hand_phone_light.png)", formatAssetSource(assets.idleLightImagePath))
                AssetStatusRow("夜间底图 (idle_hand_phone_dark.png)", formatAssetSource(assets.idleDarkImagePath))
                AssetStatusRow("日间通话 (dialing_pose_light.png)", formatAssetSource(assets.dialingLightImagePath))
                AssetStatusRow("夜间通话 (dialing_pose_dark.png)", formatAssetSource(assets.dialingDarkImagePath))
                Spacer(Modifier.height(4.dp))
                SettingsButtonPill(
                    label = "重新扫描素材目录",
                    containerColor = palette.surfaceElevated,
                    contentColor = palette.accentTeal,
                    borderColor = palette.borderStrong,
                    onClick = onRescanAssets,
                )
            }
        }

        item {
            SettingsCard(tag = "DIAGNOSTICS", title = "高级硬件与 QDC507 诊断") {
                Text(
                    text = "保留完整模块状态、AT 调试终端、语音运行时部署与 USB 配置备份恢复入口。",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(4.dp))
                if (showLegacyDiagnosticsEntry) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(palette.surfaceElevated)
                            .border(1.dp, palette.accentTeal, RoundedCornerShape(7.dp))
                            .clickable(onClick = onOpenLegacyDiagnostics),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "进入高级硬件与 AT 诊断工作台",
                            color = palette.accentTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(palette.surfaceHighlight)
                            .border(1.dp, palette.border, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "工程诊断工作台暂不可用",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "版本与构建信息",
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                    )
                    Text(
                        text = "Duo Phone v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE.uppercase()}",
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    tag: String,
    title: String,
    content: @Composable () -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tag,
                color = palette.accentTeal,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        content()
    }
}

@Composable
private fun SettingsButtonPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = palette.textPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.accentGreen,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.surfaceHighlight,
                uncheckedBorderColor = palette.borderStrong,
            ),
        )
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = palette.textPrimary, fontSize = 11.sp)
            Text(valueText, color = palette.accentTeal, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = palette.accentPrimary,
                activeTrackColor = palette.accentBlue,
                inactiveTrackColor = palette.surfaceHighlight,
            ),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
private fun AssetStatusRow(name: String, status: String) {
    val palette = LocalGtaDeckPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, color = palette.textPrimary, fontSize = 10.sp)
        Text(status, color = palette.accentGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun formatAssetSource(source: AssetSource?): String = when (source) {
    null -> "程序化占位"
    is AssetSource.ExternalFile -> "已加载 (外部)"
    is AssetSource.BundledAsset -> "已加载 (内置)"
}
