package com.java.myapplication.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.java.myapplication.data.AppInfo
import com.java.myapplication.data.AppSettings
import com.java.myapplication.notify.RestSoundPlayer
import com.java.myapplication.notify.RestSoundSource
import com.java.myapplication.ui.components.EyeIcons

/**
 * 护眼设置页（v2.4：即时生效 + 分组卡片流）。
 *
 * 这一版把原先「只读摘要 + 弹窗编辑」的两段式改掉：
 * 所有可设参数直接铺在页面上，改一下就存一下（即时生效），
 * 不再有「修改设置」按钮，也不再有保存对话框。
 *
 * 数字输入框在**失焦 / 键盘 Done** 时提交，避免每敲一个数字都写盘；
 * 开关、下拉、选项片则点一下立即提交。
 */
@Composable
fun SettingsTab(viewModel: EyeCareViewModel, context: Context) {
    var showDiagnostics by remember { mutableStateOf(false) }
    val s = viewModel.settings

    // 统一提交入口：基于**当前最新**设置做变换后落盘，避免闭包捕获旧值互相覆盖
    fun update(transform: (AppSettings) -> AppSettings) {
        viewModel.updateSettings(context, transform(viewModel.settings))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            "护眼设置",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "改完即生效，无需保存",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // ---------------- 计时 ----------------
        SettingsGroupCard(title = "计时") {
            NumberSettingRow(
                label = "用眼提醒间隔",
                unit = "分钟",
                value = s.workMinutes,
                range = 1..120,
                onCommit = { v -> update { it.copy(workMinutes = v) } }
            )
            Spacer(Modifier.height(16.dp))
            NumberSettingRow(
                label = "休息时长",
                unit = "秒",
                value = s.restSeconds,
                range = 5..300,
                onCommit = { v -> update { it.copy(restSeconds = v) } }
            )
            Spacer(Modifier.height(16.dp))
            NumberSettingRow(
                label = "每日用眼目标",
                unit = "分钟",
                value = s.dailyGoalMinutes,
                range = 10..720,
                onCommit = { v -> update { it.copy(dailyGoalMinutes = v) } }
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---------------- 提醒 ----------------
        SettingsGroupCard(title = "提醒") {
            SwitchSettingRow(
                title = "到点自动全屏",
                subtitle = "用眼到点时直接弹出全屏休息页；关闭则只发通知横幅",
                checked = s.autoFullScreen,
                onChecked = { v -> update { it.copy(autoFullScreen = v) } }
            )
            Spacer(Modifier.height(16.dp))
            SwitchSettingRow(
                title = "启用免打扰时段",
                subtitle = "时段内只计时不打扰，出时段自动恢复",
                checked = s.dndEnabled,
                onChecked = { v -> update { it.copy(dndEnabled = v) } }
            )
            if (s.dndEnabled) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TimeSettingField(
                            label = "开始 (HH:mm)",
                            minuteOfDay = s.dndStartMinute,
                            onCommit = { v -> update { it.copy(dndStartMinute = v) } }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TimeSettingField(
                            label = "结束 (HH:mm)",
                            minuteOfDay = s.dndEndMinute,
                            onCommit = { v -> update { it.copy(dndEndMinute = v) } }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SwitchSettingRow(
                title = "休息结束提示音",
                subtitle = "休息结束时由应用响一声",
                checked = s.restEndSoundEnabled,
                onChecked = { v -> update { it.copy(restEndSoundEnabled = v) } }
            )
            if (s.restEndSoundEnabled) {
                Spacer(Modifier.height(16.dp))
                SwitchSettingRow(
                    title = "同时振动",
                    subtitle = "提示音响起时短振动一下，手机扣在桌上也能察觉",
                    checked = s.restEndSoundVibrate,
                    onChecked = { v -> update { it.copy(restEndSoundVibrate = v) } }
                )
                Spacer(Modifier.height(16.dp))
                SoundSourceSetting(
                    current = s.restEndSoundSource,
                    onSelect = { v -> update { it.copy(restEndSoundSource = v) } }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------- 久坐 ----------------
        SettingsGroupCard(title = "久坐") {
            SwitchSettingRow(
                title = "久坐提醒",
                subtitle = "按间隔提醒起身活动，与用眼提醒独立计时",
                checked = s.sitReminderEnabled,
                onChecked = { v -> update { it.copy(sitReminderEnabled = v) } }
            )
            if (s.sitReminderEnabled) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "提醒间隔",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppSettings.SIT_INTERVAL_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = s.sitIntervalMinutes == minutes,
                            onClick = { update { it.copy(sitIntervalMinutes = minutes) } },
                            label = { Text("$minutes 分钟") }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------- 外观 ----------------
        SettingsGroupCard(title = "外观") {
            SwitchSettingRow(
                title = "自动夜间模式",
                subtitle = "跟随系统深色状态自动切换护眼配色",
                checked = s.autoNightMode,
                onChecked = { v -> update { it.copy(autoNightMode = v) } }
            )
        }

        Spacer(Modifier.height(14.dp))

        // 提醒可靠性自检入口：提醒不准时，用户自己就能查原因
        SoftCard {
            Text(
                "提醒可靠性自检",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "如果提醒不准时，按清单逐项检查通知权限、精确闹钟、全屏通知、电池优化与自启动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showDiagnostics = true },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始自检")
            }
        }

        Spacer(Modifier.height(14.dp))

        // 关于：版本号、开源仓库、免责声明
        AboutCard()

        Spacer(Modifier.height(24.dp))
    }

    if (showDiagnostics) {
        DiagnosticsPanel(onDismiss = { showDiagnostics = false })
    }
}

/** 分组卡片：顶部一行组名（主色小字），下面跟本组控件 */
@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SoftCard {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** 数字设置行：左边标题 + 范围说明，右边窄输入框；失焦 / 回车时提交 */
@Composable
private fun NumberSettingRow(
    label: String,
    unit: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val focusManager = LocalFocusManager.current

    fun commit() {
        val v = text.toIntOrNull()?.coerceIn(range) ?: value
        text = v.toString()
        if (v != value) onCommit(v)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "范围 ${range.first}–${range.last} $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
            modifier = Modifier
                .width(96.dp)
                .onFocusChanged { focusState -> if (!focusState.isFocused) commit() },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                commit()
                focusManager.clearFocus()
            }),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

/** 时间设置框（HH:mm）；失焦 / 回车时解析提交，非法值回退到原值 */
@Composable
private fun TimeSettingField(
    label: String,
    minuteOfDay: Int,
    onCommit: (Int) -> Unit
) {
    var text by remember(minuteOfDay) {
        mutableStateOf(AppSettings.formatMinuteOfDay(minuteOfDay))
    }
    val focusManager = LocalFocusManager.current

    fun commit() {
        val parsed = AppSettings.parseMinuteOfDay(text, minuteOfDay)
        text = AppSettings.formatMinuteOfDay(parsed)
        if (parsed != minuteOfDay) onCommit(parsed)
    }

    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isDigit() || it == ':' }.take(5)
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState -> if (!focusState.isFocused) commit() },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                commit()
                focusManager.clearFocus()
            }),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

/** 提示音音源：下拉选择 + 当前音源说明 + 试听按钮 */
@Composable
private fun SoundSourceSetting(
    current: RestSoundSource,
    onSelect: (RestSoundSource) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Text(
        "提示音音源",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = current.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = EyeIcons.ArrowDropDown,
                    contentDescription = "展开音源列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                RestSoundSource.CHOICES.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                source.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onSelect(source)
                            expanded = false
                        },
                        leadingIcon = {
                            RadioButton(
                                selected = current == source,
                                onClick = null
                            )
                        }
                    )
                }
            }
        }
        TextButton(onClick = { RestSoundPlayer.play(context, current) }) {
            Text("试听", color = MaterialTheme.colorScheme.primary)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = current.hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "音量跟随系统「通知音量」。静音 / 勿扰时会安静，只留通知与振动。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 开关行：左标题+副标题，右开关 */
@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/**
 * 「关于」区块：版本号 + 开源仓库地址（可点击）+ 免责声明入口。
 *
 * 仓库地址同时提供纯文本与按钮两种入口：文本方便长按复制，按钮方便直接跳转。
 * 跳转失败（设备无浏览器）时静默忽略，不影响应用本身。
 */
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    var showDisclaimer by remember { mutableStateOf(false) }

    SoftCard {
        Text(
            "关于",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "护眼时光 v${AppInfo.versionName(context)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "开源项目 · 欢迎查看源码与反馈问题",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        // 纯文本地址：便于长按复制（部分用户不方便直接跳转）
        Text(
            AppInfo.REPO_URL_SHORT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { openUrl(context, AppInfo.REPO_URL) }
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { openUrl(context, AppInfo.REPO_URL) },
                shape = CircleShape
            ) {
                Text("打开仓库")
            }
            OutlinedButton(
                onClick = { showDisclaimer = true },
                shape = CircleShape
            ) {
                Text("免责声明")
            }
        }
    }

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
    }
}

/** 跳浏览器打开链接；无浏览器等异常时静默忽略，绝不因为一个链接崩掉界面 */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}