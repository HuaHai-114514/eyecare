package com.java.myapplication.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.java.myapplication.MainActivity
import com.java.myapplication.R
import com.java.myapplication.data.SettingsStore

object RestNotifier {
    // 渠道 ID 版本化：v1 渠道曾在 MIUI 通知权限被拒时期创建，被系统记为静音渠道，
    // 换新渠道 ID 并显式设置声音/振动，确保横幅强提示
    private const val CHANNEL_ID = "eye_care_reminder_v2"
    private const val LEGACY_CHANNEL_ID = "eye_care_reminder"
    private const val LEGACY_CHANNEL_ID_V1 = "eye_care_reminder_v1"

    // 休息结束通知的渠道。
    //
    // 几经反复，最终定在「**通知永不发声**」：
    // - v2.3.8 及以前：应用自己播铃声，同时这里还发通知 → 两条声源，「响两次」；
    // - v2.3.9：反过来把发声权交给渠道（用下面的 REST_END_CHOICE_CHANNEL_ID），
    //   通知发出去了、渠道参数也正常，系统却一声没播 —— 不可控；
    // - v2.3.10：声音交回 [RestSoundPlayer] 自己播（唯一声源），本通知固定走静默渠道，
    //   只负责在通知栏留一条「休息结束了」的痕迹。
    private const val REST_END_SILENT_CHANNEL_ID = "eye_care_rest_end_v1"
    private const val LEGACY_REST_END_CHANNEL_ID = "eye_care_rest_end"
    /** v2.3.9 用过的「有声音」渠道，v2.3.10 起不再使用，仅在同步时清理掉 */
    private const val REST_END_CHOICE_CHANNEL_ID = "eye_care_rest_end_v2"

    private const val NOTIFICATION_ID = 1001
    // 独立 id，避免与到点提醒（1001）互相顶掉
    private const val REST_END_NOTIFICATION_ID = 1004

    // 通知「开始休息」按钮携带的 action（MainActivity 据此切换到休息状态）
    const val ACTION_REST_NOW = "com.java.myapplication.ACTION_REST_NOW"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 旧渠道可能被锁定或记为静音，全部删除后重建干净的新渠道
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID_V1)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "护眼休息提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "提醒您按时让眼睛休息"
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(true)
                }
            )
        }
    }

    /**
     * 休息结束通知的渠道（**永远静默**）。
     *
     * v2.3.10 起这里不再有任何"有声音"的渠道：响声由 [RestSoundPlayer] 自己播，
     * 通知只负责在通知栏留一条记录。这样「同一件事响两次」在结构上就没有第二个来源了。
     *
     * 渠道参数的锁定规则（这也是当初想用渠道发音却不好用的原因）：
     * 声音 / 振动 / 重要性**只在渠道首次创建时生效**，之后应用改不动、只有用户能改。
     * 所以声音这类要跟随开关实时变化的东西，不能挂在渠道上。
     */
    private fun ensureRestEndChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 老的「有声音」渠道一并删掉：在 v2.3.9 装过的机器上，它可能还带着铃声配置
            nm.deleteNotificationChannel(LEGACY_REST_END_CHANNEL_ID)
            nm.deleteNotificationChannel(REST_END_CHOICE_CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(
                    REST_END_SILENT_CHANNEL_ID,
                    "休息结束提醒",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "休息倒计时结束的记录，本身不发出声音"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    /** Android 13+ 通知权限是否已授予；低于 13 一律视为有权限 */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 是否允许使用全屏通知（Android 14 起普通应用默认被拒，需用户在本应用通知设置里单独开启）。
     *
     * 低于 Android 14 的系统上没有这个限制，一律返回 true。
     * 该函数同时被后台提醒自检页复用，避免两处各写一遍逻辑。
     */
    fun hasFullScreenPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return try {
            nm.canUseFullScreenIntent()
        } catch (_: Exception) {
            // 个别 ROM 实现异常时保守处理：当作没有权限，降级为横幅
            false
        }
    }

    /**
     * 工作到点的提醒通知：只负责「提醒」，不自动进入休息。
     * 用户点击通知里的「开始休息」按钮后才开始休息倒计时。
     *
     * @param workMinutes 本次已连续用眼的分钟数（用于正文文案，避免写死）
     */
    fun notifyRestReminder(context: Context, workMinutes: Int, restSeconds: Int) {
        ensureChannel(context)

        if (!canPostNotifications(context)) return

        val settings = SettingsStore.load(context)
        // 通知点击 → 打开应用（后台时也会把应用切到前台，展示「该休息了」确认页）
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context, 1002, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye_care)
            .setContentTitle("🌿 该让眼睛休息啦")
            .setContentText("点击「开始休息」，远眺 $restSeconds 秒")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("已连续用眼 $workMinutes 分钟。点下方「开始休息」按钮，即刻起算 $restSeconds 秒远眺倒计时。")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentPending)

        // A2：到点自动全屏弹出休息页。
        // Android 14+ 该能力默认不授予普通应用（只给通话/闹钟类），所以必须先查权限：
        // 没权限时不再闭眼调用（系统会静默吞掉，用户只会觉得「这功能坏了」），
        // 降级为普通横幅，并在自检页引导用户去开启。
        if (settings.autoFullScreen && hasFullScreenPermission(context)) {
            builder.setFullScreenIntent(contentPending, true)
        } else if (settings.autoFullScreen) {
            // 明确告知降级原因，避免用户误判为 Bug
            builder.setSubText("全屏提醒权限未开启，已降级为横幅提醒")
        }

        // 「开始休息」动作按钮：点击后才真正开始休息计时。
        //
        // 走 getActivity 而非 getBroadcast：由系统代发 Activity 启动，与通知本体同一条
        // 通道（PendingIntent 白名单豁免），不依赖应用自身的后台启动能力。
        // 早前用 getBroadcast + Receiver 内 startActivity 的写法，在后台状态下
        // 会被系统静默丢弃（无异常、无闪烁，只有通知消失），这条路径已废弃。
        val restPending = PendingIntent.getActivity(
            context, 1003,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_REST_NOW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, "🌿 开始休息", restPending)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 休息结束提醒（v2.3.6 新增；v2.3.10 起**只留痕、永不发声**）。
     *
     * 几轮试错之后，这条通知只做一件事：在通知栏留一条「休息结束了」的记录
     * （息屏 / 应用被杀时也留下痕迹）。
     *
     * 响声由 [RestSoundPlayer] 自己播、振动由 [RestVibrator] 自己发，都不再经过这里。
     * 这样「同一件事响两次」在结构上就没有第二个声源了 —— 这正是 v2.3.8 的毛病所在。
     *
     * 顺带说明为什么这里没有 `soundEnabled` 参数：渠道的声音配置只在**首次创建时**生效，
     * 之后应用改不动（只有用户能在系统设置里改）。所以"跟随开关实时变化"的声音
     * 从一开始就不该挂在渠道上，而应该由应用自己播。
     */
    fun notifyRestEnd(context: Context) {
        if (!canPostNotifications(context)) return
        ensureRestEndChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context, 1005, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 固定走唯一的静默渠道：这里不存在"有声音"的分支，也就没有第二个声源。
        // 这里**不能**用 setSilent(true) —— 它在 API 26+ 会把整个渠道的声音压掉，
        // 而我们要的是「渠道本来就静音」，不是「临时压一下」（后者行为随 ROM 而异）。
        val builder = NotificationCompat.Builder(context, REST_END_SILENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye_care)
            .setContentTitle("🌿 休息结束，可以继续了")
            .setContentText("眼睛放松好了，回到屏幕前继续吧")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(contentPending)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(REST_END_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    // 清除提醒通知（按钮点击 / 进入休息 / 息屏后调用）
    fun cancelReminder(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    /**
     * 清除「休息结束」提醒（v2.3.9）。
     * v2.3.10 起这条通知只是痕迹：取消它 = 收起通知栏那条记录。
     * 真正还在响的声音由 [RestSoundPlayer.stop] 负责掐掉（见 [com.java.myapplication.timer.EyeTimer]）。
     */
    fun cancelRestEnd(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(REST_END_NOTIFICATION_ID)
    }
}
