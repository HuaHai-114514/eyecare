package com.java.myapplication.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.OnboardingStore
import com.java.myapplication.data.StatsStore
import com.java.myapplication.notify.RestNotifier
import com.java.myapplication.ui.components.DailyBarChart
import com.java.myapplication.ui.components.EyeIcons
import com.java.myapplication.ui.components.EyeProgressRing
import com.java.myapplication.ui.theme.*
import kotlin.math.roundToInt

private enum class Tab { TIMER, REPORT, SETTINGS }

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
                    icon = { Icon(EyeIcons.Timer, contentDescription = "护眼计时") },
                    label = { Text("护眼计时") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.REPORT,
                    onClick = { currentTab = Tab.REPORT },
                    icon = { Icon(EyeIcons.Report, contentDescription = "数据报告") },
                    label = { Text("数据报告") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.SETTINGS,
                    onClick = { currentTab = Tab.SETTINGS },
                    icon = { Icon(EyeIcons.Settings, contentDescription = "护眼设置") },
                    label = { Text("护眼设置") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 页面切换淡入淡出，避免生硬跳切
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "tab-switch"
            ) { tab ->
                when (tab) {
                    Tab.TIMER -> TimerTab(viewModel, context, onOpenSettings = { currentTab = Tab.SETTINGS })
                    Tab.REPORT -> ReportTab(viewModel, context)
                    Tab.SETTINGS -> SettingsTab(viewModel, context)
                }
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
                Icon(
                    EyeIcons.Settings,
                    contentDescription = "打开设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 免打扰进行中提示（有则淡入显示）
        AnimatedVisibility(
            visible = viewModel.inDndWindow,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            Column {
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

        // 护眼知识轮播（v2.4.0：取代原「护眼知识」导航页）
        TipCarousel()

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

    // 横轴标签：用短格式「M/d」（如 9/1），比 MM-dd 更省宽度；
    // 30 天档位下 30 个标签会挤成一片、数字被压坏（图 1 的乱码就是这么来的），
    // 因此额外做「抽稀」：只保留少量关键刻度，其余留空，见下方 axisLabels。
    val labels = remember(stats) { stats.map { shortDayLabel(it.dayKey) } } // M/d
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
            Spacer(Modifier.height(6.dp))
            // 横轴：不再「每个日期一个等宽槽位」（30 天时每格仅 ~11dp，
            // "9/1" 会被截断成 "9"，就是之前看到的「只有月份没日期」）。
            // 改为独立刻度行：按档位固定少量刻度（最多 6 个），每个刻度用
            // 非等分排布，保证每个标签都有足够宽度完整显示。
            DateAxis(stats = stats)
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

/**
 * 报告页横轴刻度行。
 *
 * 设计要点：横轴刻度**不按数据点数等分**，而是先按档位算出「最多几个刻度」，
 * 再把整行等分成那么多个槽位。这样每个刻度都有约「屏宽 / 刻度数」的宽度，
 * （30 天档位只放 6 个刻度，每个宽约 60dp），"9/1" 之类的短标签必然能完整显示，
 * 彻底避开「N 个日期挤一行、每格十几 dp、数字被截断」的老毛病。
 *
 * 标签取「该刻度所代表日期」的短格式（M/d），刻度在时间轴上均匀分布，
 * 首尾分别落在最早/最新一天，方便一眼看出区间。
 */
@Composable
private fun DateAxis(stats: List<com.java.myapplication.data.DailyStat>) {
    if (stats.isEmpty()) return
    // 刻度数量：日/周每天一个，两周 4 个，月 6 个（足够看清区间又不拥挤）
    val tickCount = when {
        stats.size <= 7 -> stats.size
        stats.size <= 14 -> 4
        else -> 6
    }
    // 均匀取索引：在 [0, size-1] 上取 tickCount 个等距点（含首尾）
    val lastIndex = stats.lastIndex
    val ticks: List<Int> = if (tickCount <= 1) {
        listOf(0)
    } else {
        (0 until tickCount).map { i ->
            (lastIndex.toFloat() * i / (tickCount - 1)).roundToInt().coerceIn(0, lastIndex)
        }.distinct()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ticks.forEach { index ->
            Text(
                text = shortDayLabel(stats[index].dayKey),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

/**
 * 把日期键 yyyy-MM-dd 转成短横轴标签「M/d」（如 2025-09-01 -> 9/1）。
 *
 * 为什么不再用 MM-dd：周/月档位下标签数量多、单标签宽度只有十几个 dp，
 * 两位月 + 连字符 + 两位日在窄槽里会被逐字压缩（真机上表现为一串 0/9 的乱码）。
 * M/d 少一个字符且去掉了补零，更容易在窄槽里完整显示。
 *
 * 解析失败时回退原始字符串，保证任何脏数据都不会导致崩溃。
 */
private fun shortDayLabel(dayKey: String): String {
    // 期望格式 yyyy-MM-dd
    if (dayKey.length < 10) return dayKey
    return try {
        val month = dayKey.substring(5, 7).trimStart('0').ifEmpty { "0" }
        val day = dayKey.substring(8, 10).trimStart('0').ifEmpty { "0" }
        "$month/$day"
    } catch (_: Exception) {
        dayKey
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