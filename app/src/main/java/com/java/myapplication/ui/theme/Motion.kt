package com.java.myapplication.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 全项目统一的动效词汇表。
 *
 * 之前各处直接写 `tween(...)`，用的是 Compose 默认的 `FastOutSlowInEasing`
 * —— 那是一条弱 ease-in-out，入场时起手偏慢，正是 Emil Kowalski 说的
 * 「ease-in delays the moment the user watches most」。这里集中定义两条强曲线，
 * 所有入场/退场都从这里取，避免每个文件各写一套。
 */

/** 强 ease-out：入场 / 退场用。起手快、落点稳，UI 动效的默认曲线。 */
val EaseOutStrong: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** 强 ease-in-out：同屏位移 / 形变用（元素已经在屏幕上，只是换位置）。 */
val EaseInOutStrong: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/**
 * 时长预算（对齐 Emil Kowalski 的分档）。
 *
 * 规则：UI 动效一律 300ms 以内。全屏休息页按「模态」档（200–500ms）处理。
 */
object MotionDurations {
    /** 按压反馈 100–160ms */
    const val PRESS_MS = 120

    /** 小浮层 / 提示 125–200ms */
    const val SMALL_MS = 160

    /** 常规入场 200–240ms */
    const val ENTER_MS = 200

    /** 退场：比入场快，系统响应要干脆 */
    const val EXIT_MS = 140

    /** 全屏休息页（模态档） */
    const val MODAL_MS = 300

    /**
     * 底部导航落页（核心导航，几十次/天）。
     *
     * 页面切换本身走 HorizontalPager：位移在 pager 自己的 graphicsLayer 上，
     * 不经过 measure/layout/draw，所以「有位移」和「不掉帧」可以同时成立。
     * 导航点击只是发起一次 `animateScrollToPage`，用的是弹簧而不是时长，
     * 这里的常量留给「药丸 indicator 要滑多久」这类需要固定时长的地方。
     */
    const val NAV_MS = 200
}

/**
 * 全项目共用的「落位」弹簧参数。
 *
 * Apple《Designing Fluid Interfaces》里，弹簧只用两个参数描述：
 * 阻尼比（damping ratio）和响应（response）。约定是
 * **默认临界阻尼（1.0）不弹跳，只有手势本身带了动量时才允许回弹（~0.8）**。
 *
 * 这里全部用 1.0：底部导航是「系统发起」的落位动作，
 * 没有动量需要延续，回弹会显得油滑而不准。
 */
val SettleSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)
