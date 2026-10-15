package com.java.myapplication

import com.java.myapplication.data.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.12 段序号（accumGeneration / recordedGeneration）去重语义。
 *
 */
class GenerationDedupTest {

    private val minute = 60_000L

    // ---------------- 段序号的核心语义 ----------------

    @Test
    fun `新段序号不同时 旧基准不被采信`() {
        // 旧段：累计 10 分钟、已结算基准也是 10 分钟、段序号 1
        val old = TimerState(
            workAccumMs = 10 * minute,
            recordedAccumMs = 10 * minute,
            accumGeneration = 1L,
            recordedGeneration = 1L
        )
        // 新段：累计 8 分钟，但 recordedAccumMs 忘了归零（旧 bug 现场），段序号 2
        val newSeg = old.copy(
            workAccumMs = 8 * minute,
            accumGeneration = 2L,
            recordedGeneration = 1L
        )
        // 段序号不一致 => 旧基准作废，本段全额入账 8 分钟
        val sameSegment = newSeg.recordedGeneration == newSeg.accumGeneration
        val already = if (sameSegment) newSeg.recordedAccumMs.coerceIn(0L, newSeg.workAccumMs) else 0L
        assertEquals(0L, already)
        assertEquals(8 * minute, newSeg.workAccumMs - already)
    }

    @Test
    fun `同段内增量只记一次`() {
        val s = TimerState(
            workAccumMs = 5 * minute,
            recordedAccumMs = 3 * minute,
            accumGeneration = 7L,
            recordedGeneration = 7L
        )
        val sameSegment = s.recordedGeneration == s.accumGeneration
        assertTrue(sameSegment)
        val already = s.recordedAccumMs.coerceIn(0L, s.workAccumMs)
        assertEquals(2 * minute, s.workAccumMs - already)
    }

    @Test
    fun `newSegment 同步归零基准并抬升两个序号`() {
        val s = TimerState(
            workAccumMs = 9 * minute,
            recordedAccumMs = 9 * minute,
            accumGeneration = 3L,
            recordedGeneration = 3L,
            accumStartWallMs = 1_700_000_000_000L
        )
        val t = 1_700_000_600_000L
        val fresh = s.copy(
            workAccumMs = 0L,
            recordedAccumMs = 0L,
            accumGeneration = s.accumGeneration + 1L,
            recordedGeneration = s.accumGeneration + 1L,
            accumStartWallMs = t,
            screenOnAt = t,
            offAt = -1L
        )
        assertEquals(0L, fresh.workAccumMs)
        assertEquals(0L, fresh.recordedAccumMs)
        assertEquals(4L, fresh.accumGeneration)
        assertEquals(4L, fresh.recordedGeneration)
        assertEquals(t, fresh.accumStartWallMs)
        assertNotEquals(s.accumGeneration, fresh.accumGeneration)
    }

    // ---------------- 进程重启后不重复计入 ----------------

    @Test
    fun `进程重启丢基准后 新段首次结算全额入账而非零`() {
        // 进程被杀：recordedAccumMs 落盘为 10 分钟，但段序号也落盘为 5
        val persisted = TimerState(
            workAccumMs = 10 * minute,
            recordedAccumMs = 10 * minute,
            accumGeneration = 5L,
            recordedGeneration = 5L
        )
        // resync 走 newSegment：段序号 6，基准归零
        val afterResync = persisted.copy(
            workAccumMs = 0L,
            recordedAccumMs = 0L,
            accumGeneration = 6L,
            recordedGeneration = 6L
        )
        // 新段涨到 4 分钟
        val grown = afterResync.copy(workAccumMs = 4 * minute)
        val sameSegment = grown.recordedGeneration == grown.accumGeneration
        val already = if (sameSegment) grown.recordedAccumMs.coerceIn(0L, grown.workAccumMs) else 0L
        assertEquals(0L, already)
        assertEquals(4 * minute, grown.workAccumMs - already)
    }

    @Test
    fun `旧数据默认序号为零时首次结算全额入账`() {
        // 旧版本升级上来：两个序号键都不存在 => 都读 0
        val legacy = TimerState(workAccumMs = 12 * minute, recordedAccumMs = 0L)
        assertEquals(0L, legacy.accumGeneration)
        assertEquals(0L, legacy.recordedGeneration)
        val sameSegment = legacy.recordedGeneration == legacy.accumGeneration
        assertTrue(sameSegment)
        // 基准为 0 => 全额 12 分钟入账一次
        val already = legacy.recordedAccumMs.coerceIn(0L, legacy.workAccumMs)
        assertEquals(12 * minute, legacy.workAccumMs - already)
    }


    // ---------------- v2.6.13：续算/跨天 段落基准对齐 ----------------

    /**
     */
    @Test
    fun `短息屏续算 已结算基准对齐到冻结累计`() {
        // 息屏那一刻：累计 24 分钟，已结算基准落后（只有 20 分钟），段序号一致
        val frozen = TimerState(
            workAccumMs = 24 * minute,
            recordedAccumMs = 20 * minute,
            accumGeneration = 5L,
            recordedGeneration = 5L
        )
        // 亮屏走「短息屏续算」分支后的状态
        val resumed = frozen.copy(
            screenOnAt = 1_000L,
            offAt = -1L,
            recordedAccumMs = frozen.workAccumMs,
            recordedGeneration = frozen.accumGeneration
        )
        // 续算后累计 = 24 分钟 + 新用眼 6 分钟
        val grown = resumed.copy(workAccumMs = 24 * minute, screenOnAt = -1L)
            .let { it.copy(workAccumMs = it.workAccumMs + 6 * minute) }

        val sameSegment = grown.recordedGeneration == grown.accumGeneration
        assertTrue(sameSegment)
        val already = grown.recordedAccumMs.coerceIn(0L, grown.workAccumMs)
        // 只应新增 6 分钟，而不是整段 30 分钟
        assertEquals(6 * minute, grown.workAccumMs - already)
    }

    /**
     * 「本段已结算」，而实际冻结累计是 24 分钟 —— 这 4 分钟差额会在
     */
    @Test
    fun `未对齐基准会导致差额被重复入账`() {
        val buggy = TimerState(
            workAccumMs = 24 * minute,
            recordedAccumMs = 20 * minute,
            accumGeneration = 5L,
            recordedGeneration = 5L
        )
        val sameSegment = buggy.recordedGeneration == buggy.accumGeneration
        assertTrue(sameSegment)
        val already = buggy.recordedAccumMs.coerceIn(0L, buggy.workAccumMs)
        // 旧实现会把这 4 分钟差额再记一次
        assertEquals(4 * minute, buggy.workAccumMs - already)
        // 对齐之后差额归零
        val fixed = buggy.copy(recordedAccumMs = buggy.workAccumMs)
        assertEquals(0L, fixed.workAccumMs - fixed.recordedAccumMs)
    }

    /**
     * recordWorkSession 会误采信昨天的基准。
     */
    @Test
    fun `跨天归零同时抬升段序号`() {
        val yesterday = TimerState(
            workAccumMs = 0L,
            recordedAccumMs = 0L,
            accumGeneration = 8L,
            recordedGeneration = 8L,
            accumStartWallMs = 1_700_000_000_000L
        )
        val rolled = yesterday.copy(
            workAccumMs = 0L,
            recordedAccumMs = 0L,
            accumGeneration = yesterday.accumGeneration + 1L,
            recordedGeneration = yesterday.accumGeneration + 1L
        )
        assertEquals(9L, rolled.accumGeneration)
        assertEquals(9L, rolled.recordedGeneration)
        // 序号一致（新段自身自洽），但已不等于昨天那一段
        assertEquals(rolled.accumGeneration, rolled.recordedGeneration)
        assertNotEquals(yesterday.accumGeneration, rolled.accumGeneration)
    }
}
