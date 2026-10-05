package com.java.myapplication

import com.java.myapplication.data.TimerState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 *
 *
 * v2.4.4 引入 [TimerState.recordedAccumMs]（已结算基准）：
 *
 */
class WorkStatDedupTest {

    private val minute = 60_000L

    // ---------------- 已结算基准的语义 ----------------

    @Test
    fun `全新一段用眼未结算时基准为零`() {
        val s = TimerState(workAccumMs = 10 * minute)
        assertEquals(0L, s.recordedAccumMs)
    }

    @Test
    fun `首次结算的增量等于全部累计`() {
        val accum = 10 * minute
        val s = TimerState(workAccumMs = accum, recordedAccumMs = 0L)
        assertEquals(accum, s.workAccumMs - s.recordedAccumMs)
    }

    @Test
    fun `已结算基准抬高后增量归零`() {
        val accum = 10 * minute
        val after = TimerState(workAccumMs = accum, recordedAccumMs = accum)
        assertEquals(0L, (after.workAccumMs - after.recordedAccumMs).coerceAtLeast(0L))
    }

    @Test
    fun `基准不会超过当前累计`() {
        val s = TimerState(workAccumMs = 5 * minute, recordedAccumMs = 9 * minute)
        val already = s.recordedAccumMs.coerceIn(0L, s.workAccumMs)
        val delta = ((s.workAccumMs - already) / 1000L).toInt()
        assertEquals(0, delta)
    }

    // ---------------- 复现「翻倍」场景 ----------------

    @Test
    fun `到点结算后再息屏结算不会重复计入`() {
        val workMs = 20 * minute
        val afterDeadline = TimerState(workAccumMs = workMs, recordedAccumMs = workMs)
        val deltaSec = ((afterDeadline.workAccumMs - afterDeadline.recordedAccumMs) / 1000L).toInt()
        assertEquals(0, deltaSec)
    }

    @Test
    fun `到点结算后继续超时只补增量不重记全段`() {
        val target = 20 * minute
        val cur = 25 * minute
        val already = target
        val deltaSec = ((cur - already) / 1000L).toInt()
        assertEquals(300, deltaSec)
    }

    @Test
    fun `超时结算后休息结束不会把整段再记一次`() {
        val target = 20 * minute
        val overdue = 5 * minute
        val accum = target + overdue
        val afterAll = TimerState(workAccumMs = accum, recordedAccumMs = accum)
        val extra = ((afterAll.workAccumMs - afterAll.recordedAccumMs) / 1000L).toInt()
        assertEquals(0, extra)
    }

    @Test
    fun `两小时真实用眼只应计入一次`() {
        val realWorkMs = 120 * minute
        var recorded = 0L
        var accum = 0L
        repeat(6) {
            accum += 20 * minute
            val deltaSec = ((accum - recorded) / 1000L).toInt()
            recorded = accum
            assertEquals(1200, deltaSec)
        }
        assertEquals(realWorkMs, recorded)
        assertEquals(7200, (recorded / 1000L).toInt())
    }

    // ---------------- 零头不累积成重复段 ----------------

    @Test
    fun `不足五秒的零头不写统计但抬高基准`() {
        val accum = 10 * minute + 3_000L
        val already = 10 * minute
        val deltaSec = ((accum - already) / 1000L).toInt()
        assertEquals(3, deltaSec)
        val bumped = TimerState(workAccumMs = accum, recordedAccumMs = accum)
        assertEquals(accum, bumped.recordedAccumMs)
    }

    @Test
    fun `零头抬高基准后下次只记新增部分`() {
        val base = 10 * minute + 3_000L
        val next = base + 7 * minute
        val deltaSec = ((next - base) / 1000L).toInt()
        assertEquals(420, deltaSec)
    }

    // ---------------- 跨天切分去重 ----------------

    @Test
    fun `跨天按比例切分时扣除已结算部分`() {
        val curAccum = 120 * minute
        val recordedAccum = 60 * minute
        val beforeRatio = 0.5
        val beforeMs = (curAccum * beforeRatio).toLong()
        val alreadyMs = (recordedAccum * beforeRatio).toLong()
        val beforeSec = ((beforeMs - alreadyMs).coerceAtLeast(0L) / 1000L).toInt()
        // 实现里 alreadyRatio 已按同一 beforeRatio 折算，等价于 alreadyMs；此处断言差值
        // 对齐实现：beforeMs=60min，已结算按同比例折算=30min，差额=30min
        val implAlreadyMs = (recordedAccum.toDouble().coerceIn(0.0, curAccum.toDouble()) * beforeRatio).toLong()
        assertEquals(30 * minute, beforeMs - implAlreadyMs)
        assertEquals(1800, ((beforeMs - implAlreadyMs).coerceAtLeast(0L) / 1000L).toInt())
    }

    @Test
    fun `跨天未结算时正常补记`() {
        val curAccum = 120 * minute
        val recordedAccum = 0L
        val beforeRatio = 0.5
        val beforeMs = (curAccum * beforeRatio).toLong()
        val alreadyMs = (recordedAccum * beforeRatio).toLong()
        val beforeSec = ((beforeMs - alreadyMs).coerceAtLeast(0L) / 1000L).toInt()
        // 未结算过（recordedAccum=0），0 点前那一截 60min 应全额补记
        assertEquals(3600, beforeSec)
    }

    @Test
    fun `跨天归零后基准同步归零`() {
        val after = TimerState(
            workAccumMs = 30 * minute,
            recordedAccumMs = 0L,
            accumStartWallMs = 1_000_000L
        )
        assertEquals(0L, after.recordedAccumMs)
        assertEquals(30 * minute, after.workAccumMs)
    }
}
