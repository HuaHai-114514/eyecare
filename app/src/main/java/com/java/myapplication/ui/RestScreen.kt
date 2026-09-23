package com.java.myapplication.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.java.myapplication.ui.components.EyeProgressRing
import com.java.myapplication.ui.theme.*

// 全屏沉浸式休息页：模糊渐变背景 + 远眺倒计时 + 健康知识
//
// v2.3.1 修复：原实现把低透明度的品牌色（GrassGreen/MistBlue alpha 0.35）
// 直接叠在浅色背景上作为背景，深色模式下背景仍是「浅灰绿 + 浅灰蓝」，
// 而文字用的是 onBackground（深色模式=浅色文字），深底浅字叠浅底 → 可读性差。
// 现在按明暗模式分别给出**不依赖 alpha 混合的实心配色**，保证对比度：
//  - 浅色：米白 → 晨雾蓝 → 柔光米白（浅底 + 深墨字）
//  - 深色：深夜绿 → 深蓝灰 → 夜底（深底 + 浅字）
@Composable
fun RestScreen(viewModel: EyeCareViewModel, context: Context) {
    val dark = isSystemInDarkTheme() && viewModel.settings.autoNightMode

    // 背景渐变（实心色，不再用 alpha 叠加）
    val bgTop = if (dark) RestDarkTop else RestLightTop
    val bgMid = if (dark) RestDarkMid else RestLightMid
    val bgBottom = if (dark) RestDarkBottom else RestLightBottom

    // 前景文字色（与背景成对定义，确保对比）
    val titleColor = if (dark) NightText else InkGray
    // 副标题/正文用加深一档的灰，避免落在极淡渐变底上偏灰
    val subtitleColor = if (dark) NightTextSecondary else SubtitleOnLight
    val accentColor = if (dark) NightGreenBright else GrassGreenDeep

    // 知识卡与按钮
    val cardBg = if (dark) RestDarkCard else SoftIvoryCard
    val cardTitleColor = if (dark) NightText else InkGray
    val cardBodyColor = if (dark) NightTextSecondary else InkGraySecondary
    val ringTrack = if (dark) Color(0x33D9E3DC) else Color(0x1A4CAF7D)
    val buttonBorder = if (dark) NightGreenBright else GrassGreenDeep

    val restProgress = if (viewModel.settings.restSeconds <= 0) 1f
    else 1f - viewModel.restRemaining.toFloat() / viewModel.settings.restSeconds

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(bgTop, bgMid, bgBottom))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🌄", fontSize = 56.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                "请抬头，眺望远方",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "让眼睛离开屏幕，看向 6 米外的远方",
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            // 休息倒计时环
            Box(contentAlignment = Alignment.Center) {
                EyeProgressRing(
                    progress = restProgress,
                    size = 200.dp,
                    strokeWidth = 18.dp,
                    trackColor = ringTrack
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${viewModel.restRemaining}",
                        fontSize = 60.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor
                    )
                    Text(
                        "秒",
                        style = MaterialTheme.typography.labelMedium,
                        color = subtitleColor
                    )
                }
            }

            Spacer(Modifier.height(36.dp))

            // 健康知识卡片（实心卡面 + 高对比文字）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardBg)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "🌿 护眼知识",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${viewModel.currentTip.emoji} ${viewModel.currentTip.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cardTitleColor,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    viewModel.currentTip.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cardBodyColor,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(32.dp))

            // 提前结束按钮
            OutlinedButton(
                onClick = { viewModel.skipRest(context) },
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, buttonBorder),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    "我已休息好，继续工作",
                    color = accentColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

// ============ 休息页专用配色（成对定义，避免明暗模式错配） ============

// 浅色模式：柔和的米白 → 浅蓝 → 米白，前景用深墨色
private val RestLightTop = Color(0xFFEAF3EC)      // 极淡原野绿
private val RestLightMid = Color(0xFFE4EEF5)      // 极淡晨雾蓝
private val RestLightBottom = Color(0xFFFAF8F5)   // 柔光米白

// 深色模式：深夜绿 → 深蓝灰 → 夜底，前景用浅灰绿字
private val RestDarkTop = Color(0xFF20302A)       // 深夜绿
private val RestDarkMid = Color(0xFF1F2A31)       // 深蓝灰
private val RestDarkBottom = Color(0xFF1A2420)    // 夜底（与 NightBackground 一致）
private val RestDarkCard = Color(0xFF2A3831)      // 深色卡面

// 高对比辅助色（休息页专用：在极淡渐变底 / 深色卡面上都能达到 WCAG AA）
private val NightGreenBright = Color(0xFF7CC9A0)  // 深色底上的绿字（比 NightGreen 更亮）
private val GrassGreenDeep = Color(0xFF2F7A54)    // 浅色底上的绿字（比 GrassGreenDark 更深）
private val SubtitleOnLight = Color(0xFF4F5F56)   // 浅色底上的副标题灰（比 InkGraySecondary 更深）
