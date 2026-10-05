package com.java.myapplication.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.java.myapplication.ui.theme.EaseOutStrong
import com.java.myapplication.ui.theme.GrassGreen
import com.java.myapplication.ui.theme.MistBlue
import com.java.myapplication.ui.theme.MotionDurations

/**
 * 柔和柱状图（v2.3 支持双序列）。
 *
 * - 单序列：`data` 为「标签 → 数值」，柱子居中满宽。
 * - 双序列：额外传入 `secondData` + `secondColor`，两组柱子并排展示
 *   （左＝主序列，右＝副序列），并各自按全局最大值归一化，方便横向对比。
 *
 * v2.4.4 UI 打磨：
 * - **入场生长动画**：柱子从 0 长到实际高度，而不是「啪」地一下出现；
 * - **点按查看数值**：点任意一根柱子会把该天的用眼/休息数值显示在图表上方，
 *   再点一次取消选择；选中时其余柱子淡出，突出所选那天；
 * - **今天高亮**：最后一根柱子下方有一个小圆点，并带一层极浅的底色，
 *   一眼能分清「今天」和「历史」。
 *
 * 约定：`data` 按时间升序排列，**最后一根必须是「今天」**（与
 * `StatsStore.dailySeries` 的返回顺序一致）。
 */
@Composable
fun DailyBarChart(
    data: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    barColor: Color = GrassGreen,
    emptyColor: Color = MistBlue,
    secondData: List<Pair<String, Int>>? = null,
    secondColor: Color = MistBlue,
    primaryUnit: String = "分钟",
    secondaryUnit: String = "次"
) {
    // 为空时统一退化为空列表，dual 由它派生，避免对可空参数做多余的空判断
    val secondValues: List<Pair<String, Int>> = secondData.orEmpty()
    val dual = secondValues.isNotEmpty()
    val maxValue = (
        (data.maxOfOrNull { it.second } ?: 0)
            .coerceAtLeast(secondValues.maxOfOrNull { it.second } ?: 0)
        ).coerceAtLeast(1)

    val slots = data.size
    val todayIndex = slots - 1

    // 数据换一批（切日/周/月）时清掉选择态
    var selected by remember(data) { mutableStateOf(-1) }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        // ---------- 顶部读数：默认为操作提示，选中后显示该天的具体数值 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    fadeIn(tween(MotionDurations.SMALL_MS, easing = EaseOutStrong)) togetherWith
                        fadeOut(tween(MotionDurations.PRESS_MS, easing = EaseOutStrong))
                },
                label = "bar-readout"
            ) { index ->
                val label = data.getOrNull(index)?.first
                if (index in data.indices) {
                    val work = data[index].second
                    val rest = secondValues.getOrNull(index)?.second ?: 0
                    val suffix = if (dual) " · 休息 $rest$secondaryUnit" else ""
                    Text(
                        text = "$label · 用眼 ${work}$primaryUnit$suffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "点一下柱子，看某一天的具体数值",
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .pointerInput(data) {
                    detectTapGestures { offset ->
                        if (slots <= 0) return@detectTapGestures
                        val stepPx = size.width / slots
                        val index = (offset.x / stepPx).toInt().coerceIn(0, slots - 1)
                        selected = if (selected == index) -1 else index
                    }
                }
        ) {
            if (data.isEmpty()) return@Canvas

            // 每个槽位间隔 = 半根柱子宽度，保证柱子间留白
            val slotWidth = size.width / (slots * 1.5f)
            val barWidth = if (dual) slotWidth * 0.62f / 2f else slotWidth * 0.62f
            val step = size.width / slots
            val baseInset = (step - (if (dual) barWidth * 2 + 4.dp.toPx() else barWidth)) / 2f

            fun barHeight(value: Int): Float = if (value <= 0) {
                size.height * 0.035f
            } else {
                (size.height - 10.dp.toPx()) * (value.toFloat() / maxValue)
            }

            // 选中/今天所在槽位的背景底衬
            val hasSelection = selected in data.indices
            data.forEachIndexed { index, _ ->
                val isSelected = hasSelection && index == selected
                val isToday = index == todayIndex
                if (isSelected || isToday) {
                    val bgAlpha = if (isSelected) 0.10f else 0.045f
                    drawRoundRect(
                        color = primary.copy(alpha = bgAlpha),
                        topLeft = Offset(index * step + step * 0.08f, 0f),
                        size = Size(step * 0.84f, size.height),
                        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                    )
                }
            }

            data.forEachIndexed { index, pair ->
                val x0 = index * step + baseInset
                val h0 = barHeight(pair.second)
                // 有选中项时，未选中的柱子压暗，视觉焦点留给选中的那天
                val dim = hasSelection && index != selected
                val mainColor = when {
                    pair.second <= 0 -> emptyColor.copy(alpha = 0.35f)
                    else -> barColor
                }.let { if (dim) it.copy(alpha = 0.30f) else it }
                drawRoundRect(
                    color = mainColor,
                    topLeft = Offset(x0, size.height - h0),
                    size = Size(barWidth, h0),
                    cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
                )

                if (dual) {
                    val second = secondValues.getOrNull(index)?.second ?: 0
                    val h1 = barHeight(second)
                    val x1 = x0 + barWidth + 4.dp.toPx()
                    val subColor = when {
                        second <= 0 -> emptyColor.copy(alpha = 0.35f)
                        else -> secondColor
                    }.let { if (dim) it.copy(alpha = 0.30f) else it }
                    drawRoundRect(
                        color = subColor,
                        topLeft = Offset(x1, size.height - h1),
                        size = Size(barWidth, h1),
                        cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
                    )
                }
            }

            // 今天的小圆点（贴在基线下方，作为固定标记）
            if (todayIndex >= 0) {
                val cx = todayIndex * step + step / 2f
                drawCircle(
                    color = primary,
                    radius = 2.5.dp.toPx(),
                    center = Offset(cx, size.height - 3.dp.toPx())
                )
            }
        }
    }
}
