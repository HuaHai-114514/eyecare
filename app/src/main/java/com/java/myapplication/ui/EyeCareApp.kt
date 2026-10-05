package com.java.myapplication.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.launch
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

    // 设置页保存反馈（改一下就存一下，给一条轻量 Snackbar 让人放心）
    val snackbarHostState = remember { SnackbarHostState() }

    // 首次启动引导：只在没看过时弹一次（走完或跳过都会写入完成标记）
    var showOnboarding by remember { mutableStateOf(!OnboardingStore.isDone(context)) }

    // 休息倒计时全屏页
    if (viewModel.phase == RestPhase.RESTING) {
        RestScreen(viewModel, context)
        return
    }

    // 页面切换：用 HorizontalPager 而不是 AnimatedContent。
    //
    // 之前用 AnimatedContent + 位移会掉帧，但病因不是「位移」——
    // 是 AnimatedContent 在切换时把新旧两页同时挂上，两页各自跑完整的
    // measure / layout / draw；位移只是让这件事每帧都可见（连静止的旧页也要重排）。
    // 所以上一版改成纯 alpha 是「把症状和病因一起删了」：不卡了，但也没动效了。
    //
    // Pager 的机制不一样：它是一块横贯三页的连续画布，每帧只做一次布局，
    // 三页的位移全部走各自的 graphicsLayer.translationX（合成层），
    // 不触发 measure / layout / draw —— 这就是「只动 transform」的落地方式。
    // 附带拿到 Apple 说的那几件事：跟手 1:1、可打断、松手时速度交接、边界阻尼。
    val pagerState = rememberPagerState(
        initialPage = currentTab.ordinal,
        pageCount = { Tab.entries.size }
    )

    // 底部导航点击 → 弹簧滚到目标页。dampingRatio 1.0（临界阻尼）是 Apple 的默认值：
    // 不弹跳、干净落位；这是「系统发起」的动作，没有动量需要延续，
    // 所以不该有回弹 —— 回弹只留给用户甩出来的手势。
    val scope = rememberCoroutineScope()
    val goTo: (Tab) -> Unit = { tab ->
        if (pagerState.currentPage != tab.ordinal) {
            scope.launch {
                pagerState.animateScrollToPage(tab.ordinal, animationSpec = SettleSpring)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = currentTab == Tab.TIMER,
                    onClick = { goTo(Tab.TIMER) },
                    icon = { Icon(EyeIcons.Timer, contentDescription = "护眼计时") },
                    label = { Text("护眼计时") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.REPORT,
                    onClick = { goTo(Tab.REPORT) },
                    icon = { Icon(EyeIcons.Report, contentDescription = "数据报告") },
                    label = { Text("数据报告") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.SETTINGS,
                    onClick = { goTo(Tab.SETTINGS) },
                    icon = { Icon(EyeIcons.Settings, contentDescription = "护眼设置") },
                    label = { Text("护眼设置") }
                )
            }
        }
    ) { innerPadding ->
        // 手势滑动时把当前页同步回导航栏，药丸 indicator 才会跟着手指走
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { settled ->
                currentTab = Tab.entries[settled]
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            HorizontalPager(
                state = pagerState,
                // 两侧各预组合 2 页（总共只有 3 页，等于全部页面在首帧后就都在场）。
                //
                // 为什么是 2 而不是 1：这个参数决定「当前页两侧各有多少页会被提前测量」，
                // 而**首次测量一个页面 = 首次组合它整棵子树**。取 1 时，冷启动停在计时页，
                // 报告页会在启动时被组合，但**设置页（距离 2）不会** ——
                // 于是第一次切到设置页的那几帧要现做「组合 750 行 + 首次 measure + 首次 draw」，
                // 全砸在弹簧动画里，就是「第一次切明显掉帧、以后正常」。
                // 取 2 把这份一次性开销提前到启动首帧之后、用户还没点之前。
                //
                // 代价：三页常驻内存（都是轻量 Compose 树，无大图），
                // 换来「每次切换都一样顺」——这个交换对只有 3 页的底部导航是划算的。
                beyondViewportPageCount = 2,
                modifier = Modifier.fillMaxSize(),
                key = { it }
            ) { page ->
                when (Tab.entries[page]) {
                    Tab.TIMER -> TimerTab(viewModel, context)
                    Tab.REPORT -> ReportTab(viewModel, context)
                    Tab.SETTINGS -> SettingsTab(viewModel, context, snackbarHostState)
                }
            }
        }
    }

    // 到点提醒确认弹窗：点了「开始休息」才开始休息计时
    if (viewModel.phase == RestPhase.AWAITING) {
        // 弹窗出现时轻振一下：手上拿着手机、音量静音时也能察觉
        val haptic = LocalHapticFeedback.current
        LaunchedEffect(Unit) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
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
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部标题（设置入口统一放在底部导航，避免同一功能两处入口）
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

        Spacer(Modifier.height(20.dp))

        // 免打扰进行中提示（有则淡入显示）
        AnimatedVisibility(
            visible = viewModel.inDndWindow,
            enter = fadeIn(tween(MotionDurations.ENTER_MS, easing = EaseOutStrong)),
            exit = fadeOut(tween(MotionDurations.EXIT_MS))
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

    // 缓存键：今日累计是「统计有没有变」的可观测信号 —— 每记一笔今日数据就变。
    // 换用 Pager 之后这一页不再被销毁重建（±1 页常驻），所以必须有这么个键
    // 才知道该重新读一次；否则回到报告页永远看到进页面那一眼的旧快照。
    //
    // 按**分钟**取整而不是直接用秒：这一页所有读数都是以分钟展示的
    // （`s.workSeconds / 60`），秒级变化在这一页上根本看不出来，
    // 却会让 remember 每秒作废一次 —— 而每次作废要重跑
    // dailySeries + achievementRate + workTrendPercent 三遍整表计算。
    // 取整到分钟后，同一分钟内数字完全一致，计算量直接降到 1/60。
    val statsKey = (viewModel.todayWorkSeconds / 60) to viewModel.todayCount
    val goalMinutes = viewModel.settings.dailyGoalMinutes
    val stats = remember(range, statsKey) {
        StatsStore.dailySeries(context, range.days)
    }

    // 横轴标签：用短格式「M/d」（如 9/1），比 MM-dd 更省宽度；
    // 30 天档位下 30 个标签会挤成一片、数字被压坏（图 1 的乱码就是这么来的），
    // 因此额外做「抽稀」：只保留少量关键刻度，其余留空，见下方 axisLabels。
    val labels = remember(stats) { stats.map { shortDayLabel(it.dayKey) } } // M/d
    val workData = remember(stats) { stats.mapIndexed { i, s -> labels[i] to (s.workSeconds / 60) } }
    val restData = remember(stats) { stats.mapIndexed { i, s -> labels[i] to s.restCount } }

    // 汇总数字跟 stats 同生命周期：跟它一起 remember，避免每秒跟着重组重算。
    val totalWork = remember(stats) { stats.sumOf { it.workSeconds } }
    val totalRest = remember(stats) { stats.sumOf { it.restCount } }
    val longest = remember(stats) { stats.maxOfOrNull { it.longestStreakSeconds } ?: 0 }
    val rate = remember(range, goalMinutes, statsKey) {
        StatsStore.achievementRate(context, range.days, goalMinutes)
    }
    val trend = remember(range, statsKey) { StatsStore.workTrendPercent(context, range.days) }

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
                    onClick = { range = r },
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