package com.java.myapplication.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.java.myapplication.data.ScreenTimeStore
import com.java.myapplication.data.StatsStore
import com.java.myapplication.notify.RestNotifier
import com.java.myapplication.ui.components.DailyBarChart
import com.java.myapplication.ui.components.EyeIcons
import com.java.myapplication.ui.components.EyeProgressRing
import com.java.myapplication.ui.theme.*
import kotlinx.coroutines.delay
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
        // 先落高亮再起动画：settledPage 要等弹簧完全收敛才更新，
        // 只靠它驱动的话，点了导航得等整段滑动结束，图标和药丸才跟上。
        // 点击是明确的用户意图，应该立即反馈；settledPage 监听继续负责手势滑动。
        currentTab = tab
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
    // 系统亮屏时长（v2.6.16）：统计页唯一的数据源。
    // 亮屏时长会随时间自然增长，因此每分钟推进一次 tick 作为缓存键 ——
    // 这一页只以分钟展示，秒级变化看不出来，却会让 remember 每秒重查一次
    // 系统事件表（queryEvents 要遍历当天全部亮灭屏事件，代价不小）。
    var timerHasUsageAccess by remember { mutableStateOf(ScreenTimeStore.hasPermission(context)) }
    var timerMinuteTick by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            timerMinuteTick = System.currentTimeMillis() / 60_000L
        }
    }
    val timerScreenOnSeconds = remember(timerHasUsageAccess, timerMinuteTick) {
        if (timerHasUsageAccess) ScreenTimeStore.todayScreenOnSeconds(context) else null
    }
    val timerRequestUsageAccess: (() -> Unit)? = {
        runCatching {
            context.startActivity(
                ScreenTimeStore.permissionIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        // 回到前台时由下面的生命周期钩子重查授权状态
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部标题（设置入口统一放在底部导航，避免同一功能两处入口）
        // 这一列整体是居中的（进度环、按钮需要），但标题单独包一层占满宽度，
        // 走默认的 Start 对齐 —— 三个页面的标题就都在同一条左基准线上。
        Column(modifier = Modifier.fillMaxWidth()) {
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

        Spacer(Modifier.height(20.dp))

        // 免打扰进行中提示（有则淡入显示）
        AnimatedVisibility(
            visible = viewModel.inDndWindow,
            enter = fadeIn(tween(MotionDurations.ENTER_MS, easing = EaseOutStrong)),
            exit = fadeOut(tween(MotionDurations.EXIT_MS))
        ) {
            Column {
                SoftCard {
                    // v2.6.20：原标题写作「🌙 免打扰时段进行中」。emoji 走系统彩色字体，
                    // 与 titleSmall 的基线、字重都不一致，是自检页那类「不齐」的同源问题。
                    // 这里不另加图标，改用左侧一道竖向色条标记「静音中」——比再塞一个
                    // 图形更克制，也避免每张卡片都顶一个图标导致的重复。
                    Row {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(38.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.tertiary)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "免打扰时段进行中",
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
                    }
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
            Text("现在休息一下", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))

        // 护眼知识轮播（v2.4.0：取代原「护眼知识」导航页）
        TipCarousel()

        Spacer(Modifier.height(24.dp))

        // 今日用眼小结（实用向核心）
        // v2.6.16：主指标已改为系统亮屏时长，与报告页同源同口径。
        TodayWorkSummaryCard(
            screenOnSeconds = timerScreenOnSeconds,
            restCount = viewModel.todayCount,
            restSeconds = viewModel.todaySeconds,
            goalMinutes = viewModel.settings.dailyGoalMinutes,
            onRequestScreenTime = if (timerHasUsageAccess) null else timerRequestUsageAccess
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ============ 数据报告页（日 / 周 / 月） ============
@Composable
private fun ReportTab(viewModel: EyeCareViewModel, context: Context) {
    var range by remember { mutableStateOf(ReportRange.WEEK) }

    // 缓存键：App 自统计的今日累计已在 v2.6.16 废止（数字不可信、虚高），
    // 报告页的所有口径都改由系统亮屏时长驱动，故缓存键也换成「当前分钟」。
    //   1. 亮屏时长本身随时间增长，用当前分钟做键即可拿到新鲜读数；
    //   2. 休息次数仍是自统计的（这条链路没有虚高问题），一并参与做键。
    // 按**分钟**取整而不是用秒：这一页所有读数都以分钟展示，秒级变化看不出来，
    // 却会让 remember 每秒作废一次；而每次作废要重跑 dailySeries +
    // dailyScreenOnSeconds 两遍整表计算。取整到分钟后同一分钟内数字完全一致，
    // 计算量直接降到 1/60。
    var minuteTick by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    val statsKey = minuteTick to viewModel.todayCount
    val goalMinutes = viewModel.settings.dailyGoalMinutes

    // 系统亮屏时长：同样是「统计有没有变」的可观测键，跟着 statsKey 一起失效。
    // 没有「使用情况访问权限」时为 null，卡片会显示一个引导授权的入口。
    var hasUsageAccess by remember { mutableStateOf(ScreenTimeStore.hasPermission(context)) }
    val screenOnSeconds = remember(statsKey, hasUsageAccess) {
        if (hasUsageAccess) ScreenTimeStore.todayScreenOnSeconds(context) else null
    }
    // 每分钟推进一次 minuteTick，让上面的缓存键在亮屏时长增长后自然失效。
    // 只在页面可见时计时（这一页由 Pager 常驻，不依赖组合生命周期做可见性判断，
    // 故用最简单的 while 循环 + delay；成本是一次 60 秒的协程唤醒）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            minuteTick = System.currentTimeMillis() / 60_000L
        }
    }

    // 休息次数仍走自统计（这条链路由休息完成事件直接触发，没有虚高问题）。
    val stats = remember(range, statsKey) {
        StatsStore.dailySeries(context, range.days)
    }

    // v2.6.16：用眼时长的逐日数据改由**系统亮屏时长**提供，与今日卡片同口径。
    // 未授权时整体为 null，图表显示空态并给出授权入口。
    val screenOnDaily = remember(range, statsKey, hasUsageAccess) {
        if (hasUsageAccess) {
            ScreenTimeStore.dailyScreenOnSeconds(context, range.days)
        } else {
            List(range.days) { null }
        }
    }

    // 横轴标签：用短格式「M/d」（如 9/1），比 MM-dd 更省宽度；
    // 30 天档位下 30 个标签会挤成一片、数字被压坏（图 1 的乱码就是这么来的），
    // 因此额外做「抽稀」：只保留少量关键刻度，其余留空，见下方 axisLabels。
    val labels = remember(stats) { stats.map { shortDayLabel(it.dayKey) } } // M/d
    val workData = remember(labels, screenOnDaily) {
        labels.mapIndexed { i, l -> l to ((screenOnDaily.getOrNull(i)?.toInt() ?: 0) / 60) }
    }
    // v2.7.1：休息侧由「次数」改为「时长（分钟）」。原来两张图共用一根 Y 轴，
    // 却拿「分钟」和「次」相比：数值差两个数量级，柱子按同一 maxValue 缩放，
    // 休息柱永远只有百分之几高，且两根柱子的高度本就不具可比性。
    // 改用 DailyStat.restSeconds（addRest 逐次累加的**真实**休息秒数），
    // 与亮屏同为时长口径，两张图上下排布时量纲一致。
    val restData = remember(stats, labels) {
        stats.mapIndexed { i, s -> labels[i] to (s.restSeconds / 60) }
    }

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
            screenOnSeconds = screenOnSeconds,
            restCount = viewModel.todayCount,
            restSeconds = viewModel.todaySeconds,
            goalMinutes = viewModel.settings.dailyGoalMinutes,
            onRequestScreenTime = if (hasUsageAccess) null else {
                {
                    // 跳到系统「使用情况访问」页；回到前台时重查一次授权状态
                    runCatching {
                        context.startActivity(
                            ScreenTimeStore.permissionIntent()
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        )

        Spacer(Modifier.height(20.dp))

        // v2.7.1：原「亮屏 / 休息对比」把两种**不同量纲**画进同一坐标系 ——
        // 左轴是分钟、右轴是次数，柱高却按同一个 maxValue 缩放，既不可比
        // （「亮屏柱比休息柱高」没有含义），休息柱也永远矮得看不见。
        // 现拆成两张独立图表，各自一根 Y 轴；休息侧同时由「次数」改为「时长」，
        // 使两张图量纲一致，上下排布时可横向参照。
        // v2.7.1：日档位不画柱状图。柱状图的价值在**多天横向比较**，只有一天
        // 数据时，一根柱子无论多宽都别扭（曾出现 145dp 宽、圆角 72dp 的胶囊），
        // 且这张图与上面「今日用眼小结」的数字完全重复。日档位的信息已由那张卡
        // 承载（亮屏时长 / 休息次数 / 休息时长三项 + 目标进度），故此处整体隐藏。
        if (range != ReportRange.DAY) {
            ChartCard(
                title = "每日亮屏时长",
                legendColor = GrassGreen,
                legendText = "亮屏(分钟)",
                data = workData,
                stats = stats
            )

            Spacer(Modifier.height(20.dp))

            ChartCard(
                title = "每日休息时长",
                legendColor = MistBlue,
                legendText = "休息(分钟)",
                data = restData,
                stats = stats,
                valueLabel = "休息",
                barColor = MistBlue,
                emptyColor = MistBlue
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 单序列按日图表卡片：标题 + 图例 + 柱状图 + 横轴。
 *
 * v2.7.1 抽出。原先是「亮屏 / 休息」共用一张双序列卡，但两者量纲不同
 * （分钟 vs 次数），共用一根 Y 轴既不可比、柱子高度也失真。拆成两张单序列卡，
 * 每张各自按自己的最大值缩放，高度才真实。
 *
 * @param legendColor 图例圆点颜色，同时作为柱子颜色
 * @param data 逐日数据（标签 → 数值），单位由调用方决定，默认分钟
 */
@Composable
private fun ChartCard(
    title: String,
    legendColor: androidx.compose.ui.graphics.Color,
    legendText: String,
    data: List<Pair<String, Int>>,
    stats: List<com.java.myapplication.data.DailyStat>,
    barColor: androidx.compose.ui.graphics.Color = GrassGreen,
    emptyColor: androidx.compose.ui.graphics.Color = GrassGreen,
    valueLabel: String = "亮屏"
) {
    SoftCard {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(color = legendColor, text = legendText)
        }
        Spacer(Modifier.height(16.dp))
        DailyBarChart(
            data = data,
            barColor = barColor,
            emptyColor = emptyColor,
            primaryLabel = valueLabel
        )
        Spacer(Modifier.height(6.dp))
        // 横轴：不再「每个日期一个等宽槽位」（30 天时每格仅 ~11dp，
        // "9/1" 会被截断成 "9"，就是之前看到的「只有月份没日期」）。
        // 改为独立刻度行：按档位固定少量刻度（最多 6 个），每个刻度用
        // 非等分排布，保证每个标签都有足够宽度完整显示。
        DateAxis(stats = stats)
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