package com.java.myapplication.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.java.myapplication.ui.theme.GrassGreen
import com.java.myapplication.ui.theme.MistBlue

/**
 * 柔和柱状图（v2.3 支持双序列）。
 *
 * - 单序列：`data` 为「标签 → 数值」，柱子居中满宽。
 * - 双序列：额外传入 `secondData` + `secondColor`，两组柱子并排展示
 *   （左＝主序列，右＝副序列），并各自按全局最大值归一化，方便横向对比。
 */
@Composable
fun DailyBarChart(
    data: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    barColor: Color = GrassGreen,
    emptyColor: Color = MistBlue,
    secondData: List<Pair<String, Int>>? = null,
    secondColor: Color = MistBlue
) {
    // 为空时统一退化为空列表，dual 由它派生，避免对可空参数做多余的空判断
    val secondValues: List<Pair<String, Int>> = secondData.orEmpty()
    val dual = secondValues.isNotEmpty()
    val maxValue = (
        (data.maxOfOrNull { it.second } ?: 0)
            .coerceAtLeast(secondValues.maxOfOrNull { it.second } ?: 0)
        ).coerceAtLeast(1)

    Canvas(modifier = modifier.fillMaxWidth().height(170.dp)) {
        if (data.isEmpty()) return@Canvas

        val slots = data.size
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

        data.forEachIndexed { index, pair ->
            val x0 = index * step + baseInset
            val h0 = barHeight(pair.second)
            drawRoundRect(
                color = if (pair.second <= 0) emptyColor.copy(alpha = 0.35f) else barColor,
                topLeft = Offset(x0, size.height - h0),
                size = Size(barWidth, h0),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )

            if (dual) {
                val second = secondValues.getOrNull(index)?.second ?: 0
                val h1 = barHeight(second)
                val x1 = x0 + barWidth + 4.dp.toPx()
                drawRoundRect(
                    color = if (second <= 0) emptyColor.copy(alpha = 0.35f) else secondColor,
                    topLeft = Offset(x1, size.height - h1),
                    size = Size(barWidth, h1),
                    cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
                )
            }
        }
    }
}