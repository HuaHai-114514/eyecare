package com.java.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 单日聚合统计（v2.3 新增，用于日/周/月报告）。
 *
 * @param dayKey      日期键，格式 yyyy-MM-dd（本地时区）
 * @param workSeconds 当日累计亮屏用眼时长（秒）
 * @param restCount   当日完成休息次数
 * @param restSeconds 当日累计休息时长（秒）
 * @param longestStreakSeconds 当日最长连续用眼时长（秒）
 */
data class DailyStat(
    val dayKey: String,
    val workSeconds: Int = 0,
    val restCount: Int = 0,
    val restSeconds: Int = 0,
    val longestStreakSeconds: Int = 0
)

/** 单次休息记录，沿用旧结构用于兼容已落盘数据 */
data class RestRecord(
    val timestamp: Long,
    val restSeconds: Int
)

/**
 * 统计存储（v2.3）。
 *
 * 存储演进说明：
 * - v2.2 及以前只有 `eye_care_stats` 里的 `records`（一串休息记录）；
 * - v2.3 新增 `eye_care_stats_v2` 的 `daily`（按天聚合，含用眼时长）；
 * - **旧键完整保留不删**：首次读取时若发现 v2 为空而旧键有数据，
 *   会把旧休息记录回填进 v2 的对应日期（用眼时长为 0，因为历史无法追溯）。
 *   这样升级后老用户的休息历史不会凭空消失。
 *
 * 数据保留：只保留最近 [KEEP_DAYS] 天，避免 SharedPreferences 无限膨胀。
 */
object StatsStore {

    // ---- 旧键（兼容用，只读不删） ----
    private const val PREFS_LEGACY = "eye_care_stats"
    private const val KEY_RECORDS = "records"

    // ---- 新键（v2.3 主存储） ----
    private const val PREFS = "eye_care_stats_v2"
    private const val KEY_DAILY = "daily"
    private const val KEY_MIGRATED = "migrated_from_v1"

    /** 保留最近多少天 */
    private const val KEEP_DAYS = 90
    /** 保留上限条目数 */
    private const val MAX_ENTRIES = 120

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun legacyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_LEGACY, Context.MODE_PRIVATE)

    /**
     * 进程内缓存（v2.3.6 新增）。
     *
     * 界面每秒刷新会连续调用多个读取接口，每次都反序列化整份 JSON 过于浪费。
     *
     * 一致性约定：
     * - **所有落盘都必须经过 [saveAll]**，由它在末尾统一刷新本缓存（唯一刷新点）；
     * - [loadAll] 只返回副本，调用方修改不会污染缓存；
     * - 数据损坏时**不写入缓存**，下次读取仍会重新尝试。
     */
    @Volatile
    private var cache: MutableMap<String, DailyStat>? = null

    // ==================== 日期工具 ====================

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 当天 00:00:00.000 的时间戳 */
    private fun dayStart(time: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = time
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dayKeyOf(time: Long): String = dayFmt.format(Date(dayStart(time)))

    /** 当天已过去的分钟数（0..1439），用于免打扰时段判断 */
    fun minuteOfDay(time: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = time
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    // ==================== 读写 ====================

    /** 读取全部按天聚合数据（按日期升序） */
    private fun loadAll(context: Context): MutableMap<String, DailyStat> {
        // 命中缓存：直接返回副本
        cache?.let { return it.toMutableMap() }

        migrateIfNeeded(context)
        // 迁移过程可能已经填充了缓存
        cache?.let { return it.toMutableMap() }

        val json = prefs(context).getString(KEY_DAILY, null) ?: run {
            val empty = mutableMapOf<String, DailyStat>()
            cache = empty
            return empty.toMutableMap()
        }
        val map = mutableMapOf<String, DailyStat>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.optString("d")
                if (key.isEmpty()) continue
                map[key] = DailyStat(
                    dayKey = key,
                    workSeconds = o.optInt("w", 0),
                    restCount = o.optInt("rc", 0),
                    restSeconds = o.optInt("rs", 0),
                    longestStreakSeconds = o.optInt("ls", 0)
                )
            }
        } catch (_: Exception) {
            // 数据损坏时不崩，退化为空统计（且不写缓存，下次仍会重新读取）
            return mutableMapOf()
        }
        cache = map
        return map.toMutableMap()
    }

    private fun saveAll(context: Context, map: Map<String, DailyStat>) {
        // 裁剪：按日期键排序后只留最近 KEEP_DAYS 天 / MAX_ENTRIES 条
        val sorted = map.values.sortedBy { it.dayKey }
        val cutoffKey = dayKeyOf(System.currentTimeMillis() - (KEEP_DAYS - 1).toLong() * 24 * 3600 * 1000)
        val kept = sorted
            .filter { it.dayKey >= cutoffKey }
            .takeLast(MAX_ENTRIES)

        val arr = JSONArray()
        kept.forEach {
            val o = JSONObject()
            o.put("d", it.dayKey)
            o.put("w", it.workSeconds)
            o.put("rc", it.restCount)
            o.put("rs", it.restSeconds)
            o.put("ls", it.longestStreakSeconds)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_DAILY, arr.toString()).apply()
        // 落盘即刷新缓存：这是整份统计唯一的缓存刷新点
        cache = kept.associateBy { it.dayKey }.toMutableMap()
    }

    /** 取某天记录，不存在则创建空记录 */
    private fun ensureDay(map: MutableMap<String, DailyStat>, key: String): DailyStat =
        map[key] ?: DailyStat(dayKey = key)

    /**
     * 老数据迁移：v2 为空且从未迁移过时，
     * 把旧 `records` 里的休息记录回填到对应日期的 restCount / restSeconds。
     */
    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return

        val legacyJson = legacyPrefs(context).getString(KEY_RECORDS, null)
        if (legacyJson.isNullOrEmpty()) {
            p.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }

        try {
            val arr = JSONArray(legacyJson)
            val map = mutableMapOf<String, DailyStat>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ts = o.optLong("t", 0L)
                val sec = o.optInt("s", 0)
                if (ts <= 0L) continue
                val key = dayKeyOf(ts)
                val cur = ensureDay(map, key)
                map[key] = cur.copy(
                    restCount = cur.restCount + 1,
                    restSeconds = cur.restSeconds + sec
                )
            }
            saveAll(context, map)
        } catch (_: Exception) {
            // 迁移失败不阻断启动，下次仍会重试
            return
        }
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    // ==================== 写入接口 ====================

    /**
     * 结算一次「已完成的用眼时长」。
     * 由 EyeTimer 在各出口调用（到点、稍后再说、结束休息等）。
     *
     * @param workSeconds 本次结算的用眼秒数
     */
    fun addWorkSession(context: Context, workSeconds: Int) {
        if (workSeconds <= 0) return
        val map = loadAll(context)
        val key = dayKeyOf(System.currentTimeMillis())
        val cur = ensureDay(map, key)
        map[key] = cur.copy(
            workSeconds = cur.workSeconds + workSeconds,
            longestStreakSeconds = maxOf(cur.longestStreakSeconds, workSeconds)
        )
        saveAll(context, map)
    }

    /** 记录一次完成的休息 */
    fun addRest(context: Context, restSeconds: Int) {
        val map = loadAll(context)
        val key = dayKeyOf(System.currentTimeMillis())
        val cur = ensureDay(map, key)
        map[key] = cur.copy(
            restCount = cur.restCount + 1,
            restSeconds = cur.restSeconds + restSeconds.coerceAtLeast(0)
        )
        saveAll(context, map)
    }

    // ==================== 读取接口 ====================

    /** 今日用眼总时长（秒） */
    fun todayWorkSeconds(context: Context): Int =
        loadAll(context)[dayKeyOf(System.currentTimeMillis())]?.workSeconds ?: 0

    /** 今日休息次数 */
    fun todayRestCount(context: Context): Int =
        loadAll(context)[dayKeyOf(System.currentTimeMillis())]?.restCount ?: 0

    /** 今日累计休息时长（秒） */
    fun todayRestSeconds(context: Context): Int =
        loadAll(context)[dayKeyOf(System.currentTimeMillis())]?.restSeconds ?: 0

    /** 今日最长连续用眼（秒） */
    fun todayLongestStreak(context: Context): Int =
        loadAll(context)[dayKeyOf(System.currentTimeMillis())]?.longestStreakSeconds ?: 0

    /** 近 N 天序列（升序，缺失日自动补 0），用于日/周/月报告 */
    fun dailySeries(context: Context, days: Int): List<DailyStat> {
        val map = loadAll(context)
        val result = mutableListOf<DailyStat>()
        val base = dayStart(System.currentTimeMillis())
        for (i in days - 1 downTo 0) {
            val ts = base - i.toLong() * 24 * 3600 * 1000
            val key = dayKeyOf(ts)
            result.add(map[key] ?: DailyStat(dayKey = key))
        }
        return result
    }

    /**
     * 达标率：近 N 天中「用眼时长不超过目标」的天数占比。
     * 无任何用眼记录的日子不计入分母（避免刚装应用就被算作不达标）。
     *
     * @return 0f..1f，无有效天数时返回 -1f 表示「暂无数据」
     */
    fun achievementRate(context: Context, days: Int, dailyGoalMinutes: Int): Float {
        val series = dailySeries(context, days).filter { it.workSeconds > 0 }
        if (series.isEmpty()) return -1f
        val goalSec = dailyGoalMinutes * 60
        val ok = series.count { it.workSeconds <= goalSec }
        return ok.toFloat() / series.size
    }

    /**
     * 与上一个等长周期相比的变化率。
     * @return 变化百分比（如 0.25f 表示增加 25%）；上一周期为 0 时返回 -1f 表示无法比较
     */
    fun workTrendPercent(context: Context, days: Int): Float {
        val cur = dailySeries(context, days).sumOf { it.workSeconds }
        // 上一个等长周期：向前再取 days 天
        val map = loadAll(context)
        val base = dayStart(System.currentTimeMillis()) - days.toLong() * 24 * 3600 * 1000
        var prev = 0
        for (i in 0 until days) {
            val ts = base - i.toLong() * 24 * 3600 * 1000
            prev += map[dayKeyOf(ts)]?.workSeconds ?: 0
        }
        if (prev <= 0) return -1f
        return (cur - prev).toFloat() / prev
    }
}