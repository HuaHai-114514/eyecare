package com.java.myapplication.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.java.myapplication.data.AppInfo
import com.java.myapplication.data.AppSettings
import com.java.myapplication.notify.RestSoundPlayer
import com.java.myapplication.notify.RestSoundSource
import com.java.myapplication.ui.components.EyeIcons
import com.java.myapplication.ui.theme.EaseOutStrong
import com.java.myapplication.ui.theme.MotionDurations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 护眼设置页（v2.4：即时生效 + 分组卡片流）。
 *
 * 这一版把原先「只读摘要 + 弹窗编辑」的两段式改掉：
 * 所有可设参数直接铺在页面上，改一下就存一下（即时生效），
 * 不再有「修改设置」按钮，也不再有保存对话框。
 *
 * 数字项改成「−/＋」步进器（按住可连点），免打扰时间改用系统风格时间选择器，
 * 每次落盘都回一条 Snackbar，让「即时生效」看得见。
 */
@Composable
fun SettingsTab(
    viewModel: EyeCareViewModel,
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    // 「到点自动全屏」开启前的二次确认（v2.4.2）：开启后会打断其他应用，需用户明确知晓
    var showFullScreenConfirm by remember { mutableStateOf(false) }
    // 连点型设置（步进器）的待播报文案：等用户停下来再回一条，避免 Snackbar 以 ~11Hz 反复重放
    var pendingAnnounce by remember { mutableStateOf<String?>(null) }
    val s = viewModel.settings
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingAnnounce) {
        val msg = pendingAnnounce ?: return@LaunchedEffect
        delay(600L) // 等连点停下来
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        pendingAnnounce = null
    }

    // 统一提交入口：基于**当前最新**设置做变换后落盘，避免闭包捕获旧值互相覆盖。
    // [announce] 是保存反馈文案。
    // [settled] = true 用于步进器这类会连续触发的操作：不当场弹 Snackbar，
    //   而是交给上面的 LaunchedEffect 等连点停止后再回一条（否则每 90ms 一次
    //   dismiss+show，Snackbar 会以约 11Hz 反复重播入场动画，看着就是在闪）。
    // 开关 / 选项片 / 时间选择器这类一次性操作走 settled = false，立即回执。
    fun update(
        transform: (AppSettings) -> AppSettings,
        announce: String = "设置已保存",
        settled: Boolean = false
    ) {
        viewModel.updateSettings(context, transform(viewModel.settings))
        if (settled) {
            pendingAnnounce = announce
        } else {
            pendingAnnounce = null // 收掉还没弹的连点提示，避免过一会儿又冒出来
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(announce, duration = SnackbarDuration.Short)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
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
                onCommit = { v -> update({ it.copy(workMinutes = v) }, "用眼提醒间隔：$v 分钟", settled = true) }
            )
            Spacer(Modifier.height(16.dp))
            NumberSettingRow(
                label = "休息时长",
                unit = "秒",
                value = s.restSeconds,
                range = 5..300,
                onCommit = { v -> update({ it.copy(restSeconds = v) }, "休息时长：$v 秒", settled = true) }
            )
            Spacer(Modifier.height(16.dp))
            NumberSettingRow(
                label = "超时二次提醒间隔",
                unit = "分钟",
                value = s.overdueRemindMinutes,
                range = AppSettings.OVERDUE_REMIND_RANGE,
                onCommit = { v -> update({ it.copy(overdueRemindMinutes = v) }, "超时二次提醒：$v 分钟", settled = true) }
            )
            Spacer(Modifier.height(16.dp))
            NumberSettingRow(
                label = "每日用眼目标",
                unit = "分钟",
                value = s.dailyGoalMinutes,
                range = 10..720,
                onCommit = { v -> update({ it.copy(dailyGoalMinutes = v) }, "每日用眼目标：$v 分钟", settled = true) }
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---------------- 提醒 ----------------
        SettingsGroupCard(title = "提醒") {
            SwitchSettingRow(
                title = "到点自动全屏",
                subtitle = "用眼到点时直接弹出全屏休息页（后台也不会漏）；关闭则只发通知横幅",
                checked = s.autoFullScreen,
                // 关闭 → 直接生效；开启 → 先弹二次确认（明确告知会打断其他应用）
                onChecked = { v ->
                    if (v) showFullScreenConfirm = true
                    else update({ it.copy(autoFullScreen = false) }, "已关闭「到点自动全屏」")
                }
            )
            Spacer(Modifier.height(16.dp))
            SwitchSettingRow(
                title = "启用免打扰时段",
                subtitle = "时段内只计时不打扰，出时段自动恢复",
                checked = s.dndEnabled,
                onChecked = { v ->
                    update({ it.copy(dndEnabled = v) }, if (v) "免打扰时段已开启" else "免打扰时段已关闭")
                }
            )
            if (s.dndEnabled) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TimeSettingField(
                            label = "开始时间",
                            minuteOfDay = s.dndStartMinute,
                            onCommit = { v ->
                                update({ it.copy(dndStartMinute = v) }, "免打扰开始：${AppSettings.formatMinuteOfDay(v)}")
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TimeSettingField(
                            label = "结束时间",
                            minuteOfDay = s.dndEndMinute,
                            onCommit = { v ->
                                update({ it.copy(dndEndMinute = v) }, "免打扰结束：${AppSettings.formatMinuteOfDay(v)}")
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SwitchSettingRow(
                title = "休息结束提示音",
                subtitle = "休息结束时由应用响一声",
                checked = s.restEndSoundEnabled,
                onChecked = { v ->
                    update({ it.copy(restEndSoundEnabled = v) }, if (v) "休息结束提示音已开启" else "休息结束提示音已关闭")
                }
            )
            if (s.restEndSoundEnabled) {
                Spacer(Modifier.height(16.dp))
                SwitchSettingRow(
                    title = "同时振动",
                    subtitle = "提示音响起时短振动一下，手机扣在桌上也能察觉",
                    checked = s.restEndSoundVibrate,
                    onChecked = { v ->
                        update({ it.copy(restEndSoundVibrate = v) }, if (v) "已开启同时振动" else "已关闭同时振动")
                    }
                )
                Spacer(Modifier.height(16.dp))
                SoundSourceSetting(
                    current = s.restEndSoundSource,
                    onSelect = { v -> update({ it.copy(restEndSoundSource = v) }, "提示音音源：${v.label}") }
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
                onChecked = { v ->
                    update({ it.copy(sitReminderEnabled = v) }, if (v) "久坐提醒已开启" else "久坐提醒已关闭")
                }
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
                            onClick = {
                                update({ it.copy(sitIntervalMinutes = minutes) }, "久坐提醒间隔：$minutes 分钟")
                            },
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
                onChecked = { v ->
                    update({ it.copy(autoNightMode = v) }, if (v) "已开启自动夜间模式" else "已关闭自动夜间模式")
                }
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
                "如果提醒不准时，按清单逐项检查通知权限、精确闹钟、悬浮窗（后台全屏）、电池优化与自启动",
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

    // 「到点自动全屏」二次确认（v2.4.2）：必须让用户明确知道会打断其他操作
    if (showFullScreenConfirm) {
        AlertDialog(
            onDismissRequest = { showFullScreenConfirm = false },
            title = { Text("开启「到点自动全屏」？") },
            text = {
                Text(
                    "开启后，用眼到点时会在你正在使用的应用之上强制弹出全屏休息页，" +
                        "可能打断正在进行的操作（如看视频、开会、导航）。\n\n" +
                        "手机息屏或锁屏时不会弹出。是否确认开启？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFullScreenConfirm = false
                    update({ it.copy(autoFullScreen = true) }, "已开启「到点自动全屏」")
                }) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { showFullScreenConfirm = false }) { Text("取消") }
            }
        )
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

/** 数字设置行：左边标题 + 范围说明，右边「−/数值/＋」步进器 */
@Composable
private fun NumberSettingRow(
    label: String,
    unit: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit
) {
    // 跨度大的项步子也大一点，避免从 10 按到 720 要按七十下
    val step = if (range.last - range.first > 60) 5 else 1

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
        StepperButton(
            symbol = "−",
            contentDescription = "$label 减少 $step",
            enabled = value > range.first,
            onStep = { onCommit((value - step).coerceIn(range)) }
        )
        Text(
            text = value.toString(),
            modifier = Modifier.widthIn(min = 46.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        StepperButton(
            symbol = "＋",
            contentDescription = "$label 增加 $step",
            enabled = value < range.last,
            onStep = { onCommit((value + step).coerceIn(range)) }
        )
    }
}

/** 圆形步进按钮：点一下走一步，按住约 0.4 秒后每 90 毫秒连走一步 */
@Composable
private fun StepperButton(
    symbol: String,
    contentDescription: String,
    enabled: Boolean,
    onStep: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val desc = contentDescription
    // 长按连点期间会连续调用，用 rememberUpdatedState 保证每次读到的都是最新值的闭包
    val currentStep by rememberUpdatedState(onStep)
    val accent = MaterialTheme.colorScheme.primary
    val tint = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    // 按压态：只走 graphicsLayer 的 scale/alpha（GPU 合成），不碰 size/padding
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(MotionDurations.PRESS_MS, easing = EaseOutStrong),
        label = "stepperPress"
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (enabled) 1f else 0.6f
            }
            .clip(CircleShape)
            .background(
                if (enabled) accent.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            )
            // 无障碍：只给 contentDescription 只能"被读出来"，没有 Role + onClick 动作
            // TalkBack 是激活不了的，必须补上 role 与 onClick 语义动作。
            .semantics {
                this.contentDescription = desc
                role = Role.Button
                if (enabled) {
                    onClick(label = desc) {
                        currentStep()
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        currentStep()
                        val repeat = scope.launch {
                            // 先等一小会儿再开始连点，避免轻点被当成两下
                            delay(400L)
                            while (true) {
                                currentStep()
                                delay(90L)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = tint,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 时间设置：点一下弹系统风格时间选择器（24 小时制），不用手敲 HH:mm */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSettingField(
    label: String,
    minuteOfDay: Int,
    onCommit: (Int) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = AppSettings.formatMinuteOfDay(minuteOfDay),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (showPicker) {
        val pickerState = rememberTimePickerState(
            initialHour = (minuteOfDay / 60) % 24,
            initialMinute = minuteOfDay % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = pickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPicker = false
                    val picked = pickerState.hour * 60 + pickerState.minute
                    if (picked != minuteOfDay) onCommit(picked)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
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
    // 版本号走 PackageManager（binder IPC），不能放在组合里直接调 ——
    // 这一页首次进入时正好在入场动画的几帧里，一次 IPC 就可能吃掉好几帧。
    val versionName = remember(context) { AppInfo.versionName(context) }

    SoftCard {
        Text(
            "关于",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "护眼时光 v$versionName",
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