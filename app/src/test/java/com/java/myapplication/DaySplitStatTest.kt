package com.java.myapplication

import com.java.myapplication.data.StatsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar

/**
 * 用眼统计「归属起点日」规纯逻辑测试（v2.4.x 第二轮）。
 *
 * 语义变更：旧实现把跨 0 点的一段时长按比例劈成两半，导致 0 点后
 * 「今日用眼」立刻继承了昨天后半段，看起来像「没有在 0 点归零」。
 *
 * 新口径：[StatsStore.addWorkSession] 把整段时长记到**起点日**，
 * 配合 EyeTimer 的「跨天切段」，使「今日用眼」严格等于当天 0:00 至今。
 *
 * 因此第一轮测试的 [StatsStore.splitSecondsAcrossDays] 已不存在，
 * 本文件改为验证新的 [StatsStore.dayKeyAt] 日期键计算。
 */
class DaySplitStatTest {

    /** 某个墙钟时间所在日的 00:00 时间戳 */
    private fun dayStart(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `同一天内不同时刻的日期键相同`() {
        val day0 = dayStart(System.currentTimeMillis())
        val early = day0 + 60_000L            // 00:01
        val late = day0 + 23 * 3600_000L      // 23:00
        assertEquals(StatsStore.dayKeyAt(early), StatsStore.dayKeyAt(late))
    }

    @Test
    fun `相邻两天跨 0点日期键不同`() {
        val day0 = dayStart(System.currentTimeMillis())
        val before = day0 - 1            // 昨天 23:59:59.999
        val after = day0                 // 今天 00:00
        assertNotEquals(StatsStore.dayKeyAt(before), StatsStore.dayKeyAt(after))
    }

    @Test
    fun `日期键格式为 yyyy-MM-dd`() {
        val key = StatsStore.dayKeyAt(System.currentTimeMillis())
        assertEquals(10, key.length)
        assertEquals('-', key[4])
        assertEquals('-', key[7])
    }

    @Test
    fun `起点日与结束日不同且各自稳定`() {
        // 模拟一段「昨天 23:00 → 今天 01:00」的跨天用眼。
        val today0 = dayStart(System.currentTimeMillis())
        val segStart = today0 - 3600_000L           // 昨天 23:00
        val segEnd = today0 + 3600_000L             // 今天 01:00
        val startKey = StatsStore.dayKeyAt(segStart)
        val endKey = StatsStore.dayKeyAt(segEnd)
        // 段起点属于昨天：整段（按归属起点日口径）应记到昨天而不是今天，
        // 这正是「今天 0 点后今日用眼从 0 起算」的关键前提。
        assertNotEquals(startKey, endKey)
        // 同一时间重复调用结果稳定（不依赖 now()）
        assertEquals(startKey, StatsStore.dayKeyAt(segStart))
    }

    @Test
    fun `0点整点属于新的一天`() {
        val today0 = dayStart(System.currentTimeMillis())
        val justBefore = today0 - 1
        assertNotEquals(StatsStore.dayKeyAt(justBefore), StatsStore.dayKeyAt(today0))
        // 0 点之后任意时刻都属于今天
        assertEquals(StatsStore.dayKeyAt(today0), StatsStore.dayKeyAt(today0 + 5 * 3600_000L))
    }
}
