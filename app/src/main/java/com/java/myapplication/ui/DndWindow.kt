package com.java.myapplication.ui

import com.java.myapplication.data.AppSettings

/**
 * 免打扰时段判断（v2.3 新增）。
 *
 * 从 ViewModel 里抽出来单独放，是为了让非 UI 层（EyeTimer / SitReminder / TimerReceiver）
 * 也能复用同一套判断逻辑，避免两处实现不一致。
 */
object DndWindow {

    /**
     * 当前是否落在免打扰时段内。
     *
     * 支持跨午夜（如 23:00 - 07:00）；起止相等视为「不启用」，避免误静音全天。
     *
     * @param settings  用户设置
     * @param minuteOfDay 当前时刻「当天 0 点起的分钟数」(0..1439)
     */
    fun isInDndWindow(settings: AppSettings, minuteOfDay: Int): Boolean {
        if (!settings.dndEnabled) return false
        val s = settings.dndStartMinute
        val e = settings.dndEndMinute
        if (s == e) return false
        return if (s < e) minuteOfDay in s until e
        else minuteOfDay >= s || minuteOfDay < e
    }
}