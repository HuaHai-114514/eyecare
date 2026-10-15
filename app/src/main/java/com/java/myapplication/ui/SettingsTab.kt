package com.java.myapplication.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import kotlin.math.abs
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

        Spacer(Modifier.height(20.dp))

        // ---------------- 计时 ----------------
        SettingsGroupCard(title = "计时") {
            MinuteSettingRow(
                label = "用眼提醒间隔",
                value = s.workMinutes,
                range = 1..120,
                onCommit = { v -> update({ it.copy(workMinutes = v) }, "用眼提醒间隔：$v 分钟", settled = true) }
            )
            Spacer(Modifier.height(16.dp))
            // 休息时长是秒级，跨度过长，保留步进器更顺手
            NumberSettingRow(
                label = "休息时长",
                unit = "秒",
                value = s.restSeconds,
                range = 5..300,
                onCommit = { v -> update({ it.copy(restSeconds = v) }, "休息时长：$v 秒", settled = true) }
            )
            Spacer(Modifier.height(16.dp))
            MinuteSettingRow(
                label = "超时二次提醒间隔",
                value = s.overdueRemindMinutes,
                range = AppSettings.OVERDUE_REMIND_RANGE,
                onCommit = { v -> update({ it.copy(overdueRemindMinutes = v) }, "超时二次提醒：$v 分钟", settled = true) }
            )
            Spacer(Modifier.height(16.dp))
            HourMinuteSettingRow(
                label = "每日用眼目标",
                value = s.dailyGoalMinutes,
                minTotalMinutes = 10,
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
            Button(
                onClick = { showDiagnostics = true },
                shape = CircleShape,
                colors = previewButtonColors(),
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
            modifier = Modifier.widthIn(min = 52.dp),
            textAlign = TextAlign.Center,
            fontSize = 20.sp,
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

// ============ 滚轮选择器（v2.6.12 样式调整） ============

/** 滚轮每一项的高度。文字放大到 20sp 后 44dp 会显得贴边，一并加到 48dp */
private val WheelItemHeight = 48.dp

/** 滚轮可见项数（奇数，保证中间永远正好压着一项） */
private const val WheelVisibleItems = 5

/** 滚轮项文字：比正文大一号，看得清 */
private val WheelItemTextSize = 20.sp

/**
 * 算出 [listState] 当前压在正中的那一项下标。
 *
 * 按「项中心离视口几何中心的距离」找最近的一个，而不是取 `firstVisibleItemIndex`
 * —— 后者只会告诉你是「第一个可见项」，吸附动画收尾时它可能已经翻页，
 * 这就是之前偶现「停稳后选中的是旁边一格」的原因。
 */
private fun centeredItemIndex(listState: LazyListState): Int? {
    val info = listState.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo
        .minByOrNull { abs(it.offset + it.size / 2f - center) }
        ?.index
}

/**
 * 单/双栏通用的滚轮。
 *
 * 用 [LazyColumn] + 吸附惯性实现：每项等高，靠上下 contentPadding 让首尾项
 * 也能滚到正中；离中心越远越小越淡，用 `graphicsLayer` 在绘制阶段算，
 * 所以滚动时只重绘、不重组整个列表。
 *
 * [onSettled] 在每次滚动停稳后回调正中项下标，只用来更新「对话框里当前选中的值」，
 * **不直接写设置** —— 写盘统一交给「确定」按钮，见 [WheelPickerDialog]。
 */
@Composable
private fun WheelColumn(
    options: List<Int>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
    onSettled: (Int) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex.coerceIn(options.indices)
    )
    val flingBehavior = rememberSnapFlingBehavior(listState)
    val settle by rememberUpdatedState(onSettled)

    // 停稳后把正中项报上去。吸附动画结束（isScrollInProgress 转 false）时
    // 布局已经落定，此刻读到的 offset 才是最终位置。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val idx = centeredItemIndex(listState) ?: return@collect
                settle(idx)
            }
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        modifier = modifier,
        contentPadding = PaddingValues(
            vertical = WheelItemHeight * (WheelVisibleItems / 2)
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(options.size) { index ->
            val option = options[index]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WheelItemHeight)
                    .graphicsLayer {
                        val vp = listState.layoutInfo
                        val item = vp.visibleItemsInfo
                            .firstOrNull { it.index == index }
                            ?: return@graphicsLayer
                        val center = (vp.viewportStartOffset +
                            vp.viewportEndOffset) / 2f
                        val dist = abs(
                            item.offset + item.size / 2f - center
                        ) / (item.size * 2f)
                        val near = (1f - dist).coerceIn(0f, 1f)
                        val sc = 0.7f + 0.3f * near
                        scaleX = sc
                        scaleY = sc
                        alpha = 0.35f + 0.65f * near
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$option",
                    fontSize = WheelItemTextSize,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 滚轮对话框里中间的选中底色，让「停在哪个值上」一眼可见 */
@Composable
private fun WheelCenterBand(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WheelItemHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    )
}

/**
 * 滚轮选择对话框（单栏）。
 *
 * 交互按「停稳只更新选中态、点确定才写盘」设计：滚动过程中不会半路改设置，
 * 误触滑过也不会把值改掉；点确定外的任何方式关闭都保持原值。
 */
@Composable
private fun WheelPickerDialog(
    title: String,
    unit: String,
    options: List<Int>,
    initialIndex: Int,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                WheelCenterBand()
                WheelColumn(
                    options = options,
                    initialIndex = initialIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WheelItemHeight * WheelVisibleItems),
                    onSettled = onSelect
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 分钟设置行：右侧按钮显示当前值，点开是滚轮选择器。
 *
 * 只用于「分钟」为单位的两项；每日用眼目标跨度大，另见 [HourMinuteSettingRow]。
 */
@Composable
private fun MinuteSettingRow(
    label: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit
) {
    val options = remember(range) { range.toList() }
    var showPicker by remember { mutableStateOf(false) }
    // 对话框内的「当前选中」是暂存值，只有点确定才回调出去
    var draft by remember { mutableStateOf(value) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = {
                draft = value
                showPicker = true
            },
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "$value 分钟",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (showPicker) {
        WheelPickerDialog(
            title = label,
            unit = "分钟",
            options = options,
            initialIndex = (value - range.first) - WheelVisibleItems / 2,
            currentIndex = draft,
            onSelect = { idx -> options.getOrNull(idx)?.let { draft = it } },
            onConfirm = {
                showPicker = false
                if (draft != value) onCommit(draft)
            },
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * 每日用眼目标：双栏滚轮，左边小时、右边分钟。
 *
 * 存进 [AppSettings.dailyGoalMinutes] 的仍然是总分钟数（小时×60+分钟），
 * 所以统计页、达标率那套逻辑完全不用动。
 */
@Composable
private fun HourMinuteSettingRow(
    label: String,
    value: Int,
    minTotalMinutes: Int,
    onCommit: (Int) -> Unit
) {
    val hourOptions = remember { (0..12).toList() }
    val minuteOptions = remember { (0..59).toList() }

    var showPicker by remember { mutableStateOf(false) }
    var draftHour by remember { mutableStateOf(value / 60) }
    var draftMinute by remember { mutableStateOf(value % 60) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = {
                draftHour = value / 60
                draftMinute = value % 60
                showPicker = true
            },
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "${value / 60} 小时 ${value % 60} 分",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (showPicker) {
        val total = draftHour * 60 + draftMinute
        val tooSmall = total < minTotalMinutes
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                WheelCenterBand()
                                WheelColumn(
                                    options = hourOptions,
                                    initialIndex = draftHour - WheelVisibleItems / 2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(WheelItemHeight * WheelVisibleItems),
                                    onSettled = { idx ->
                                        hourOptions.getOrNull(idx)?.let { draftHour = it }
                                    }
                                )
                            }
                        }
                        Text(
                            "小时",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                WheelCenterBand()
                                WheelColumn(
                                    options = minuteOptions,
                                    initialIndex = draftMinute - WheelVisibleItems / 2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(WheelItemHeight * WheelVisibleItems),
                                    onSettled = { idx ->
                                        minuteOptions.getOrNull(idx)?.let { draftMinute = it }
                                    }
                                )
                            }
                        }
                        Text(
                            "分",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 只设 0 小时几分钟是允许的（你确认过），但不能低于目标下限
                    if (tooSmall) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "时长不能少于 $minTotalMinutes 分钟",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !tooSmall,
                    onClick = {
                        showPicker = false
                        val picked = draftHour * 60 + draftMinute
                        if (picked != value) onCommit(picked)
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
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
            fontSize = 15.sp,
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
                fontSize = 20.sp,
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
    // v2.6.14（临时诊断）：日志导出状态

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
            Button(
                onClick = { openUrl(context, AppInfo.REPO_URL) },
                shape = CircleShape,
                colors = previewButtonColors()
            ) {
                Text("打开仓库")
            }
            Button(
                onClick = { showDisclaimer = true },
                shape = CircleShape,
                colors = previewButtonColors()
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

/**
 * 设置页动作按钮的统一配色（v2.6.17）。
 *
 * 用户反馈「设置界面按钮没有高亮，期望和开关颜色一样（绿色）」。
 * 开关打开态用的是 `MaterialTheme.colorScheme.primary`，这里取同一色作为
 * 实心填充，让「可点的动作」与「已开启的开关」共用一套绿色视觉语言。
 */
@Composable
private fun previewButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
)
