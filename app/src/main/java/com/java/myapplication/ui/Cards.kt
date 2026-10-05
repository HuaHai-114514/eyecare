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
                "💡 护眼小知识",
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

        // 页码指示器（宽度平滑过渡，不再跳变）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(tips.size) { i ->
                val active = i == index
                val dotScale by animateFloatAsState(
                    targetValue = if (active) 1f else 0.375f,
                    animationSpec = tween(MotionDurations.ENTER_MS, easing = EaseOutStrong),
                    label = "dotScale"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(16.dp)
                        // 固定 16dp + scaleX(0.375→1)，视觉上仍是 6dp→16dp。
                        // 用 transform 而不是动 width：动 width 走布局阶段（每帧 relayout + repaint）。
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
 * 今日用眼小结（实用向核心）：用眼总时长 / 最长连续用眼 / 休息次数 + 目标进度条。
 *
 * @param workSeconds 今日用眼总秒数
 * @param longestStreakSeconds 今日最长连续用眼秒数
 * @param restCount 今日休息次数
 * @param goalMinutes 每日用眼目标（分钟）
 */
@Composable
fun TodayWorkSummaryCard(
    workSeconds: Int,
    longestStreakSeconds: Int,
    restCount: Int,
    goalMinutes: Int
) {
    val goalSeconds = (goalMinutes * 60).coerceAtLeast(1)
    val progress = (workSeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val overGoal = workSeconds > goalSeconds

    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📋 今日用眼小结",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (overGoal) "⚠️ 已超目标" else "✅ 未超标",
                style = MaterialTheme.typography.labelSmall,
                color = if (overGoal) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            StatItem(
                value = formatDurationShort(workSeconds),
                label = "用眼总时长",
                modifier = Modifier.weight(1f)
            )
            StatItem(
                value = formatDurationShort(longestStreakSeconds),
                label = "最长连续用眼",
                modifier = Modifier.weight(1f)
            )
            StatItem(
                value = "$restCount",
                label = "休息次数",
                modifier = Modifier.weight(1f)
            )
        }

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