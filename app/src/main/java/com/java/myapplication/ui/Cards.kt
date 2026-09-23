package com.java.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.java.myapplication.data.EyeTip
import com.java.myapplication.ui.theme.*

// 柔和卡片容器
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
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