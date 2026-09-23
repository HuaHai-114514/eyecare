package com.java.myapplication.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.java.myapplication.data.AppInfo
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.EyeTips
import com.java.myapplication.data.OnboardingStore
import com.java.myapplication.data.StatsStore
import com.java.myapplication.notify.RestNotifier
import com.java.myapplication.ui.components.DailyBarChart
import com.java.myapplication.ui.components.EyeProgressRing
import com.java.myapplication.ui.theme.*

private enum class Tab { TIMER, TIPS, REPORT, SETTINGS }

/** 报告页时间档位 */
private enum class ReportRange(val days: Int, val label: String) {
    DAY(1, "日"),
    WEEK(7, "周"),
    MONTH(30, "月")
}

@Composable
fun EyeCareApp(viewModel: EyeCareViewModel = viewModel()) {
    val context = LocalContext.current

    // 首次加载初始化（与 ticker 分离，避免 init 内的 resync 让界面提前切休息页后 ticker 无人回收）
    LaunchedEffect(Unit) {
        viewModel.init(context)
        RestNotifier.ensureChannel(context)
    }

    // ticker 由 onForeground 单点驱动，onCleared / onBackground 负责取消
    LaunchedEffect(Unit) {
        viewModel.startTicking(context)
    }

    // 通知权限（Android 13+）
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission(context)
        }
    }

    var currentTab by remember { mutableStateOf(Tab.TIMER) }

    // 首次启动引导：只在没看过时弹一次（走完或跳过都会写入完成标记）
    var showOnboarding by remember { mutableStateOf(!OnboardingStore.isDone(context)) }

    // 休息倒计时全屏页
    if (viewModel.phase == RestPhase.RESTING) {
        RestScreen(viewModel, context)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = currentTab == Tab.TIMER,
                    onClick = { currentTab = Tab.TIMER },
                    icon = { Text("⏱️", fontSize = 20.sp) },
                    label = { Text("护眼计时") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.TIPS,
                    onClick = { currentTab = Tab.TIPS },
                    icon = { Text("\uD83D\uDCA1", fontSize = 20.sp) },
                    label = { Text("护眼知识") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.REPORT,
                    onClick = { currentTab = Tab.REPORT },
                    icon = { Text("📊", fontSize = 20.sp) },
                    label = { Text("数据报告") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.SETTINGS,
                    onClick = { currentTab = Tab.SETTINGS },
                    icon = { Text("⚙️", fontSize = 20.sp) },
                    label = { Text("护眼设置") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (currentTab) {
                Tab.TIMER -> TimerTab(viewModel, context, onOpenSettings = { currentTab = Tab.SETTINGS })
                Tab.TIPS -> TipsTab()
                Tab.REPORT -> ReportTab(viewModel, context)
                Tab.SETTINGS -> SettingsTab(viewModel, context)
            }
        }
    }

    // 到点提醒确认弹窗：点了「开始休息」才开始休息计时
    if (viewModel.phase == RestPhase.AWAITING) {
        AlertDialog(
            onDismissRequest = { viewModel.postponeRest(context) },
            title = { Text("\uD83C\uDF3F 该让眼睛休息啦") },
            text = {
                Text(
                    "已连续用眼 ${viewModel.settings.workMinutes} 分钟。\n" +
                        "点「开始休息」后即刻起算 ${viewModel.settings.restSeconds} 秒远眺倒计时。"
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.startRest(context) },
                    shape = CircleShape
                ) {
                    Text("开始休息")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.postponeRest(context) }) {
                    Text("稍后再说")
                }
            }
        )
    }

    // 首次启动引导（v2.3.13）：免责声明 → 可靠性设置
    if (showOnboarding) {
        OnboardingDialog(
            onFinish = {
                OnboardingStore.markDone(context)
                showOnboarding = false
            }
        )
    }
}

// ============ 计时页 ============
@Composable
private fun TimerTab(
    viewModel: EyeCareViewModel,
    context: Context,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部：标题 + 设置
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "护眼时光",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "给眼睛一片呼吸的原野",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Text("⚙️", fontSize = 22.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // 免打扰进行中提示（有则显示）
        if (viewModel.inDndWindow) {
            SoftCard {
                Text(
                    "🌙 免打扰时段进行中",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "这段时间只为你计时，不会打扰你。出时段后自动恢复提醒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // 提醒开关
        EyeCareSwitchCard(
            enabled = viewModel.reminderEnabled,
            onToggle = { viewModel.setReminderEnabled(context, it) }
        )

        Spacer(Modifier.height(24.dp))

        // 眼眸进度环
        Box(contentAlignment = Alignment.Center) {
            EyeProgressRing(
                progress = viewModel.workProgress,
                size = 220.dp,
                strokeWidth = 20.dp,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = viewModel.formatTime(viewModel.elapsedSeconds),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    if (viewModel.pausedByScreenOff) "息屏已暂停计时" else "本次亮屏已用眼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "目标 ${viewModel.settings.workMinutes} 分钟，休息 ${viewModel.settings.restSeconds} 秒",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // 立即休息按钮（手动触发时才起算休息倒计时）
        Button(
            onClick = { viewModel.startRest(context) },
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("🌿 现在休息一下", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))

        // 健康知识卡片
        TipCard(tip = viewModel.currentTip)

        Spacer(Modifier.height(24.dp))

        // 今日用眼小结（实用向核心）
        TodayWorkSummaryCard(
            workSeconds = viewModel.todayWorkSeconds,
            longestStreakSeconds = viewModel.todayLongestStreak,
            restCount = viewModel.todayCount,
            goalMinutes = viewModel.settings.dailyGoalMinutes
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ============ 护眼知识页 ============
@Composable
private fun TipsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            "护眼小知识",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "${EyeTips.tips.size} 条日常用眼好习惯，一起守护明亮双眼",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        EyeTips.tips.forEachIndexed { index, tip ->
            SoftCard {
                Text(
                    "${tip.emoji} ${tip.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tip.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (index != EyeTips.tips.lastIndex) {
                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ============ 数据报告页（日 / 周 / 月） ============
@Composable
private fun ReportTab(viewModel: EyeCareViewModel, context: Context) {
    var range by remember { mutableStateOf(ReportRange.WEEK) }

    // 每次进入/切换档位时刷新
    // 注意：这里不再把 viewModel.todayWorkSeconds 当缓存键 —— 它每秒都在变，
    // 会让 remember 每秒重算 30/60 天数据。今日数据本身由「今日小结」卡片单独展示。
    var refreshKey by remember { mutableStateOf(0) }
    val stats = remember(range, refreshKey) {
        StatsStore.dailySeries(context, range.days)
    }
    LaunchedEffect(Unit) { refreshKey++ }

    val labels = remember(stats) { stats.map { it.dayKey.substring(5) } } // MM-dd
    val workData = remember(stats) { stats.mapIndexed { i, s -> labels[i] to (s.workSeconds / 60) } }
    val restData = remember(stats) { stats.mapIndexed { i, s -> labels[i] to s.restCount } }

    val totalWork = stats.sumOf { it.workSeconds }
    val totalRest = stats.sumOf { it.restCount }
    val longest = stats.maxOfOrNull { it.longestStreakSeconds } ?: 0
    val rate = StatsStore.achievementRate(context, range.days, viewModel.settings.dailyGoalMinutes)
    val trend = StatsStore.workTrendPercent(context, range.days)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            "数据报告",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "用眼时长、最长连续用眼与达标率，看清自己的用眼习惯",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))

        // 日 / 周 / 月 切换
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportRange.entries.forEach { r ->
                FilterChip(
                    selected = range == r,
                    onClick = {
                        range = r
                        refreshKey++
                    },
                    label = { Text(r.label) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 今日小结（用眼侧）
        TodayWorkSummaryCard(
            workSeconds = viewModel.todayWorkSeconds,
            longestStreakSeconds = viewModel.todayLongestStreak,
            restCount = viewModel.todayCount,
            goalMinutes = viewModel.settings.dailyGoalMinutes
        )

        Spacer(Modifier.height(20.dp))

        // 汇总指标
        SoftCard {
            Text(
                "近 ${range.days} 天汇总",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    value = formatDurationShort(totalWork),
                    label = "用眼总时长",
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    value = formatDurationShort(longest),
                    label = "最长连续",
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    value = "$totalRest",
                    label = "休息次数",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                buildReportAdvice(longest, rate, trend, totalWork),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))

        // 双序列柱状图：用眼（分钟） + 休息（次数）
        SoftCard {
            Text(
                "每日用眼 / 休息对比",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(color = GrassGreen, text = "用眼(分钟)")
                Spacer(Modifier.width(14.dp))
                LegendDot(color = MistBlue, text = "休息(次)")
            }
            Spacer(Modifier.height(16.dp))
            DailyBarChart(
                data = workData,
                secondData = restData
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                stats.forEach { s ->
                    Text(
                        s.dayKey.substring(5),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 依据数据生成一句轻提示 */
private fun buildReportAdvice(
    longestStreakSeconds: Int,
    rate: Float,
    trend: Float,
    totalWorkSeconds: Int
): String {
    if (totalWorkSeconds <= 0) {
        return "这段时间还没有用眼记录，从今天开始记录吧 🌱"
    }
    val parts = mutableListOf<String>()
    val longestMin = longestStreakSeconds / 60
    if (longestMin >= 45) {
        parts.add("单次最长连续用眼 ${longestMin} 分钟，建议缩短单次时长")
    } else if (longestMin > 0) {
        parts.add("单次最长连续用眼 ${longestMin} 分钟，节奏还不错 👍")
    }
    if (rate >= 0f) {
        parts.add("达标率 ${(rate * 100).toInt()}%")
    }
    if (trend >= 0f) {
        val pct = (trend * 100).toInt()
        parts.add(if (pct > 0) "环比上升 $pct%" else "环比下降 ${-pct}%")
    }
    return parts.joinToString(" · ")
}

// ============ 护眼设置页 ============
@Composable
private fun SettingsTab(viewModel: EyeCareViewModel, context: Context) {
    var showDialog by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

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
            "调整计时、提醒与外观",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        val s = viewModel.settings

        SettingSummaryCard(
            title = "计时",
            lines = listOf(
                "用眼提醒间隔：${s.workMinutes} 分钟",
                "休息时长：${s.restSeconds} 秒",
                "每日用眼目标：${s.dailyGoalMinutes} 分钟"
            )
        )
        Spacer(Modifier.height(14.dp))
        SettingSummaryCard(
            title = "提醒",
            lines = listOf(
                "到点自动全屏：${if (s.autoFullScreen) "已开启" else "已关闭"}",
                "免打扰：${
                    if (s.dndEnabled)
                        "${AppSettingsTimeText(s.dndStartMinute)} - ${AppSettingsTimeText(s.dndEndMinute)}"
                    else "未启用"
                }",
                "休息结束提示音：${
                    AppSettings.describeRestEndSound(
                        s.restEndSoundEnabled,
                        s.restEndSoundVibrate,
                        s.restEndSoundSource.label
                    )
                }"
            )
        )
        Spacer(Modifier.height(14.dp))
        SettingSummaryCard(
            title = "久坐",
            lines = listOf(
                "久坐提醒：${if (s.sitReminderEnabled) "已开启" else "已关闭"}",
                "提醒间隔：每 ${s.sitIntervalMinutes} 分钟"
            )
        )
        Spacer(Modifier.height(14.dp))
        SettingSummaryCard(
            title = "外观",
            lines = listOf(
                "自动夜间模式：${if (s.autoNightMode) "已开启" else "已关闭"}"
            )
        )

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

        // v2.3.13：关于 —— 版本号、开源仓库、免责声明
        AboutCard()

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { showDialog = true },
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("修改设置", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showDialog) {
        SettingsDialog(
            settings = viewModel.settings,
            onDismiss = { showDialog = false },
            onSave = { newSettings ->
                viewModel.updateSettings(context, newSettings)
                showDialog = false
            }
        )
    }

    if (showDiagnostics) {
        DiagnosticsPanel(onDismiss = { showDiagnostics = false })
    }
}

private fun AppSettingsTimeText(minute: Int): String =
    com.java.myapplication.data.AppSettings.formatMinuteOfDay(minute)

@Composable
private fun SettingSummaryCard(title: String, lines: List<String>) {
    SoftCard {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * 「关于」区块（v2.3.13 新增）：版本号 + 开源仓库地址（可点击）+ 免责声明入口。
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

private fun requestNotificationPermission(context: Context) {
    if (ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        // 通过 Activity 请求
        (context as? android.app.Activity)?.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
        )
    }
}