package com.java.myapplication.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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

// 健康知识卡片
@Composable
fun TipCard(tip: EyeTip) {
    SoftCard {
        Text(
            "💡 护眼小知识",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
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
}

/**
 * 护眼知识轮播（v2.4.0）。
 *
 * 取代原先独立的「护眼知识」导航页：把整页题库搬进主页一张卡片，
 * 每 [autoIntervalMs] 自动切一条，用户手动划过之后暂停 [resumeDelayMs] 再恢复自动播放。
 *
 * 实现只用 Compose 自带动画/手势 API（AnimatedContent + detectHorizontalDragGestures），
 * 不引入 Pager 等新依赖，保持轻量。
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

    val tip = tips[index]

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

        // 手势区：左右拖拽切换（拖动超过阈值即翻页，并记录手动时间以暂停自动播放）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(tips.size) {
                    var dragAcc = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragAcc <= -60f) {
                                index = (index + 1) % tips.size
                                lastManualAt = System.currentTimeMillis()
                                ticker++
                            } else if (dragAcc >= 60f) {
                                index = (index - 1 + tips.size) % tips.size
                                lastManualAt = System.currentTimeMillis()
                                ticker++
                            }
                            dragAcc = 0f
                        },
                        onHorizontalDrag = { _, delta -> dragAcc += delta }
                    )
                }
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = slideInHorizontally(
                        animationSpec = tween(320)
                    ) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(320))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(320)
                    ) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(320))
                    enter togetherWith exit
                },
                label = "tip-carousel"
            ) { i ->
                val t = tips[i]
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

        // 页码指示器
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(tips.size) { i ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(if (i == index) 16.dp else 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (i == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        )
                )
            }
        }
    }
}

// 今日小结卡片
@Composable
fun TodaySummaryCard(count: Int, seconds: Int) {
    SoftCard {
        Text(
            "📋 今日小结",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatItem(
                value = "$count",
                label = "休息次数",
                modifier = Modifier.weight(1f)
            )
            StatItem(
                value = "${seconds / 60}",
                label = "累计休息(分)",
                modifier = Modifier.weight(1f)
            )
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