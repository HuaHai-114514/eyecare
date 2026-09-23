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
 */
class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                EyeTimer.onScreenOn(context)
                SitReminder.resync(context)
            }
            Intent.ACTION_SCREEN_OFF -> {
                EyeTimer.onScreenOff(context)
                AlarmScheduler.cancelSitAlarm(context)
            }
        }
    }
}