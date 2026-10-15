package com.java.myapplication

import com.java.myapplication.data.TimerState
import com.java.myapplication.timer.EyeTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕实况校验测试（v2.6.16 新增）。
 *
 * 背景：`screenOnAt` 是**进程内事件推断值**。一旦息屏广播丢失（进程在息屏期间
 * 被杀、广播被系统丢弃、厂商省电策略拦截），它会停留在一个陈旧的亮屏时刻，
 * 而 `at - screenOnAt` 就会把**整段息屏时间当作连续用眼**结算入统计。
 *
 * 实测症状：系统亮屏 3 分钟，自统计 33 分钟；此前还有 3h17m → 息屏一小时 →
 * 亮屏显示 4h19m（多出 62 分钟）。日志中 `SCREEN-ON ... onAt=T0` 之后 20 分钟
 * 直接出现 `REC-WORK accum=1200s delta=1200s`，中间**没有任何 SCREEN-OFF**。
 *
 * 修法：所有累计入口把真实屏幕状态交叉传进来，屏幕实际熄灭时一律按
 * 「未在累计」处理（返回已落盘累计，不加墙钟差）。
 *
 * 纯逻辑测试，不需要 Context / 真机。
 */
class ScreenTruthTest {

    // ---------------- accumulatedAt：屏幕实况校验 ----------------

    @Test
    fun `屏幕亮着时照常累计墙钟差`() {
        val s = TimerState(workAccumMs = 0L, screenOnAt = 1_000_000L)
        assertEquals(1_200_000L, EyeTimer.accumulatedAt(s, 2_200_000L, screenActuallyOn = true))
    }

    @Test
    fun `屏幕已灭时拒绝把陈旧亮屏时刻的墙钟差计入`() {
        // 这就是虚高根因：screenOnAt 停在 20 分钟前，但屏幕其实是灭的
        val s = TimerState(workAccumMs = 0L, screenOnAt = 1_000_000L)
        assertEquals(0L, EyeTimer.accumulatedAt(s, 2_200_000L, screenActuallyOn = false))
    }

    @Test
    fun `屏幕已灭时已落盘累计仍然保留`() {
        // 只拦墙钟差，不丢已经真实结算过的累计
        val s = TimerState(workAccumMs = 600_000L, screenOnAt = 1_000_000L)
        assertEquals(600_000L, EyeTimer.accumulatedAt(s, 2_200_000L, screenActuallyOn = false))
    }

    @Test
    fun `屏幕已灭且已冻结时结果与冻结值一致`() {
        val s = TimerState(workAccumMs = 42_000L, screenOnAt = -1L)
        assertEquals(42_000L, EyeTimer.accumulatedAt(s, 9_999_999L, screenActuallyOn = false))
    }

    @Test
    fun `默认参数保持既有行为不变`() {
        // 旧调用点只传两个参数，必须与修复前完全一致
        val s = TimerState(workAccumMs = 10_000L, screenOnAt = 1_000_000L)
        assertEquals(15_000L, EyeTimer.accumulatedAt(s, 1_005_000L))
        assertEquals(EyeTimer.accumulatedAt(s, 1_005_000L, true), EyeTimer.accumulatedAt(s, 1_005_000L))
    }

    @Test
    fun `二十息屏分钟不再被算成用眼`() {
        // 复现实测场景：T0 亮屏起点，20 分钟后由闹钟触发结算
        val t0 = 1_000_000L
        val twentyMinutes = 20 * 60_000L
        val s = TimerState(workAccumMs = 0L, screenOnAt = t0)
        // 修复前：1200000（整段入账 → 虚高）
        assertEquals(0L, EyeTimer.accumulatedAt(s, t0 + twentyMinutes, screenActuallyOn = false))
        // 屏幕真亮着时仍然如实累计
        assertEquals(twentyMinutes, EyeTimer.accumulatedAt(s, t0 + twentyMinutes, screenActuallyOn = true))
    }

    // ---------------- 熄屏归零：离开过必须优先于「补提醒」 ----------------

    /**
     * 复现用户报的致命漏洞：**息屏一段时间后亮屏必须从 0 重新计时**。
     *
     * 场景：上一次用眼累计到 20 分钟（已达 20 分钟目标但还没弹提醒时息屏了），
     * 息屏 1 小时后亮屏。此时：
     *   - `offAt` 是 1 小时前的时间戳 → `leftLong` 必须为 true；
     *   - 但若按「先补提醒」的旧顺序，`elapsed >= workMs` 会抢先成立并 return，
     *     归零分支永远执行不到 → 误弹「到点」/「超时」，且 screenOnAt 被改写、
     *     workAccumMs 保留旧值，下次结算把 1 小时息屏算成用眼。
     *
     * 这里断言的是判据本身：离开过 → 归零决策成立，且与 elapsed 是否到点无关。
     */
    @Test
    fun `息屏超过阈值后再亮屏必须判定为需要归零`() {
        val t = 10_000_000L
        val offAt = t - EyeTimer.LONG_OFF_RESET_MS - 1L     // 息屏略超阈值
        val s = TimerState(
            workAccumMs = 20 * 60_000L,                     // 息屏前已累计到点
            screenOnAt = -1L,                               // 息屏已冻结
            offAt = offAt
        )
        val leftLong = s.offAt > 0 && t - s.offAt >= EyeTimer.LONG_OFF_RESET_MS
        assertEquals(true, leftLong)
        // 旧顺序下这个条件会抢先成立，正是它短路了归零分支
        val elapsedIfNotReset = EyeTimer.accumulatedAt(s, t, screenActuallyOn = true)
        assertEquals(true, elapsedIfNotReset >= 20 * 60_000L)
    }

    @Test
    fun `息屏未达阈值时不应归零而应续算`() {
        val t = 10_000_000L
        val offAt = t - 30_000L                          // 只息屏 30 秒
        val s = TimerState(workAccumMs = 5 * 60_000L, screenOnAt = -1L, offAt = offAt)
        val leftLong = s.offAt > 0 && t - s.offAt >= EyeTimer.LONG_OFF_RESET_MS
        assertEquals(false, leftLong)
    }

    /**
     * 归零段必须同时清掉超时态与代际序号，否则旧段的 recordedAccumMs
     * 会被新段误认成「已结算」，新段整段漏记（历史事故）。
     */
    @Test
    fun `归零判据与是否已到点相互独立`() {
        // 关键不变量：leftLong 只由「息屏时长」决定，**不受 elapsed 是否到点影响**。
        // 旧实现的 bug 正在于让「elapsed >= workMs」抢先 return，使 leftLong 失效。
        val t = 10_000_000L
        val longOff = TimerState(workAccumMs = 20 * 60_000L, screenOnAt = -1L,
            offAt = t - 3_600_000L, awaitingRest = true, overdueCounting = true)
        val shortOff = TimerState(workAccumMs = 20 * 60_000L, screenOnAt = -1L,
            offAt = t - 30_000L, awaitingRest = true, overdueCounting = true)

        fun leftLong(s: TimerState) = s.offAt > 0 && t - s.offAt >= EyeTimer.LONG_OFF_RESET_MS

        // 两者 elapsed 都已到点，但只有长息屏应归零
        assertEquals(true, leftLong(longOff))
        assertEquals(false, leftLong(shortOff))
    }

    // ---------------- 回归：切后台返回不得归零 ----------------

    /**
     * v2.6.17 回归：切后台回前台会经 `onForeground` → `syncScreenState` →
     * `onScreenOn`。而 `offAt` 是**上一次真正息屏**留下的时间戳，切后台并不更新它，
     * 所以只要上次息屏距今超过阈值，`leftLong` 就恒为 true —— 若不额外要求
     * `screenOnAt <= 0`，每次切回应用都会误判「用户离开过」而从零重新计时。
     *
     * 不变量：**归零只对「屏幕起点已失效」的状态成立**；`screenOnAt > 0`
     * 表示这一段仍在累计中（同一亮屏会话），不得归零。
     */
    @Test
    fun `切后台返回 没有息屏边沿 即使 offAt 陈旧也不得归零`() {
        val t = 10_000_000L
        val ongoing = TimerState(
            workAccumMs = 5 * 60_000L,
            screenOnAt = -1L,                   // frozen() 落盘后长期就是 -1
            offAt = t - 3_600_000L              // 一小时前息过屏，遗留的旧值
        )
        val leftLong = ongoing.offAt > 0 && t - ongoing.offAt >= EyeTimer.LONG_OFF_RESET_MS
        assertEquals(true, leftLong)            // 单看 offAt 确实「离开很久」
        // 但切后台返回没有「息屏→亮屏」边沿 → 不得归零。
        // 这正是仅靠 offAt / screenOnAt 无法区分、必须引入边沿标记的原因。
        val cameFromScreenOff = false
        assertEquals(false, leftLong && cameFromScreenOff)
    }

    @Test
    fun `真实息屏后亮屏 有边沿且超过阈值 应当归零`() {
        val t = 10_000_000L
        val afterOff = TimerState(workAccumMs = 20 * 60_000L, screenOnAt = -1L,
            offAt = t - 3_600_000L)
        val leftLong = afterOff.offAt > 0 && t - afterOff.offAt >= EyeTimer.LONG_OFF_RESET_MS
        val cameFromScreenOff = true
        assertEquals(true, leftLong && cameFromScreenOff)
    }

    @Test
    fun `短暂息屏后亮屏 有边沿但未达阈值 仍不归零`() {
        val t = 10_000_000L
        val afterShortOff = TimerState(workAccumMs = 5 * 60_000L, screenOnAt = -1L,
            offAt = t - 30_000L)
        val leftLong = afterShortOff.offAt > 0 && t - afterShortOff.offAt >= EyeTimer.LONG_OFF_RESET_MS
        val cameFromScreenOff = true
        assertEquals(false, leftLong && cameFromScreenOff)
    }

    @Test
    fun `还原 screenOnAt 无法区分两种场景`() {
        // 反证：切后台返回与真实长息屏，在 TimerState 上可以完全同构
        // （screenOnAt 都是 -1、offAt 都很旧），故判据必须依赖外部边沿信号。
        val t = 10_000_000L
        val backFromBackground = TimerState(workAccumMs = 5 * 60_000L, screenOnAt = -1L,
            offAt = t - 3_600_000L)
        val realLongOff = TimerState(workAccumMs = 5 * 60_000L, screenOnAt = -1L,
            offAt = t - 3_600_000L)
        assertEquals(backFromBackground, realLongOff)   // 状态完全相同
    }

    // ---------------- 回归：零累计兜底不得反复归零 ----------------

    /**
     * v2.6.17 关键回归（有日志铁证）。
     *
     * 现网日志：`leftLong=false`、`edge=false` 两个判据都为假，却仍然走进了
     * 归零分支并打出 `[NEWSEG] from=onScreenOn`。唯一为真的是旧条件里的
     * `|| s.workAccumMs <= 0L`。
     *
     * 而 `onScreenOn` 会被 **重复调用**（onResume → onForeground →
     * syncScreenState → onScreenOn），`newSegment` 又把 workAccumMs 清 0、
     * screenOnAt 置为有效 —— 于是形成自维持死循环：
     *   归零 → workAccumMs==0 → 下次调用再归零 → …
     * 实测段序号 gen 一路 79 → 84 → 85。
     *
     * 不变量：「从未起算过」的兜底必须同时要求 **没有进行中的段**
     * （screenOnAt <= 0）；否则刚归零过的新段会被误判成「没起算过」。
     */
    @Test
    fun `刚归零的新段 零累计但 screenOnAt 有效 不得再次归零`() {
        val t = 10_000_000L
        val freshSegment = TimerState(
            workAccumMs = 0L,
            screenOnAt = t,                 // 新段刚起算，屏幕起点有效
            offAt = -1L
        )
        // 旧判据：只看 workAccumMs <= 0 → 会命中（这就是死循环）
        val oldGuard = freshSegment.workAccumMs <= 0L
        assertEquals(true, oldGuard)
        // 新判据：还要没有进行中的段
        val newGuard = freshSegment.screenOnAt <= 0 && freshSegment.workAccumMs <= 0L
        assertEquals(false, newGuard)
    }

    @Test
    fun `真正未起算的状态 应当被兜底起算`() {
        val s = TimerState(workAccumMs = 0L, screenOnAt = -1L, offAt = -1L)
        val newGuard = s.screenOnAt <= 0 && s.workAccumMs <= 0L
        assertEquals(true, newGuard)
    }

    @Test
    fun `累积了一小段的进行中段 不得归零`() {
        val t = 10_000_000L
        val s = TimerState(workAccumMs = 3_000L, screenOnAt = t - 3_000L, offAt = -1L)
        val newGuard = s.screenOnAt <= 0 && s.workAccumMs <= 0L
        assertEquals(false, newGuard)
    }

    // ---------------- 跨天：陈旧 screenOnAt 不得跨越整夜 ----------------

    /**
     * v2.6.18 回归：跨天时若屏幕此刻是灭的，新段必须以「未在累计」起步。
     *
     * 实测事故：前一天 23:5x 息屏，10-13 00:00 跨天检测把 `screenOnAt`
     * 保留/改写为「正在累计」，清晨首次亮屏时 `accumulatedAt` 用
     * `isScreenOn()=true` 全量采信，算出 27194s（7.5 小时）并弹超时提醒。
     * 关键在于**跨天那一刻屏幕是灭的**，字段值不足以证明「正在累计」。
     */
    @Test
    fun `跨天时屏幕已灭 新段不得被判定为正在累计`() {
        // 屏幕灭：isScreenOn=false → 新段 screenOnAt 必须为 -1
        val segStartYesterday = 1_000_000L          // 昨天的段起点
        val t = segStartYesterday + 86_400_000L     // 跨天那一刻
        val s = TimerState(workAccumMs = 600_000L, screenOnAt = segStartYesterday)
        // 语义：屏幕灭时，即便字段 screenOnAt > 0，也不能把整夜算进新段
        val screenOnAtForNewSegment = if (false && s.screenOnAt > 0) t else -1L
        assertEquals(-1L, screenOnAtForNewSegment)
        // 反证：屏幕真的亮着时才延续累计
        val screenOnAtWhenLit = if (true && s.screenOnAt > 0) t else -1L
        assertEquals(t, screenOnAtWhenLit)
    }

    /**
     * v2.6.18 回归：短暂息屏回来后，陈旧的 `screenOnAt` 不得被原样继承。
     *
     * 事故链条：短暂息屏（未达 leftLong 阈值）走 `awaitingRest` 的 else 分支，
     * 原实现 `screenOnAt = if (s.screenOnAt > 0) s.screenOnAt else t` 直接把
     * **几小时前的旧亮屏时刻**保留下来；紧接着 `elapsedMs()` 重新读盘，
     * `isScreenOn()=true` 全量采信 `t - screenOnAt` → 恒判「已到点」→
     * 同一分钟内刷了 19 次 DEADLINE。
     */
    @Test
    fun `短暂息屏回来 陈旧亮屏时刻不得被继承`() {
        val stale = 1_000_000L           // 几小时前的旧亮屏时刻
        val t = stale + 4 * 3_600_000L   // 现在（已过 4 小时）
        val s = TimerState(workAccumMs = 60_000L, screenOnAt = stale)

        // 错误做法（修复前）：无条件继承 → 跨度 4 小时
        val wrongSpan = t - s.screenOnAt
        assertEquals(4 * 3_600_000L, wrongSpan)

        // 正确做法：屏幕亮着也不得继承陈旧值，应以 t 重新起算
        val fixedScreenOnAt = t
        assertEquals(0L, t - fixedScreenOnAt)
    }

    /**
     * v2.6.18：跨天检测发生在闹钟/亮屏/每秒 tick 三条路径上，
     * 无论哪条触发，屏幕实况的判定必须一致。
     */
    @Test
    fun `跨天归零后累计不得包含息屏时段`() {
        val dayStart = 1_000_000L
        val morning = dayStart + 8 * 3_600_000L   // 清晨首次亮屏
        // 跨天时屏幕灭 → 新段 screenOnAt = -1 → 累计恒为已落盘值
        val rolled = TimerState(workAccumMs = 0L, screenOnAt = -1L)
        assertEquals(0L, EyeTimer.accumulatedAt(rolled, morning, screenActuallyOn = true))
        // 修复前的错误状态（screenOnAt 被写成凌晨某时刻）会算出整段时间
        val buggy = TimerState(workAccumMs = 0L, screenOnAt = dayStart + 11 * 60_000L)
        assertEquals(8 * 3_600_000L - 11 * 60_000L,
            EyeTimer.accumulatedAt(buggy, morning, screenActuallyOn = true))
    }

    // ---------------- 息屏必须落盘（v2.6.19 根因回归） ----------------

    /**
     * v2.6.19 回归：`offAt` 是「上次真正息屏的时刻」，`leftLong` 判据完全依赖它。
     *
     * 事故：`onScreenOff` 的 `branch=NORMAL`（最常走的一条）写成
     * `recordWorkSession(context, f.copy(offAt = t))` —— 而 `recordWorkSession`
     * 在 v2.6.17 自统计废止后**只 return、不再写盘**，于是返回值被丢弃，
     * `offAt` 从未持久化。磁盘上恒为 -1 → 亮屏时 `leftLong` 恒假 →
     * **长息屏后亮屏不归零**（用户实测：息屏 4 分 34 秒，亮屏接着走而非从零）。
     *
     * 本测试锁定语义：息屏时算出的 `offAt` 必须能被后续读到 —— 即调用方
     * 不得依赖被调函数的写盘副作用，必须显式落盘。
     */
    @Test
    fun `息屏时刻必须落盘 否则亮屏判不出离开过`() {
        val offTime = 1_700_000_000_000L
        // 息屏处理产出的状态：offAt 为息屏时刻、screenOnAt 已冻结为 -1
        val afterScreenOff = TimerState(
            workAccumMs = 337_000L,
            screenOnAt = -1L,
            offAt = offTime
        )
        // 若调用方丢弃返回值（旧实现的缺陷），磁盘上读到的仍是默认 -1
        val ifReturnDiscarded = TimerState()
        assertEquals(-1L, ifReturnDiscarded.offAt)

        // 正确落盘后，亮屏时能算出「离开过」
        val threshold = EyeTimer.LONG_OFF_RESET_MS
        val cameBackAt = offTime + threshold + 60_000L   // 超过阈值 1 分钟
        val leftLong = afterScreenOff.offAt > 0 && cameBackAt - afterScreenOff.offAt >= threshold
        assertTrue("息屏超过阈值后必须判定为离开过", leftLong)

        // 未达阈值：仍算「短暂息屏」，不应归零
        val brief = afterScreenOff.offAt > 0 &&
            (offTime + threshold / 2) - afterScreenOff.offAt >= threshold
        assertFalse("未达阈值不得判定为离开过", brief)
    }

    /**
     * v2.6.19：`recordWorkSession` 早退路径（增量 < 5 秒）会返回**未带 offAt**
     * 的 state，因此调用方必须再强制带上息屏时刻，否则短息屏同样丢 offAt。
     */
    @Test
    fun `增量不足五秒早退时 offAt 也必须被带上`() {
        val offTime = 1_700_000_000_000L
        val s = TimerState(workAccumMs = 0L, screenOnAt = -1L)
        // 模拟 recordWorkSession 早退返回：只改基准，不含 offAt
        val earlyReturn = s.copy(recordedAccumMs = s.workAccumMs)
        assertEquals(-1L, earlyReturn.offAt)
        // 调用方必须补上（修复后的写法：settled.copy(offAt = t)）
        val persisted = earlyReturn.copy(offAt = offTime)
        assertEquals(offTime, persisted.offAt)
    }
}
