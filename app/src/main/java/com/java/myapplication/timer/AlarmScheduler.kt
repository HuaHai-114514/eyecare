package com.java.myapplication.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 统一管理用眼/休息到点闹钟。
 * - 精确闹钟权限可用时使用 setExactAndAllowWhileIdle（Doze 下也准时），
 *   否则自动退化为 setAndAllowWhileIdle（最坏延迟数分钟，但必定触发）。
 * - 相同 PendingIntent 重复 set 会直接覆盖，天然幂等，可放心重复排。
 * - 闹钟由系统持有，进程被回收后到点仍会拉起 TimerReceiver 执行。
 */
object AlarmScheduler {

    const val ACTION_WORK_DONE = "com.java.myapplication.ACTION_WORK_DONE"
    const val ACTION_REST_DONE = "com.java.myapplication.ACTION_REST_DONE"
    const val ACTION_SIT_DONE = "com.java.myapplication.ACTION_SIT_DONE"

    private const val REQUEST_WORK = 2001
    private const val REQUEST_REST = 2002
    private const val REQUEST_SIT = 2003

    fun scheduleWorkDeadline(context: Context, triggerAt: Long) {
        setAlarm(context, triggerAt, pendingIntent(context, ACTION_WORK_DONE, REQUEST_WORK))
    }

    fun scheduleRestDeadline(context: Context, triggerAt: Long) {
        setAlarm(context, triggerAt, pendingIntent(context, ACTION_REST_DONE, REQUEST_REST))
    }

    /** 久坐提醒：与 20-20-20 周期独立，按设定间隔重复唤醒（亮屏期间才有效） */
    fun scheduleSitReminder(context: Context, triggerAt: Long) {
        setAlarm(context, triggerAt, pendingIntent(context, ACTION_SIT_DONE, REQUEST_SIT))
    }

    fun cancelWorkAlarm(context: Context) {
        alarmManager(context).cancel(pendingIntent(context, ACTION_WORK_DONE, REQUEST_WORK))
    }

    fun cancelRestAlarm(context: Context) {
        alarmManager(context).cancel(pendingIntent(context, ACTION_REST_DONE, REQUEST_REST))
    }

    fun cancelSitAlarm(context: Context) {
        alarmManager(context).cancel(pendingIntent(context, ACTION_SIT_DONE, REQUEST_SIT))
    }

    /**
     * 非唤醒兜底闹钟：使用 set()（不唤醒设备，仅设备下次醒来时投递）。
     * 用于息屏期间保留一个「下次亮屏时校正计时」的廉价检查点，
     * 即使应用进程被杀也能在下次唤醒时补齐推进，且几乎不耗电。
     */
    fun scheduleWorkCheckFallback(context: Context, triggerAt: Long) {
        val am = alarmManager(context)
        try {
            @Suppress("DEPRECATION")
            am.set(AlarmManager.RTC, triggerAt, pendingIntent(context, ACTION_WORK_DONE, REQUEST_WORK))
        } catch (_: SecurityException) {
        }
    }

    fun cancelAll(context: Context) {
        cancelWorkAlarm(context)
        cancelRestAlarm(context)
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        // 显式组件：无 intent-filter 的接收器只能被显式 intent 触发，
        // 隐式包级 intent 会被系统静默丢弃（此前后台到点不提醒的根因）
        val intent = Intent(context, TimerReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(context: Context, triggerAt: Long, pi: PendingIntent) {
        val am = alarmManager(context)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            // 极端情况下精确闹钟权限被收回：退化为普通闹钟
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}
