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
    val tipIndex: Int = -1,              // 休息页展示的知识索引

    // ============ v2.4.0：超时二次提醒 ============
    /** 已到点等待休息期间，超时累计是否已在走动（true 表示超时时长正在计入统计） */
    val overdueCounting: Boolean = false,
    /** 超时累计已结算并写入统计的毫秒数（避免重复计入） */
    val overdueRecordedMs: Long = 0L,

    // ============ v2.4.x：跨天统计修复 ============
    /**
     * 当前 [workAccumMs] 这段累计的「墙钟起点」（本段用眼实际开始的时刻）。
     * 用于结算统计时把**跨越 0 点**的时长按天切分到各自日期。
     * -1 表示未知（退化为全部记到结算当天，兼容旧数据）。
     */
    val accumStartWallMs: Long = -1L,

    // ============ v2.4.4：统计去重（修复用眼时长翻倍） ============
    /**
     * 本段累计里**已经写入统计**的毫秒数（已结算基准）。
     *
     * 每次结算只把 `workAccumMs - recordedAccumMs` 的增量写入统计，
     * 记完立刻把本字段抬到 `workAccumMs`。
     */
    val recordedAccumMs: Long = 0L,

    // ============ v2.6.12：统计虚高修复（段序号） ============
    /**
     * 当前累计段的**代际序号**。
     *
     * 为什么需要它：`recordedAccumMs` 只在「同一段」内才有意义。旧实现靠
     * `recordedAccumMs > workAccumMs` 事后猜「是不是换了新段」，但有几条重置路径
     * （`resync` 亮屏分支、`onScreenOn` 短暂息屏续算分支）只归零了 `workAccumMs`
     * 而漏了 `recordedAccumMs`，于是新段被旧基准「压住」→ 增量恒为 0（漏记），
     * 或者反过来整段被重记（虚高 12 倍的根因）。
     *
     * 现在改为：**只要开启新一段（`workAccumMs` 归零重算），段序号 +1，
     * `recordedAccumMs` 一律同步归零**；`recordWorkSession` 只在段序号一致时
     * 才认旧基准，否则视为「本段尚未结算」。
     *
     * 旧数据（该键不存在）读出为 0，与旧行为等价：首次结算会把当前累计全额入账，
     * 之后由段序号保证不再重复。
     */
    val accumGeneration: Long = 0L,

    /**
     * [recordedAccumMs] 这份基准所属的段序号。
     *
     * 「本段已结算」的可信基准；不相等说明累计已开新段，旧基准一律作废。
     *
     */
    val recordedGeneration: Long = 0L
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
    // v2.4.0
    private const val KEY_OVERDUE_COUNTING = "overdue_counting"
    private const val KEY_OVERDUE_RECORDED_MS = "overdue_recorded_ms"
    // v2.4.x
    private const val KEY_ACCUM_START_WALL_MS = "accum_start_wall_ms"
    // v2.4.4
    private const val KEY_RECORDED_ACCUM_MS = "recorded_accum_ms"
    // v2.6.12
    private const val KEY_ACCUM_GENERATION = "accum_generation"
    private const val KEY_RECORDED_GENERATION = "recorded_generation"

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
            tipIndex = p.getInt(KEY_TIP_INDEX, -1),
            overdueCounting = p.getBoolean(KEY_OVERDUE_COUNTING, false),
            overdueRecordedMs = p.getLong(KEY_OVERDUE_RECORDED_MS, 0L),
            accumStartWallMs = p.getLong(KEY_ACCUM_START_WALL_MS, -1L),
            recordedAccumMs = p.getLong(KEY_RECORDED_ACCUM_MS, 0L),
            accumGeneration = p.getLong(KEY_ACCUM_GENERATION, 0L),
            recordedGeneration = p.getLong(KEY_RECORDED_GENERATION, 0L)
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
            .putBoolean(KEY_OVERDUE_COUNTING, state.overdueCounting)
            .putLong(KEY_OVERDUE_RECORDED_MS, state.overdueRecordedMs)
            .putLong(KEY_ACCUM_START_WALL_MS, state.accumStartWallMs)
            .putLong(KEY_RECORDED_ACCUM_MS, state.recordedAccumMs)
            .putLong(KEY_ACCUM_GENERATION, state.accumGeneration)
            .putLong(KEY_RECORDED_GENERATION, state.recordedGeneration)
            .apply()
    }
}