package com.java.myapplication.data

import android.content.Context

/**
 * 首次启动引导的完成标记（v2.3.13 新增）。
 *
 * 单独一个 store 而不是塞进 [AppSettings]，是因为它不属于「用户设置」而是「应用状态」：
 * 不该出现在设置对话框里，也不该在每次保存设置时被顺带写一遍。
 *
 * 语义很轻：只看过一次就不再自动弹。想重看可靠性设置，走
 * 「护眼设置 → 提醒可靠性自检」，那条路一直在。
 */
object OnboardingStore {
    private const val PREFS = "eye_care_onboarding"
    private const val KEY_DONE = "onboarding_done"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 引导是否已完成（默认 false = 还没看过） */
    fun isDone(context: Context): Boolean = prefs(context).getBoolean(KEY_DONE, false)

    /** 标记引导已完成（走完两步和主动跳过都算完成） */
    fun markDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }
}
