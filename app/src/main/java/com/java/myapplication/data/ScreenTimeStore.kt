package com.java.myapplication.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * 系统亮屏时长（v2.6.12）。
 *
 * 应用自己统计的「用眼时长」依赖亮/灭屏广播与本进程存活，息屏边界、进程被回收、
 * 悬浮窗是否算在内这些环节都会带进一点偏差。这里直接向系统要
 * SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE 事件，算出来的就是
 * **设备真正亮屏了多久**，作为对照值展示在统计页。
 *
 * 注意语义区别：亮屏时长 >= 用眼时长。用户亮着屏没在用眼、看别的应用、
 * 甚至把手机撂在桌面上，都会被算进亮屏时长。
 *
 * v2.6.16：App 自统计的用眼时长因「息屏广播丢失后 screenOnAt 陈旧」屡修不净
 * （实测系统亮屏 3 分钟 / 自统计 33 分钟），已**整体废止**。统计页展示的
 * 「今日用眼」与报表的日/周/月全部改用本模块的系统亮屏时长。
 * 界面上明确标注口径为「屏幕亮着的时间」，不再暗示它是精确的用眼时长。
 *
 * 需要用户在系统设置里授予「使用情况访问权限」，未授权时相关函数返回 null，
 * 调用方展示占位并提供一键跳转授权。
 */
object ScreenTimeStore {

    /** 「使用情况访问」的系统设置页 */
    fun permissionIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * 是否已授予「使用情况访问权限」。
     *
     * 这是 AppOps 而非运行时权限，没有 checkSelfPermission 可用，只能查 AppOps 的模式位。
     */
    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 今日亮屏总时长（秒）。未授权、或系统没给任何事件时返回 null。
     *
     * 事件成对出现（SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE），逐对累加；
     * 结尾若只有「亮」没有「灭」，说明此刻正亮着，补到当前时刻。
     */
    fun todayScreenOnSeconds(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Long? = screenOnSecondsBetween(context, dayStartMillis(now), now)

    /**
     * 任意区间内的亮屏总时长（秒），用于报表的日/周/月。未授权或无事件返回 null。
     *
     * v2.6.16 新增。区间语义为 [from, to)。事件在区间外开始（跨区间）时，
     * 会把起点夹到 from，避免把区间外的时间算进来。
     */
    fun screenOnSecondsBetween(context: Context, from: Long, to: Long): Long? {
        if (to <= from) return 0L
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return null

        val events = manager.queryEvents(from, to)
        val event = UsageEvents.Event()

        var totalMs = 0L
        var screenOnAt = 0L
        var sawAny = false

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    sawAny = true
                    // v2.6.16：区间内首个「亮」事件；若区间开始时屏幕就已经亮着，
                    // 系统不会补发事件，故下面用 from 作为兜底起点。
                    if (screenOnAt == 0L) screenOnAt = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    sawAny = true
                    if (screenOnAt != 0L) {
                        totalMs += (event.timeStamp - screenOnAt).coerceAtLeast(0L)
                        screenOnAt = 0L
                    }
                }
            }
        }

        if (!sawAny) return null
        if (screenOnAt != 0L) {
            // 区间末尾仍亮着（或查询时正亮）：补到区间终点。
            totalMs += (to - screenOnAt).coerceAtLeast(0L)
        }
        return totalMs / 1000L
    }

    /**
     * 逐日亮屏时长（秒），供报表折线/柱状图使用（v2.6.16 新增）。
     *
     * @param days 需要统计的天数（含今天），从「今天 - (days-1) 天」的 0 点开始
     * @return 与 [days] 等长的列表，索引 0 是最早的一天；某天无数据或未授权时为 null
     */
    fun dailyScreenOnSeconds(
        context: Context,
        days: Int,
        now: Long = System.currentTimeMillis()
    ): List<Long?> {
        if (days <= 0) return emptyList()
        if (!hasPermission(context)) return List(days) { null }
        val result = ArrayList<Long?>(days)
        val cal = Calendar.getInstance()
        cal.timeInMillis = dayStartMillis(now)
        // 回退到最早那天的 0 点
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        for (i in 0 until days) {
            val from = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val to = if (i == days - 1) now else cal.timeInMillis
            result.add(screenOnSecondsBetween(context, from, to))
        }
        return result
    }

    /** 当日 0 点的时间戳 */
    private fun dayStartMillis(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
