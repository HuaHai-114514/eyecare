package com.java.myapplication.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.java.myapplication.data.SettingsStore
import com.java.myapplication.data.StatsStore
import com.java.myapplication.data.TimerStore
import com.java.myapplication.ui.DndWindow

/**
 * 后台到点闹钟接收器。
 * - 工作到点 → 仅发送提醒通知，等待用户点击「开始休息」（不自动进入休息）；
 * - 休息到点 → 记录统计并衔接下一周期（若已息屏则暂停待命）；
 * - 久坐到点 → 按间隔弹久坐提醒（免打扰时段内静默）。
 * 进程被回收后由系统拉起本接收器执行，提醒不依赖界面存活。
 */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_WORK_DONE -> {
                // 双重确认提醒仍开启（开关切换与闹钟到期可能存在竞态）
                if (TimerStore.load(context).reminderEnabled) {
                    EyeTimer.onWorkDeadline(context)
                }
            }
            AlarmScheduler.ACTION_REST_DONE -> EyeTimer.finishRest(context)
            AlarmScheduler.ACTION_SIT_DONE -> {
                val silent = DndWindow.isInDndWindow(
                    SettingsStore.load(context),
                    StatsStore.minuteOfDay()
                )
                SitReminder.onDeadline(context, silent)
            }
        }
    }
}