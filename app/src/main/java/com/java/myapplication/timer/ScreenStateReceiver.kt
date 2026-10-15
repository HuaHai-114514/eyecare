package com.java.myapplication.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 亮屏 / 息屏监听（由 EyeCareApplication 动态注册）。
 * - 亮屏（含解锁）：重置并开始用眼计时；
 * - 息屏：暂停计时并撤销到点闹钟，息屏期间系统不会有任何本应用的唤醒源。
 *
 * v2.3：同步接管久坐提醒的排定/撤销，保证息屏期间也不留唤醒源。
 *
 * v2.6.12（修「息屏后亮屏误弹超时」）：[android.content.Intent.ACTION_SCREEN_ON] 与
 * [android.content.Intent.ACTION_USER_PRESENT] 是**两次独立广播**，同一次亮屏会先发
 * SCREEN_ON（还没解锁）再发 USER_PRESENT。曾一度让 SCREEN_ON 只同步久坐提醒、不调
 * [EyeTimer.onScreenOn]，以「避免同一轮亮屏被处理两次」。
 *
 * ⚠️ v2.6.21 回归修复：上面那种拆法是错的，已还原为两者都调 [EyeTimer.onScreenOn]。
 *
 * [android.content.Intent.ACTION_USER_PRESENT] **只在「锁屏 → 解锁」时才发**；
 * 若息屏前本就没有锁屏（看视频 / 读长文时自动息屏等），亮屏时系统**只发 SCREEN_ON**。
 * 若 SCREEN_ON 不调 onScreenOn，则「长息屏后亮屏应清零重算」这条路径**一次都不会执行**——
 * 表现就是「熄屏一小时后亮屏，计时没有从 0 重新开始」，且时好时坏（锁屏了才生效），
 * 正是用户实测到的那种间歇性失效。
 *
 * 「同一轮亮屏重复处理」并不需要靠掐掉广播源来规避：[EyeTimer.onScreenOn] 入口就用
 * `consumeScreenOnEdge()` **读后清零**边沿标记，第二次进来拿到的 `cameFromScreenOff`
 * 必为 false，归零判定（`leftThisScreen`）自然不成立 —— 重复调用是幂等的。
 */
class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when {
            // 两者都要触发 onScreenOn：USER_PRESENT 只在「解锁」时发，
            // SCREEN_ON 覆盖面更广（未锁屏的亮屏只有它）。幂等由 consumeScreenOnEdge 保证。
            startsScreenOnSession(intent.action) -> {
                EyeTimer.onScreenOn(context)
                SitReminder.resync(context)
            }
            intent.action == Intent.ACTION_SCREEN_OFF -> {
                EyeTimer.onScreenOff(context)
                AlarmScheduler.cancelSitAlarm(context)
            }
        }
    }

    companion object {
        /**
         * 该广播是否代表「用户回到屏幕前」，即需要走 [EyeTimer.onScreenOn]。
         *
         * 抽成纯函数是为了能被单测直接锁住：v2.6.21 的回归正是**只有
         * `USER_PRESENT` 算数** —— 未锁屏的亮屏（看视频 / 读长文时自动息屏后
         * 再亮）系统只发 `SCREEN_ON`，于是归零重算一次都不执行。
         * 这里两个 action 必须同时为 true。
         *
         * 息屏（`SCREEN_OFF`）与其它广播（`BOOT_COMPLETED` 等）都返回 false。
         */
        fun startsScreenOnSession(action: String?): Boolean =
            action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT
    }
}