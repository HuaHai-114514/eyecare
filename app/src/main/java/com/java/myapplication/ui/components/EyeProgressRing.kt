package com.java.myapplication.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.java.myapplication.ui.theme.CoralRed
import com.java.myapplication.ui.theme.GrassGreen
import com.java.myapplication.ui.theme.SunOrange

// 「眼眸」形状的进度环：随用眼进度从绿 → 橙 → 红渐变
@Composable
fun EyeProgressRing(
    progress: Float,             // 0f..1f
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    strokeWidth: Dp = 18.dp,
    trackColor: Color = Color(0x1A4CAF7D)
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300),
        label = "ringProgress"
    )

    // 根据进度计算渐变颜色
    val color = when {
        animatedProgress < 0.6f -> GrassGreen
        animatedProgress < 0.85f -> SunOrange
        else -> CoralRed
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            // 轨道
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 进度（渐变）
            //
            // 设计意图：整条环从**浅绿（主色）→ 橙红**，即「用眼越久越该休息」。
            // 由于 drawArc 的起点在 12 点方向（startAngle = -90）而在 ColorSpace
            // 下 Brush.sweepGradient 的色标 0.0 对应 3 点方向（0°），直接把
            // [绿, 橙, 红] 交给 sweepGradient 会让 12 点处的颜色错位成红色。
            //
            // 修正：把整个渐变「顺时针旋转 90°」，使得 12 点方向（色标 0.75）
            // 正好落在浅绿上，随后顺时针依次过渡到橙红。
            //
            // 色标与物理角度的对应（t 为 sweepGradient 参数，0°=3点）：
            //   t=0.00 → 3点
            //   t=0.25 → 6点
            //   t=0.50 → 9点
            //   t=0.75 → 12点  ← 与 drawArc 起点对齐，必须是浅绿
            //   t=1.00 → 回到 3点
            // 因此按「从 12 点顺时针」的颜色顺序采样，得到如下序列。
            if (animatedProgress > 0f) {
                val brush = Brush.sweepGradient(
                    0.00f to lerpGreenToOrange(0.25f),  // 3点（12点顺时针 90°）
                    0.25f to SunOrange,                  // 6点（顺时针 180°）
                    0.50f to lerpOrangeToRed(0.50f),     // 9点（顺时针 270°）
                    0.75f to GrassGreen,                 // 12点（起点，浅绿）
                    1.00f to lerpGreenToOrange(0.25f),   // 回到 3点，闭合
                    center = center
                )
                drawArc(
                    brush = brush,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

// 渐变锚点之间的线性插值（用于让色标在 12 点起点严格为浅绿）。
private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

private fun lerpGreenToOrange(t: Float): Color = lerpColor(GrassGreen, SunOrange, t)
private fun lerpOrangeToRed(t: Float): Color = lerpColor(SunOrange, CoralRed, t)
