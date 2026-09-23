package com.java.myapplication.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.java.myapplication.MainActivity
import com.java.myapplication.R
import com.java.myapplication.data.SettingsStore

/**
 * 久坐提醒（v2.3 新增）。
 *
 * 设计要点：
 * - 与 20-20-20 用眼周期**完全独立**，走自己的闹钟通道 [AlarmScheduler.scheduleSitReminder]；
 * - 只在**亮屏期间**排闹钟：息屏时撤销，亮屏时重排，避免息屏期间空转耗电；
 * - 每次触发后立即重排下一个间隔，形成「亮屏期间每 N 分钟一次」的节奏；
 * - 走独立通知渠道，可在闹钟便捷开关里单独管理。
 */
object SitReminder {

    private const val CHANNEL_ID = "eye_care_sit_v1"
    private const val NOTIFICATION_ID = 1002

    /** 确保通知渠道存在 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "久坐活动提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "按设定间隔提醒您起身活动一下"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            )
        }
    }

    /**
     * 依据当前设置重新排定久坐闹钟。
     * - 提醒开关关闭 / 久坐关闭 / 息屏 → 撤销闹钟
     * - 其余情况 → 从「现在」起算一个间隔
     *
     * 幂等：相同 PendingIntent 重复 set 会直接覆盖，不会堆积。
     */
    fun resync(context: Context) {
        val s = SettingsStore.load(context)
        val active = s.sitReminderEnabled && EyeTimer.isScreenOn(context)
        if (!active) {
            AlarmScheduler.cancelSitAlarm(context)
            return
        }
        val intervalMs = s.sitIntervalMinutes.coerceAtLeast(1) * 60 * 1000L
        AlarmScheduler.scheduleSitReminder(context, System.currentTimeMillis() + intervalMs)
    }

    /**
     * 久坐闹钟到点。
     *
     * @param silent 免打扰时段内为 true，只静默提醒（不出声音/横幅）
     */
    fun onDeadline(context: Context, silent: Boolean = false) {
        val s = SettingsStore.load(context)
        if (!s.sitReminderEnabled) {
            AlarmScheduler.cancelSitAlarm(context)
            return
        }
        // 息屏期间不做久坐提醒（人大概率已经离开），等亮屏时由 resync 重排
        if (!EyeTimer.isScreenOn(context)) {
            AlarmScheduler.cancelSitAlarm(context)
            return
        }

        notifySit(context, s.sitIntervalMinutes, silent)

        // 立即排下一个周期，形成循环节奏
        val intervalMs = s.sitIntervalMinutes.coerceAtLeast(1) * 60 * 1000L
        AlarmScheduler.scheduleSitReminder(context, System.currentTimeMillis() + intervalMs)
    }

    /** 取消久坐提醒并清掉已弹出的通知 */
    fun cancel(context: Context) {
        AlarmScheduler.cancelSitAlarm(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun notifySit(context: Context, intervalMinutes: Int, silent: Boolean) {
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 1012, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye_care)
            .setContentTitle("🧍 起来活动一下吧")
            .setContentText("已经连续坐姿 $intervalMinutes 分钟了，起身走走、活动下肩颈")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("已经连续坐姿 $intervalMinutes 分钟了。起身走走、转转肩颈，让身体也休息一下。")
            )
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pi)

        // 免打扰时段：静音 + 不出横幅，只在通知栏里静静躺着
        if (silent) {
            builder.setSilent(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setOnlyAlertOnce(true)
            }
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }
}
