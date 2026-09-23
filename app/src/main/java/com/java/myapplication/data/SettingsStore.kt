package com.java.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import com.java.myapplication.notify.RestSoundSource

/**
 * 用户设置（v2.3 扩展版）。
 *
 * 字段分四组：
 * - 计时：用眼提醒间隔 / 休息时长 / 每日用眼目标
 * - 提醒：到点自动全屏 / 免打扰时段
 * - 久坐：久坐提醒开关与间隔
 * - 外观：自动夜间模式
 *
 * 所有字段都有默认值，旧版本升级上来时缺失的键会取默认值，不会崩。
 */
data class AppSettings(
    // ============ 计时 ============
    val workMinutes: Int = 20,          // 用眼提醒间隔（分钟）
    val restSeconds: Int = 20,          // 休息时长（秒）
    val dailyGoalMinutes: Int = 60,     // 每日用眼目标上限（分钟）

    // ============ 提醒 ============
    val autoFullScreen: Boolean = true, // 到点是否自动弹出全屏休息页
    val dndEnabled: Boolean = false,    // 免打扰时段开关
    val dndStartMinute: Int = 12 * 60,  // 免打扰开始（当天 0 点起的分钟数，默认 12:00）
    val dndEndMinute: Int = 14 * 60,    // 免打扰结束（默认 14:00）

    // ============ 久坐 ============
    val sitReminderEnabled: Boolean = true,  // 久坐提醒开关
    val sitIntervalMinutes: Int = 45,        // 久坐提醒间隔（分钟）

    // ============ 提示音（v2.3.6 新增，v2.3.8 换音源） ============
    val restEndSoundEnabled: Boolean = true, // 休息结束时是否播放提示音

    /**
     * 提示音是否同时短振动（v2.3.8 新增）。
     *
     * v2.3.8 起提示音改为「系统通知铃声 + 短振动」：
     * 通知铃声天生只有一两秒，不再需要设置时长（v2.3.7 的时长选项已移除）。
     * 振动默认开启 —— 手机扣在桌上时铃声容易被挡住，振动更稳妥。
     */
    val restEndSoundVibrate: Boolean = true,

    /**
     * 休息结束提示音的**音源**（v2.3.12 新增）。
     *
     * 默认「跟随系统通知铃声」—— 即用户原来听惯的那个声音。
     * v2.3.11 曾锁死为内置单音，这一版恢复为可选。
     */
    val restEndSoundSource: RestSoundSource = RestSoundSource.DEFAULT,

    // ============ 外观 ============
    val autoNightMode: Boolean = true   // 自动随系统切换夜间模式
) {
    companion object {
        /** 久坐间隔可选值，设置页用 */
        val SIT_INTERVAL_CHOICES = listOf(30, 45, 60)

        /**
         * 提示音状态的可读文案（v2.3.8，v2.3.12 增加音源）。
         *
         * 写成纯函数放在这里而不是塞进 Compose，是为了能直接单测。
         * [sourceLabel] 是当前音源名（见 [RestSoundSource.label]），关闭时不展示。
         */
        fun describeRestEndSound(
            enabled: Boolean,
            vibrate: Boolean,
            sourceLabel: String = RestSoundSource.DEFAULT.label
        ): String = when {
            !enabled -> "已关闭"
            vibrate -> "已开启（$sourceLabel + 振动）"
            else -> "已开启（$sourceLabel）"
        }

        /**
         * 休息结束是否真的短振动（v2.3.9）。
         *
         * 「同时振动」是从属于提示音的选项：提示音整个关掉时不该还震一下 ——
         * 用户关掉的是「休息结束的打扰」，不是「只关声音」。
         * 这条规则以前散在调用处，现在抽成纯函数，顺便能单测。
         */
        fun isRestEndVibrateActive(enabled: Boolean, vibrate: Boolean): Boolean =
            enabled && vibrate

        /** 把「当天 0 点起的分钟数」格式化为 HH:mm */
        fun formatMinuteOfDay(minute: Int): String {
            val m = minute.coerceIn(0, 24 * 60 - 1)
            return "%02d:%02d".format(m / 60, m % 60)
        }

        /** 解析 HH:mm，失败返回 fallback */
        fun parseMinuteOfDay(text: String, fallback: Int): Int {
            val parts = text.trim().split(":")
            if (parts.size != 2) return fallback
            val h = parts[0].toIntOrNull() ?: return fallback
            val m = parts[1].toIntOrNull() ?: return fallback
            if (h !in 0..23 || m !in 0..59) return fallback
            return h * 60 + m
        }
    }
}

object SettingsStore {
    private const val PREFS = "eye_care_settings"
    private const val KEY_WORK = "work_minutes"
    private const val KEY_REST = "rest_seconds"
    private const val KEY_AUTO_NIGHT = "auto_night_mode"
    // v2.3 新增
    private const val KEY_DAILY_GOAL = "daily_goal_minutes"
    private const val KEY_AUTO_FULL_SCREEN = "auto_full_screen"
    private const val KEY_DND_ENABLED = "dnd_enabled"
    private const val KEY_DND_START = "dnd_start_minute"
    private const val KEY_DND_END = "dnd_end_minute"
    private const val KEY_SIT_ENABLED = "sit_reminder_enabled"
    private const val KEY_SIT_INTERVAL = "sit_interval_minutes"
    // v2.3.6 新增
    private const val KEY_REST_END_SOUND = "rest_end_sound_enabled"
    // v2.3.8 新增
    // 注：v2.3.7 的 "rest_end_sound_seconds" 键已随音源改造废弃，
    // 旧设备上残留的该键会被忽略，无需清理（多条无害数据不影响行为）。
    private const val KEY_REST_END_SOUND_VIBRATE = "rest_end_sound_vibrate"
    // v2.3.12 新增：提示音音源（存 RestSoundSource 的 name；未知值回退默认）
    private const val KEY_REST_END_SOUND_SOURCE = "rest_end_sound_source"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppSettings {
        val p = prefs(context)
        val d = AppSettings()
        return AppSettings(
            workMinutes = p.getInt(KEY_WORK, d.workMinutes),
            restSeconds = p.getInt(KEY_REST, d.restSeconds),
            dailyGoalMinutes = p.getInt(KEY_DAILY_GOAL, d.dailyGoalMinutes),
            autoFullScreen = p.getBoolean(KEY_AUTO_FULL_SCREEN, d.autoFullScreen),
            dndEnabled = p.getBoolean(KEY_DND_ENABLED, d.dndEnabled),
            dndStartMinute = p.getInt(KEY_DND_START, d.dndStartMinute),
            dndEndMinute = p.getInt(KEY_DND_END, d.dndEndMinute),
            sitReminderEnabled = p.getBoolean(KEY_SIT_ENABLED, d.sitReminderEnabled),
            sitIntervalMinutes = p.getInt(KEY_SIT_INTERVAL, d.sitIntervalMinutes),
            restEndSoundEnabled = p.getBoolean(KEY_REST_END_SOUND, d.restEndSoundEnabled),
            restEndSoundVibrate = p.getBoolean(KEY_REST_END_SOUND_VIBRATE, d.restEndSoundVibrate),
            restEndSoundSource = RestSoundSource.fromKey(p.getString(KEY_REST_END_SOUND_SOURCE, null)),
            autoNightMode = p.getBoolean(KEY_AUTO_NIGHT, d.autoNightMode)
        )
    }

    fun save(context: Context, settings: AppSettings) {
        prefs(context).edit()
            .putInt(KEY_WORK, settings.workMinutes)
            .putInt(KEY_REST, settings.restSeconds)
            .putInt(KEY_DAILY_GOAL, settings.dailyGoalMinutes)
            .putBoolean(KEY_AUTO_FULL_SCREEN, settings.autoFullScreen)
            .putBoolean(KEY_DND_ENABLED, settings.dndEnabled)
            .putInt(KEY_DND_START, settings.dndStartMinute)
            .putInt(KEY_DND_END, settings.dndEndMinute)
            .putBoolean(KEY_SIT_ENABLED, settings.sitReminderEnabled)
            .putInt(KEY_SIT_INTERVAL, settings.sitIntervalMinutes)
            .putBoolean(KEY_REST_END_SOUND, settings.restEndSoundEnabled)
            .putBoolean(KEY_REST_END_SOUND_VIBRATE, settings.restEndSoundVibrate)
            .putString(KEY_REST_END_SOUND_SOURCE, settings.restEndSoundSource.name)
            .putBoolean(KEY_AUTO_NIGHT, settings.autoNightMode)
            .apply()
    }
}