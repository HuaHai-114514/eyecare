package com.java.myapplication

import com.java.myapplication.data.TimerState
import com.java.myapplication.timer.EyeTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 超时统计纯逻辑测试（v2.4.0 新增）。
 *
 * 验证“到点未休息的那段时长也会计入用眼统计”这一修复的核心算术。
 * 不需要 Context / 真机，`./gradlew testDebugUnitTest` 即可跑。
 */
class OverdueStatTest {

    private val minute = 60_000L

    // ---------------- accumulatedAt ----------------

    @Test
    fun `亮屏中累计等于已落盘加上本次亮屏已过时长`() {
        val s = TimerState(workAccumMs = 10_000L, screenOnAt = 1_000_000L)
        // at - screenOnAt = 5_000
        assertEquals(15_000L, EyeTimer.accumulatedAt(s, 1_005_000L))
    }

    @Test
    fun `已冻结累计不再随当前时间增长`() {
        val s = TimerState(workAccumMs = 42_000L, screenOnAt = -1L)
        assertEquals(42_000L, EyeTimer.accumulatedAt(s, 9_999_999L))
    }

    @Test
    fun `亮屏时间戳在未来时不会倒扣`() {
        val s = TimerState(workAccumMs = 10_000L, screenOnAt = 2_000_000L)
        // at 早于 screenOnAt：coerceAtLeast(0) 兜底，仍取已落盘值
        assertEquals(10_000L, EyeTimer.accumulatedAt(s, 1_000_000L))
    }

    // ---------------- overdueDeltaSeconds ----------------

    @Test
    fun `未到点时超时增量为零`() {
        // 目标 20 分钟，仅累计 10 分钟，此前也没记过
        assertEquals(0, EyeTimer.overdueDeltaSeconds(10 * minute, 20 * minute, 0L))
    }

    @Test
    fun `到点未休息的时长计入统计`() {
        // 核心场景：目标 20 分钟，已累计 25 分钟（超时 5 分钟），此前未记
        assertEquals(300, EyeTimer.overdueDeltaSeconds(25 * minute, 20 * minute, 0L))
    }

    @Test
    fun `已结算部分会去重不重复计入`() {
        // 总超时 5 分钟，此前已结算 3 分钟，只应再补 2 分钟
        assertEquals(120, EyeTimer.overdueDeltaSeconds(25 * minute, 20 * minute, 3 * minute))
    }

    @Test
    fun `已全部结算后增量为零`() {
        assertEquals(0, EyeTimer.overdueDeltaSeconds(25 * minute, 20 * minute, 5 * minute))
    }

    @Test
    fun `已结算值大于当前超时时不出现负数`() {
        // 理论上不应发生（息屏冻结后累计回退），但要兼容：返回 0 而不是负数
        assertEquals(0, EyeTimer.overdueDeltaSeconds(21 * minute, 20 * minute, 10 * minute))
    }

    @Test
    fun `超时不足一秒向下取整为零`() {
        // 目标 20 分钟，累计 20 分钟又 900ms
        assertEquals(0, EyeTimer.overdueDeltaSeconds(20 * minute + 900L, 20 * minute, 0L))
    }

    @Test
    fun `超时包含多轮提醒连续累计`() {
        // 模拟：到点后每 2 分钟结算一次，第二次结算的增量
        val target = 20 * minute
        val firstRecorded = 2 * minute   // 第一次已记 2 分钟
        // 现在已累计 24 分钟（超时 4 分钟），应再补 2 分钟 = 120 秒
        assertEquals(120, EyeTimer.overdueDeltaSeconds(24 * minute, target, firstRecorded))
    }
}
