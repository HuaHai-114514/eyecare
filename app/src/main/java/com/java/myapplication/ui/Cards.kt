package com.java.myapplication.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.java.myapplication.ui.components.EyeIcons
import com.java.myapplication.data.EyeTip
import com.java.myapplication.data.EyeTips
import com.java.myapplication.ui.theme.*
import kotlinx.coroutines.delay

// 柔和卡片容器
// v2.4：加一层极淡的描边，让白卡从米白/夜色底里"浮"起来。
// 之前只有背景色差（surfaceVariant vs background），层次几乎为零；
// 描边用 outline 的低透明度版本，日夜两套主题都能自然过渡，不喧宾夺主。
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                shape = shape
            )
            .padding(20.dp),
        content = content
    )
}

// 提醒开关卡片
@Composable
fun EyeCareSwitchCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "用眼提醒",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (enabled) "已开启，按时守护你的双眼" else "已关闭，暂时不提醒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/**
 * 护眼知识轮播（v2.4.0）。
 *
 * 取代原先独立的「护眼知识」导航页：把整页题库搬进主页一张卡片，
 * 每 [autoIntervalMs] 自动切一条，用户手动划过之后暂停 [resumeDelayMs] 再恢复自动播放。
 *
 * 实现只用 Compose 自带动画/手势 API（AnimatedContent + draggable），
 * 不引入 Pager 等新依赖，保持轻量。
 *
 * v2.4.4 交互打磨：
 * - 拖拽**跟手**（拖动时内容随手平移，而不是松手才动），带一点阻尼手感；
 * - 松手未过阈值会**弹回**原位，过了阈值才翻页；
 * - 页码指示器用固定 16dp + `graphicsLayer.scaleX` 过渡（不动 width，避免布局动画）。
 */
@Composable
fun TipCarousel(
    tips: List<EyeTip> = EyeTips.tips,
    autoIntervalMs: Long = 6000L,
    resumeDelayMs: Long = 15_000L
) {
    if (tips.isEmpty()) return

    var index by remember { mutableIntStateOf(0) }
    // 记下用户最后一次手动滑动的时间戳：在该时间之后 resumeDelayMs 才恢复自动播放
    var lastManualAt by remember { mutableLongStateOf(0L) }
    // 每次自动/手动切换都自增，用于重启下面的定时协程（作为 LaunchedEffect 的 key）
    var ticker by remember { mutableIntStateOf(0) }

    // 跟手拖拽的位移（px）。拖动中只是普通状态写入（每个 pointer 事件不起协程），
    // 抬手后才用 animate(spring) 归零。
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 本次翻页是否由手拖触发：手拖时拖拽本身已给了方向感，翻页只做淡入淡出；
    // 自动播放没有拖拽兜底，才用横向滑入来交代「新的一屏从哪来」。
    var userDriven by remember { mutableStateOf(false) }
    // 一次拖拽开始时的页码，用来判断「拖完之后 index 有没有被自动播放改掉」
    val dragStartIndex = remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    val dragBoundPx = with(density) { 96.dp.toPx() }
    val distanceThresholdPx = with(density) { 48.dp.toPx() }
    // 甩动判据（px/s）：轻扫就该生效，不必硬拖过位移阈值
    val flingVelocityPx = with(density) { 300.dp.toPx() }

    // 自动播放：每 autoIntervalMs 推进一步；手动滑动后暂停 resumeDelayMs
    LaunchedEffect(ticker, tips.size) {
        while (true) {
            delay(autoIntervalMs)
            val sinceManual = System.currentTimeMillis() - lastManualAt
            if (sinceManual < resumeDelayMs) {
                // 仍在暂停窗口内：缩短等待，到点后继续
                delay(resumeDelayMs - sinceManual)
                continue
            }
            index = (index + 1) % tips.size
            ticker++
        }
    }

    // 翻完之后复位：transitionSpec 只在 targetState 变化那一刻求值，
    // 这里在组合完成后再清标记，不会影响已经开始的这次翻页。
    LaunchedEffect(index) { userDriven = false }

    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "护眼小知识",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${index + 1}/${tips.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))

        // 手势区：左右拖拽切换。拖动时内容跟手平移；松手后按「位移 or 甩动速度」判翻页，
        // 不翻则用弹簧弹回原位（手势可被中途反向，弹簧能保留速度，固定时长会从零重启）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // graphicsLayer 而非 offset：位移留在合成层（GPU），不触发每帧重新布局
                .graphicsLayer { translationX = dragOffset }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 0.9 系数 + 限幅：拖到头有轻微阻尼感（越拖越沉），不是硬墙
                        dragOffset = (dragOffset + delta * 0.9f).coerceIn(-dragBoundPx, dragBoundPx)
                    },
                    onDragStarted = { dragStartIndex.intValue = index },
                    onDragStopped = { velocity ->
                        val flipForward = dragOffset <= -distanceThresholdPx || velocity <= -flingVelocityPx
                        val flipBackward = dragOffset >= distanceThresholdPx || velocity >= flingVelocityPx
                        if ((flipForward || flipBackward) && dragStartIndex.intValue == index) {
                            // 标记「本次翻页是手拖出来的」：拖拽本身已经提供了方向感，
                            // 翻页就只做淡入淡出 + 弹回原位，不再叠加一次横向滑入
                            // —— 那是两条同轴位移同时播放，视觉上会「抖一下」。
                            userDriven = true
                            index = if (flipForward) {
                                (index + 1) % tips.size
                            } else {
                                (index - 1 + tips.size) % tips.size
                            }
                            lastManualAt = System.currentTimeMillis()
                            ticker++
                        }
                        animate(
                            initialValue = dragOffset,
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { value, _ -> dragOffset = value }
                    }
                )
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    if (userDriven) {
                        // 手拖翻页：不叠横向滑入（会和回弹 spring 抢同一根轴，看起来抖）
                        fadeIn(tween(MotionDurations.SMALL_MS, easing = EaseOutStrong)) togetherWith
                            fadeOut(tween(MotionDurations.EXIT_MS))
                    } else {
                        val forward = targetState > initialState
                        val enter = slideInHorizontally(
                            animationSpec = tween(MotionDurations.ENTER_MS, easing = EaseOutStrong)
                        ) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(MotionDurations.ENTER_MS, easing = EaseOutStrong))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(MotionDurations.ENTER_MS, easing = EaseOutStrong)
                        ) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(MotionDurations.EXIT_MS))
                        enter togetherWith exit
                    }
                },
                label = "tip-carousel"
            ) { i ->
                val t = tips[i % tips.size]
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${t.emoji} ${t.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 页码指示器。
        //
        // v2.6.17：原实现固定每点宽 16dp + 左右各 3dp padding = 22dp，19 条科普
        // 就是 418dp —— 超出任何手机屏宽（常见 360~412dp），末尾几个点被裁掉。
        // 根因是激活态用 `graphicsLayer { scaleX = ... }` 缩放：transform 只影响
        // 绘制，**不参与布局测量**，所以非激活点看着只有 6dp、实际仍占满 22dp。
        //
        // 现改为「按可用宽度自适应」：先量出容器宽度，再算每点能分到多少，
        // 点宽/间距按比例收缩并设下限，保证整排始终完整落在卡片内。
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val count = tips.size
            // 每点理想占位（点宽 + 两侧间距）
            val idealSlot = 22.dp
            val minDot = 3.dp
            val minGap = 1.dp
            val available = maxWidth
            val slot = if (count > 0) {
                (available / count).coerceAtMost(idealSlot)
            } else {
                idealSlot
            }
            // 由可用槽位反推点宽与间距：先保间距，剩下的给点宽；点宽不低于 minDot。
            // 槽位 = 点宽 + 两侧间距，而激活态要放大到 2.4 倍，所以点宽得留出余量：
            // 直接按 (slot - 2*gap) / 2.4 反推，保证放大后仍不越过相邻点。
            val gap = 2.dp.coerceAtMost((slot / 8f).coerceAtLeast(minGap))
            val dotWidth = ((slot - gap * 2) / 2.4f).coerceAtLeast(minDot)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(count) { i ->
                    val active = i == index
                    val dotScale by animateFloatAsState(
                        targetValue = if (active) 2.4f else 1f,
                        animationSpec = tween(MotionDurations.ENTER_MS, easing = EaseOutStrong),
                        label = "dotScale"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = gap)
                            .height(6.dp)
                            .width(dotWidth)
                            // 基础宽 = dotWidth（已按可用宽度收缩），再乘 1→2.4 的
                            // 绘制缩放表示激活。槽位留了 2.4 倍余量，放大不会越界。
                            .graphicsLayer { scaleX = dotScale }
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== v2.3：用眼侧小结 ====================

/**
 * 今日用眼小结：屏幕亮屏时长 / 休息次数 + 目标进度条。
 *
 * v2.6.16：主指标由 App 自统计的「用眼总时长」改为**系统亮屏时长**。
 * 自统计那套记账（累计 + 水位线 + 段序号）在「息屏广播丢失、进程被回收」时
 * 会把整段息屏时间算成用眼（实测系统亮屏 3 分钟 / 自统计 33 分钟），
 * 屡修不净，故整体废止。「最长连续用眼」在亮屏口径下没有对应物，一并移除。
 *
 * @param screenOnSeconds 今日系统亮屏秒数；null = 未授权/拿不到，展示占位
 * @param restCount 今日休息次数
 * @param goalMinutes 每日用眼目标（分钟）
 * @param onRequestScreenTime 未授权时点「去开启」的回调
 */
@Composable
fun TodayWorkSummaryCard(
    screenOnSeconds: Long?,
    restCount: Int,
    goalMinutes: Int,
    onRequestScreenTime: (() -> Unit)? = null
) {
    val goalSeconds = (goalMinutes * 60).coerceAtLeast(1)
    val workSeconds = screenOnSeconds?.toInt() ?: 0
    val progress = (workSeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val overGoal = screenOnSeconds != null && workSeconds > goalSeconds

    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "今日用眼小结",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (screenOnSeconds != null) {
                // v2.6.20：原为 emoji「⚠️ 已超目标 / ✅ 未超标」。emoji 走系统彩色
                // 字体，与相邻 labelSmall 文字基线对不齐；改用自绘图标 + 同色文字，
                // 图标只承担状态识别，语义仍由文字承载。
                val warnColor = if (overGoal) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (overGoal) EyeIcons.AlertCircle else EyeIcons.CheckCircle,
                        contentDescription = null,
                        tint = warnColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (overGoal) "已超目标" else "未超标",
                        style = MaterialTheme.typography.labelSmall,
                        color = warnColor
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            StatItem(
                // 未授权时主数字位留占位，不留一个可能是错的自统计值
                value = screenOnSeconds?.let { formatDurationShort(it.toInt()) } ?: "--",
                label = "亮屏时长",
                modifier = Modifier.weight(1f)
            )
            StatItem(
                value = "$restCount",
                label = "休息次数",
                modifier = Modifier.weight(1f)
            )
        }

        if (screenOnSeconds != null) {
            Spacer(Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = if (overGoal) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "已完成目标的 ${(progress * 100).toInt()}% · 目标 $goalMinutes 分钟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))

            Text(
                "数据来自系统的屏幕亮屏记录，包含亮着屏但没在用眼的时间。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (onRequestScreenTime != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "开启「使用情况访问」后即可显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onRequestScreenTime,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("去开启", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 把秒数格式化为紧凑可读时长：<1h 显示「x分」，≥1h 显示「x小时y分」 */
fun formatDurationShort(seconds: Int): String {
    if (seconds <= 0) return "0分"
    val totalMinutes = seconds / 60
    if (totalMinutes < 60) return "${totalMinutes}分"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (m == 0) "${h}小时" else "${h}小时${m}分"
}