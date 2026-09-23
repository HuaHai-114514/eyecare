package com.java.myapplication.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 计时状态机持久化（累计亮屏用眼模型）。
 *
 * v1.8 起计时语义从「周期开始时间戳」改为「亮屏用眼时长累计」：
 * - workAccumMs：本次用眼周期已累计的亮屏毫秒数（息屏期间不增长）；
 * - screenOnAt：当前这次亮屏的开始时刻（>0 表示正在累计；-1 表示已暂停/息屏）；
 * - offAt：最近一次息屏的时刻（用于判断「短暂息屏续算 / 长时间离开重算」）。
 *
 * 应用被杀、设备重启后仍可恢复：息屏时累计值已落盘，亮屏时按差值续算。
 */
data class TimerState(
    val reminderEnabled: Boolean = true,
    val phaseResting: Boolean = false,
    val awaitingRest: Boolean = false,   // 已到点、已提醒，等待用户点「开始休息」
    val workAccumMs: Long = 0L,          // 已累计的亮屏用眼毫秒
    val screenOnAt: Long = -1L,          // 本次亮屏开始时刻；-1 表示未在累计
    val offAt: Long = -1L,               // 最近一次息屏时刻
    val restStart: Long = -1L,           // 休息开始时刻
    val tipIndex: Int = -1               // 休息页展示的知识索引
)

object TimerStore {
    private const val PREFS = "eye_care_timer"
    private const val KEY_REMINDER = "reminder_enabled"
    private const val KEY_RESTING = "phase_resting"
    private const val KEY_AWAITING = "awaiting_rest"
    private const val KEY_ACCUM = "work_accum_ms"
    private const val KEY_SCREEN_ON_AT = "screen_on_at"
    private const val KEY_OFF_AT = "off_at"
    private const val KEY_REST_START = "rest_start"
    private const val KEY_TIP_INDEX = "tip_index"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): TimerState {
        val p = prefs(context)
        return TimerState(
            reminderEnabled = p.getBoolean(KEY_REMINDER, true),
            phaseResting = p.getBoolean(KEY_RESTING, false),
            awaitingRest = p.getBoolean(KEY_AWAITING, false),
            workAccumMs = p.getLong(KEY_ACCUM, 0L),
            screenOnAt = p.getLong(KEY_SCREEN_ON_AT, -1L),
            offAt = p.getLong(KEY_OFF_AT, -1L),
            restStart = p.getLong(KEY_REST_START, -1L),
            tipIndex = p.getInt(KEY_TIP_INDEX, -1)
        )
    }

    fun save(context: Context, state: TimerState) {
        prefs(context).edit()
            .putBoolean(KEY_REMINDER, state.reminderEnabled)
            .putBoolean(KEY_RESTING, state.phaseResting)
            .putBoolean(KEY_AWAITING, state.awaitingRest)
            .putLong(KEY_ACCUM, state.workAccumMs)
            .putLong(KEY_SCREEN_ON_AT, state.screenOnAt)
            .putLong(KEY_OFF_AT, state.offAt)
            .putLong(KEY_REST_START, state.restStart)
            .putInt(KEY_TIP_INDEX, state.tipIndex)
            .apply()
    }
}