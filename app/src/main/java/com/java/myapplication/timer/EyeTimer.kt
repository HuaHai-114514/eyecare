package com.java.myapplication.timer

import android.content.Context
import android.os.PowerManager
import com.java.myapplication.AppForeground
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.EyeTips
import com.java.myapplication.data.SettingsStore
import com.java.myapplication.data.StatsStore
import com.java.myapplication.data.TimerState
import com.java.myapplication.data.TimerStore
import com.java.myapplication.notify.RestNotifier
import com.java.myapplication.notify.RestOverlayService
import com.java.myapplication.notify.RestSoundPlayer
import com.java.myapplication.notify.RestVibrator
import com.java.myapplication.ui.DndWindow

object EyeTimer {

    /**
     * 息屏达到该时长视为「真正离开」，再次亮屏时累计清零并重新起算。
     *
     * v2.4.3 起由 5 分钟收紧为 1 分钟（用户反馈：放下手机再回来不该续算，
     * 更不该把息屏期间判成「超时未休息」）；短于该阈值的息屏仍视为短暂离开，
     * 回来后续算剩余时间，避免抬手看时间就毁掉进度。
     */
    const val LONG_OFF_RESET_MS = 1 * 60 * 1000L

    /** 低于该秒数的用眼片段视为抖动噪声，不计入统计 */
    private const val MIN_RECORD_SECONDS = 5

    /**
     * 本次进程是否**真的**经历过一次息屏（边沿标记）。
     *
     * v2.6.17：仅靠 `offAt`/`screenOnAt` 无法区分「息屏后回来」与「切后台返回」——
     * 两者都会让 [onScreenOn] 被调用。只有屏幕状态发生 off→on 跃变才算「离开过」。
     * 本标记在 [onScreenOff] 置位，由 [consumeScreenOnEdge] 消费（读后清零），
     * 保证一次息屏只对紧接着的那次亮屏生效。
     */
    @Volatile
    private var sawScreenOff = false

    /**
     * 读取并清除「息屏边沿」标记。
     *
     * 必须**先消费再判断**：[onScreenOn] 里有多个提前 return 分支
     * （`!reminderEnabled`、`phaseResting` 等），若把消费放在后面，
     * 标记会残留到下一次亮屏，把一次无关的亮屏误判成「前置过息屏」。
     */
    private fun consumeScreenOnEdge(): Boolean {
        val seen = sawScreenOff
        sawScreenOff = false
        return seen
    }

    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun workMs(context: Context): Long =
        SettingsStore.load(context).workMinutes * 60 * 1000L

    private fun restMs(context: Context): Long =
        SettingsStore.load(context).restSeconds * 1000L

    /** 超时二次提醒间隔毫秒（v2.4.0） */
    private fun overdueMs(context: Context): Long =
        SettingsStore.load(context).overdueRemindMinutes * 60 * 1000L

    /**
     * 当前周期已累计的亮屏用眼毫秒 = 已落盘累计 + 本次亮屏已过去时长。
     *
     * v2.6.16：用 [isScreenOn] 交叉校验 `screenOnAt`，屏幕实际熄灭时不加墙钟差
     * —— 否则息屏广播丢失后，整段息屏时间会被算成用眼（统计虚高根因）。
     */
    fun elapsedMs(context: Context): Long {
        val s = TimerStore.load(context)
        return accumulatedAt(s, now(), isScreenOn(context))
    }

    /**
     * 把「本次亮屏已过去的时间」结算进累计值，并停止累计（进入暂停/冻结态）。
     *
     * v2.6.16：[screenActuallyOn] 同 [accumulatedAt]，用于拦截陈旧的 `screenOnAt`。
     */
    private fun frozen(s: TimerState, at: Long, screenActuallyOn: Boolean = true): TimerState {
        val acc = accumulatedAt(s, at, screenActuallyOn)
        return s.copy(workAccumMs = acc, screenOnAt = -1L)
    }

    // ==================== 用眼统计（旁路，v2.3 新增） ====================

    /**
     * 结算一段「已完成的用眼时长」并写入统计。
     *
     * 关键约束：本方法**只读** [TimerState]，不修改、不回写计时状态，
     * 因此不会影响任何既有计时语义（各出口的 copy() 参数与 return 分支保持原样）。
     *
     * @param state 结算前的计时状态快照
     */
    // ==================== 跨天归零（v2.4.x 第二轮） ====================

    /** 某时刻所在日的 00:00:00.000 时间戳（本地时区） */
    private fun dayStartOf(time: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = time
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 跨天归零：若当前累计段的**起点**与现在不在同一天，
     * 则把 [0 点之前] 的部分结算落盘（它属于昨天），并把累计值归零、
     * 段起点重置为「今天的 0 点」，使 0 点之后的时间从新的一天重新累计。
     *
     * 语义目标（用户诉求）：**每天 0 点，当日用眼统计从 0 重新累计**。
     *
     * 返回**可能被更新过**的状态；调用方应当用返回值覆盖自己的 local state，
     * 再基于新状态继续。若未跨天则原样返回。
     *
     * 注意：本函数会就地落盘并写统计，因此只应在「结算类」出口调用
     * （onScreenOn / onWorkDeadline / beginRest / finishRest / postponeRest /
     *  resync / 界面 tick）。
     */
    internal fun rollOverIfDayChanged(context: Context, s: TimerState, at: Long): TimerState {
        // 段起点未知：无法判断，原样返回（兼容旧数据）
        val segStart = s.accumStartWallMs
        if (segStart <= 0L) return s
        // 同一自然日：无需归零
        if (StatsStore.dayKeyAt(segStart) == StatsStore.dayKeyAt(at)) return s

        // 跨天了：先把「段起点 → 今天 0 点」这段的有效用眼结算到起点日。
        // 用比例：有效用眼 = 该墙钟子区间占整段墙钟跨度的比例 × 当前累计。
        val todayStart = dayStartOf(at)
        val curAccum = accumulatedAt(s, at, isScreenOn(context))
        val fullSpan = (at - segStart).coerceAtLeast(1L)
        val beforeSpan = (todayStart - segStart).coerceIn(0L, fullSpan)
        val beforeMs = (curAccum.toDouble() * (beforeSpan.toDouble() / fullSpan.toDouble())).toLong()
        // 已结算部分按同一比例切分，跨天时只补「尚未计入统计」的差额，
        // 否则整段会被重复记一次（旧实现的翻倍根因之一）。
        val alreadyRatio = s.recordedAccumMs.toDouble().coerceIn(0.0, curAccum.toDouble()) *
            (beforeSpan.toDouble() / fullSpan.toDouble())
        // v2.6.17：自统计已废止，跨天不再需要往昨天补记一笔。
        // 保留下面的「归零并从今天 0 点重新起算」——计时器本身仍要跨天归零。

        // 归零并从「今天 0 点」重新起算：
        // - workAccumMs 只保留 0 点之后到 at 的用量（即 curAccum - beforeMs）
        // - screenOnAt 保持「正在累计」当且仅当**此刻屏幕真的亮着**
        // - 段起点重置为「今天 0 点」
        val afterMs = (curAccum - beforeMs).coerceAtLeast(0L)
        // ⚠️ v2.6.18 回归修复：这里原为 `if (s.screenOnAt > 0) at else -1L`，
        // 只看持久化字段、不校验屏幕实况，是「次日首次亮屏弹超时 500+ 分钟」的根因。
        //
        // 事故链（实测日志 segStart=当天0点 onAt=前一天亮屏时刻）：
        //  1. 前一天最后一次亮屏结束后 `frozen()` 已把 `screenOnAt` 落盘为 -1，
        //     但跨天检测走的是 `clockTick` 每秒路径，它读到的 `s` 可能来自
        //     「屏幕刚灭、而某个中间态把 screenOnAt 又写回正数」的时刻；
        //  2. 跨天时该分支把陈旧的 `screenOnAt` 直接改写成 `at`（数小时后的凌晨），
        //     于是新段看起来「从凌晨就开始累计」；
        //  3. 清晨用户首次亮屏，`accumulatedAt` 的 `screenActuallyOn = isScreenOn()`
        //     此刻为 true，于是全量采信 `at - screenOnAt` —— 把**整夜**算成用眼，
        //     连带 `onWorkDeadline` 反复判定「已到点」并弹超时提醒。
        //
        // 修法与 [accumulatedAt] 的 v2.6.16 修复同源：**累计入口必须用真实屏幕状态
        // 交叉验证**，字段值本身不足以证明「正在累计」。屏幕此刻是灭的，新段一律
        // 以「未在累计」起步（screenOnAt = -1）；屏幕真的亮着才继续累计。
        val screenActuallyOn = isScreenOn(context)
        // 新一段（0 点之后）尚未结算，recordedAccumMs 与 workAccumMs 同步归零，
        // 并把段序号 +1 —— 否则新一天的段与昨天的段序号相同，
        // recordWorkSession 会拿昨天的基准去比今天的累计（少记或重记）。
        return s.copy(
            workAccumMs = afterMs,
            screenOnAt = if (screenActuallyOn) at else -1L,
            accumStartWallMs = todayStart,
            recordedAccumMs = 0L,
            accumGeneration = s.accumGeneration + 1L,
            recordedGeneration = s.accumGeneration + 1L
        )
    }

    private fun recordWorkSession(context: Context, state: TimerState): TimerState {
        val accum = state.workAccumMs
        // v2.6.12：基准只在「同一段」内才可信，用段序号严格判定。
        // 旧实现靠 `recordedAccumMs > accum` 事后猜段，但 `resync` 亮屏分支与
        // `onScreenOn` 短暂息屏续算分支只归零了 workAccumMs 而漏了 recordedAccumMs，
        // 于是新段被旧基准压住 → 增量恒为 0（漏记）或整段重记（虚高 12 倍）。
        // 现在基准带上它所属的段序号：只有序号一致才认旧基准，否则本段全额入账。
        val sameSegment = state.recordedGeneration == state.accumGeneration
        val already = if (sameSegment) state.recordedAccumMs.coerceIn(0L, accum) else 0L
        val deltaSec = ((accum - already) / 1000L).toInt()
        // v2.6.12：真实「连续用眼」跨度由本段墙钟跨度反推（见 realStreakSeconds）。
        val streakSec = realStreakSeconds(state, accum)
        // v2.6.14（临时诊断）：把每一次结算的算式落盘，用于定位重复入账。
        if (deltaSec < MIN_RECORD_SECONDS) {
            // 增量不足 5 秒：不写统计，但把基准抬到当前累计值，
            // 避免这些零头在后续出口被反复凑成一段重复计入。
            return state.copy(recordedAccumMs = accum, recordedGeneration = state.accumGeneration)
        }
        // v2.6.17：这里原本调用 StatsStore.addWorkSession 把增量写进自统计。
        // 自统计已废止，不再有任何一笔写盘 —— 本函数退化为纯粹的
        // 「把已结算水位线抬到当前累计」的状态推进。
        return state.copy(recordedAccumMs = accum, recordedGeneration = state.accumGeneration)
    }

    /**
     * v2.6.12：开启新一段累计。
     *
     * `recordedAccumMs` 与 `recordedGeneration` 同步重置 —— 这样
     * [recordWorkSession] 永远不会把上一段的基准套到新段上（虚高根因）。
     */
    private fun TimerState.newSegment(
        at: Long,
        screenOn: Boolean
    ): TimerState = newSegment(at, screenOn, "?")

    private fun TimerState.newSegment(
        at: Long,
        screenOn: Boolean,
        trace: String
    ): TimerState = copy(
        workAccumMs = 0L,
        recordedAccumMs = 0L,
        accumGeneration = accumGeneration + 1L,
        recordedGeneration = accumGeneration + 1L,
        accumStartWallMs = if (screenOn) at else -1L,
        screenOnAt = if (screenOn) at else -1L,
        offAt = if (screenOn) -1L else at
    )

    /**
     * v2.6.12：真实「连续用眼」时长（秒）。
     *
     */
    internal fun realStreakSeconds(state: TimerState, accumMs: Long, at: Long = now()): Int {
        val start = state.accumStartWallMs
        if (start <= 0L) return (accumMs / 1000L).toInt()
        val wallSpan = (at - start).coerceAtLeast(0L)
        val span = maxOf(wallSpan, accumMs)
        return (span / 1000L).toInt()
    }

    /**
     * 结算「超时用眼」增量（v2.4.0）。
     *
     * 到点后未休息的时间里，累计值会超过 workMs。这里只把**超出到点值的部分**
     * 写入统计，并用 [TimerState.overdueRecordedMs] 记录已结算的毫秒数去重，
     * 避免每次 tick / 重复提醒把同一段时长反复计入。
     */
    private fun recordOverdue(context: Context, s: TimerState, at: Long): Long {
        if (!s.overdueCounting) return s.recordedAccumMs
        val curAccum = accumulatedAt(s, at, isScreenOn(context))
        // v2.6.15：超时段**不再单独入账**。
        // recordWorkSession 的 `accum - already` 已覆盖到点后的全部时长，
        // 这里再按 `curAccum - workMs - overdueRecordedMs` 记一次就是纯重复。
        // 本函数保留，用于推进 overdueRecordedMs 水位线（决定超时提醒是否该响）
        // 并把当前累计返回给调用方。
        return curAccum
    }

    /**
     * 把一段「已冻结的累计」按同一段基准结算入统计。
     *
     * v2.6.13：`recordWorkSession` 的 `already` 取值要求**同一段**且
     * `recordedAccumMs` 与传入的 `workAccumMs` 口径一致。各出口在冻结累计后
     * 常常紧接着又调用了 `recordOverdue`（它返回的 `curAccum` 是**含超时**的总量），
     * 于是 `recordedAccumMs` 被抬到「含超时值」、而 `workAccumMs` 仍是冻结值，
     * 两者口径再次错位。
     *
     * 因此这里统一收口：先按冻结累计结算，再把超时增量单独结算，
     * 最后把基准显式对齐到「冻结累计」，保证任何出口都不会重复入账。
     */
    /**
     * 把「已落盘累计 + 本次亮屏已过去时长」算成当前总累计毫秒（纯函数，便于测试）。
     *
     * v2.6.16：新增 [screenActuallyOn] 交叉校验。`screenOnAt` 是「进程内事件推断值」，
     * 一旦息屏广播丢失（进程被杀、广播被系统丢弃），它会停留在一个陈旧的亮屏时刻，
     * 而 `at - screenOnAt` 就把整段息屏时间算成用眼（实测虚高 20 分钟）。
     * 因此调用方应传入由 `PowerManager.isInteractive` 得到的真实屏幕状态；
     * 屏幕实际是灭的时一律按「未在累计」处理。
     */
    internal fun accumulatedAt(s: TimerState, at: Long, screenActuallyOn: Boolean = true): Long =
        if (s.screenOnAt > 0 && screenActuallyOn) {
            s.workAccumMs + (at - s.screenOnAt).coerceAtLeast(0L)
        } else {
            s.workAccumMs
        }

    /**
     * 计算应写入统计的「超时增量秒数」（纯函数，v2.4.0）。
     *
     * @param currentAccumMs 当前累计用眼毫秒（含超时）
     * @param targetWorkMs 到点阀值（即设置的用眼间隔）
     * @param alreadyRecordedMs 此前已结算过的超时毫秒（去重）
     * @return 本次应新增计入统计的秒数（可能为 0）
     */
    internal fun overdueDeltaSeconds(
        currentAccumMs: Long,
        targetWorkMs: Long,
        alreadyRecordedMs: Long
    ): Int {
        val overdueTotal = (currentAccumMs - targetWorkMs).coerceAtLeast(0L)
        val delta = (overdueTotal - alreadyRecordedMs).coerceAtLeast(0L)
        return (delta / 1000L).toInt()
    }

    /** 计算到 [at] 为止、已经结算并写入统计的超时毫秒数（用于去重落盘） */
    private fun overdueRecordedAfter(context: Context, s: TimerState, at: Long): Long {
        val curAccum = accumulatedAt(s, at, isScreenOn(context))
        return (curAccum - workMs(context)).coerceAtLeast(0L)
    }

    /** 免打扰时段判断：避开免打扰，避免打扰用户 */
    private fun shouldNotify(context: Context): Boolean {
        val settings = SettingsStore.load(context)
        return !DndWindow.isInDndWindow(settings, StatsStore.minuteOfDay())
    }

    // ==================== 亮屏 / 息屏 ====================

    /**
     * 亮屏（含解锁）：
     * - 休息中：不打断；
     * - 同一亮屏会话（进程重启等）：继续累计，仅确保闹钟存在；
     * - 新会话：达到 [LONG_OFF_RESET_MS] 的息屏视为「真正离开」→ 清零重算，
     *   并作废「到点未休息」的超时态；短于阈值则续算剩余时间。
     */
    fun onScreenOn(context: Context) {
        // v2.6.17：**先消费边沿标记**，再做任何可能提前 return 的判断。
        // 否则 `!reminderEnabled` / `phaseResting` 的提前返回会让标记残留到
        // 下一次调用，把一次无关的亮屏误判成「前置过息屏」。
        // 消费后的值以局部变量贯穿本函数，语义清晰且不受后续分支影响。
        val cameFromScreenOff = consumeScreenOnEdge()
        var s = TimerStore.load(context)
        if (!s.reminderEnabled) return
        if (s.phaseResting) return
        // 跨天：亮屏时先把昨天那段结算掉、今天从 0 重新累计
        s = rollOverIfDayChanged(context, s, now()).also { if (it !== s) TimerStore.save(context, it) }

        val t = now()

        // 本次息屏是否已达「真正离开」阈值
        val leftLong = s.offAt > 0 && t - s.offAt >= LONG_OFF_RESET_MS

        if (s.awaitingRest) {
            if (leftLong) {
                // 息屏够久 = 用户已放下手机：作废「到点未休息」，
                // 清空用眼计时与超时态，亮屏后从 0 安静重新起算（不再弹超时）。
                resetToFreshCycle(context, t)
            } else {
                // 短暂息屏后回来：**不再恢复超时态、不再重排超时闹钟**。
                // v2.6.12 修复：原实现把 offAt 抹成 -1 并重排超时闹钟，而
                // ACTION_SCREEN_ON / ACTION_USER_PRESENT 是两次独立广播 →
                // 第二次进来 leftLong 因 offAt == -1 恒为 false，又走一遍 else
                // 再排一次闹钟，于是亮屏后不久就误弹「超时未休息」。
                // 现在只恢复累计、清掉超时标记，等用户真正继续用眼到点后重新提醒。
                // ⚠️ v2.6.18 回归修复：`screenOnAt` 不得无条件继承。
                //
                // 原为 `if (s.screenOnAt > 0) s.screenOnAt else t`，只看字段不看
                // 屏幕实况 —— 与 [rollOverIfDayChanged] 是同一类缺陷的两个表现，
                // 同样导致「次日首次亮屏弹超时 500+ 分钟」以及提醒反复触发：
                //  1. 息屏期间 frozen() 已把 screenOnAt 落盘为 -1，但短暂息屏
                //     （未达 leftLong 阈值）走本分支时，若字段恰为陈旧正数，
                //     就被原样保留 —— 而它指向的是**几小时前的旧亮屏时刻**；
                //  2. 紧接着 `elapsedMs(context)` 重新读盘，此时 isScreenOn() 为 true，
                //     `accumulatedAt` 全量采信 `t - screenOnAt` → 算出数小时「用眼」；
                //  3. `elapsed >= workMs` 恒真 → 每次亮屏都调 onWorkDeadline，
                //     实测同一分钟内刷了 19 次 DEADLINE。
                //
                // 正确语义：这里的 `screenOnAt` 是「本段累计起点」，只有在**本段仍在
                // 累计中**（字段有效）且**屏幕此刻真的亮着**时才可延续；否则必须
                // 以当前时刻 t 重新起算，绝不能把陈旧跨度接进新段。
                val screenActuallyOn = isScreenOn(context)
                TimerStore.save(
                    context,
                    s.copy(
                        screenOnAt = if (screenActuallyOn && s.screenOnAt > 0) s.screenOnAt else t,
                        offAt = -1L,
                        awaitingRest = false,
                        overdueCounting = false,
                        overdueRecordedMs = 0L
                    )
                )
                AlarmScheduler.cancelOverdueReminder(context)
                RestNotifier.cancelReminder(context)
                RestOverlayService.stop(context)
                val workMs = workMs(context)
                // v2.6.18：elapsed 改为基于**刚落盘的状态**计算，不再重新读盘 ——
                // 重新读盘会拿到与上面判据不同的快照（正是重复触发的成因之一）。
                // v2.6.17 已在 [onWorkDeadline] 内做过同样的「快照一致性」修复。
                val justSaved = TimerStore.load(context)
                val elapsed = accumulatedAt(justSaved, t, screenActuallyOn)
                if (elapsed >= workMs) {
                    onWorkDeadline(context, notify = true)
                } else {
                    AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - elapsed))
                }
            }
            return
        }

        // v2.6.17：**先判「是否真正离开过」，再做其它一切分支**。
        //
        // 这是「熄屏一段时间后亮屏应从 0 重新计时」失效的根因。原实现把
        // 「已到点却还没提醒 → 立刻补提醒」的兜底放在 leftLong 判定之前，
        // 于是：息屏前刚好累计到点 → 息屏一小时后亮屏 → elapsed 仍 >= workMs
        // → 直接走 onWorkDeadline 弹提醒并 return，下面「真正离开过 → 新段归零」
        // 的分支永远没机会执行。
        //
        // 后果有两个，都是同一个根因：
        //   1. 亮屏瞬间误弹「到点请休息」/「超时 N 分钟」；
        //   2. onWorkDeadline 会把 screenOnAt 改写为亮屏时刻、却保留息屏前的
        //      workAccumMs，下次结算 accumulatedAt 就把中间的息屏跨度算成用眼，
        //      即统计虚高。
        //
        // 正确顺序：离开过 → 直接开新段（归零、+1 代际、清超时态），结束。
        //
        // ⚠️ v2.6.17 回归修复：「离开过」必须限定在**本次亮屏确实来自一次息屏**。
        //
        // 判据演进（两步都踩过坑）：
        //  - 只看 `leftLong = t - offAt >= 阈值`：`offAt` 是上次真正息屏的时间戳，
        //    切后台并不更新它 → 只要上次息屏距今超过阈值，之后**每次**切回前台
        //    （onForeground → syncScreenState → onScreenOn）都误判「离开过」而归零。
        //  - 再补 `screenOnAt <= 0`：无效。`frozen()` 每次冻结都会把 `screenOnAt`
        //    写成 -1 并落盘，所以「真正息屏过一次」之后它**长期就是 -1**，
        //    该前提形同不存在，回归照旧。
        //
        // 唯一可靠的区分是**边沿**：真正的「息屏→亮屏」会让屏幕状态发生
        // off→on 跃变；而切后台返回时屏幕全程是亮的，状态并未变化。
        // 因此这里要求 `consumeScreenOnEdge()` 为真 —— 只有跨过一次真实息屏
        // 的调用才有资格进入归零判定。
        // 「离开过」的判据：必须同时满足
        //   leftLong           —— 本次息屏时长达到阈值；
        //   cameFromScreenOff  —— 确实发生过「息屏→亮屏」跃变（排除切后台返回）。
        val leftThisScreen = leftLong && cameFromScreenOff

        // 「从未起算过」的兜底。
        //
        // ⚠️ v2.6.17 回归修复：原来条件是 `s.workAccumMs <= 0L`，而本函数会被
        // **重复调用**（MainActivity.onResume → onForeground → syncScreenState
        // → onScreenOn），于是形成自维持的死循环：
        //   newSegment 把 workAccumMs 清 0 且 screenOnAt 置为有效 →
        //   下次调用读到 workAccumMs == 0 → 又命中 <= 0 → 再归零一次……
        // 表现就是「每次切后台回来都从零重新计时」，且段序号 gen 一路疯涨
        // （实测 79 → 84 → 85）。日志铁证：leftLong=false、edge=false 两个
        // 判据都为假，却仍然走进了归零分支，唯一为真的就是这个兜底。
        //
        // 正确语义：「没有进行中的段」才需要兜底起算。`screenOnAt > 0` 表示
        // 当前段正在累计中，即便累计恰好是 0（刚开始、或刚归零过）也属于
        // 「已有段」，不得重复开新段。
        val noActiveSegment = s.screenOnAt <= 0 && s.workAccumMs <= 0L

        if (leftThisScreen || noActiveSegment) {
            resetToFreshCycle(context, t)
            return
        }

        val workMs = workMs(context)
        val elapsed = elapsedMs(context)

        // 已到点却还没提醒（例如息屏期间到点被冻结）：立刻补提醒
        if (elapsed >= workMs) {
            onWorkDeadline(context, notify = true)
            return
        }

        if (s.screenOnAt > 0) {
            // 同一次亮屏会话内：继续累计，只校正闹钟
            AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - elapsed))
            return
        }

        run {
            // 短暂息屏：续算剩余时间。
            // v2.6.13：续算时**不能**把「息屏前的累计」当成同一段已结算值 ——
            // 息屏那一刻的累计已经冻结在 workAccumMs 里，而 recordedAccumMs 可能
            // 落后（上一段遗留）或超前。这里显式声明「息屏前的累计已结算」，
            // 并把段落基准（起点/序号）对齐，避免续算段被整段重记。
            TimerStore.save(
                context,
                s.copy(
                    screenOnAt = t,
                    offAt = -1L,
                    recordedAccumMs = s.workAccumMs,
                    recordedGeneration = s.accumGeneration
                )
            )
            AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - s.workAccumMs))
        }
    }

    /**
     * 清空用眼计时与「到点未休息」超时态，从 [t] 起算一个全新周期。
     *
     * 用于「息屏达到 [LONG_OFF_RESET_MS] 后亮屏」：用户已经离开过，
     * 上一轮的到点欠账与超时一律作废，亮屏后安静地重新计时。
     * 会撤销到点 / 超时闹钟，并按需重排新的到点闹钟（由调用方屏幕状态决定）。
     */
    private fun resetToFreshCycle(context: Context, t: Long) {
        // v2.6.12：也要开新段（段序号 +1、基准归零），否则上一段的
        // recordedAccumMs 会在新段里被当成「已结算」而把新段整段漏记。
        val prev = TimerStore.load(context)
        // v2.6.17（临时诊断）：归零是统计/计时异常的核心动作，标出调用来源。
        TimerStore.save(
            context,
            prev.newSegment(at = t, screenOn = true).copy(
                reminderEnabled = true,
                phaseResting = false,
                awaitingRest = false,
                overdueCounting = false,
                overdueRecordedMs = 0L
            )
        )
        AlarmScheduler.cancelOverdueReminder(context)
        RestNotifier.cancelReminder(context)
        RestOverlayService.stop(context)
        AlarmScheduler.scheduleWorkDeadline(context, t + workMs(context))
    }

    /**
     * 息屏：暂停累计并撤销到点闹钟（息屏期间不唤醒设备）。
     *
     * v2.4.3 起：息屏时若处于「到点未休息」的超时态，先把超时时长结算入统计，
     * 再**清掉超时标记**（不再等亮屏时恢复）——亮屏时由 [onScreenOn] 依据
     * 息屏时长决定「续算」还是「清零重算」，从根本上杜绝息屏后亮屏误弹超时。
     */
    fun onScreenOff(context: Context) {
        // 记录真实息屏边沿：后续 onScreenOn 可据此区分「真的离开过」与「切后台返回」
        sawScreenOff = true
        val s = TimerStore.load(context)
        if (!s.reminderEnabled) {
            AlarmScheduler.cancelAll(context)
            return
        }

        val t = now()
        val f = frozen(s, t)
        AlarmScheduler.cancelWorkAlarm(context)
        // 息屏时若已累计出可观用眼时长，视为本次用眼周期结束，结算入统计。
        // v2.4.4：recordWorkSession 现在会落盘（含 offAt），故改为互斥分支，
        // 避免先前写入的 offAt 被随后落盘的旧 offAt 覆盖（会让「息屏时长」判定失真）。
        //
        // ⚠️ v2.6.19 回归修复（「熄屏后亮屏不重置」的真正根因）：
        // 上面这条注释里的「recordWorkSession 现在会落盘」**在 v2.6.17 之后不再成立** ——
        // 自统计废止时该函数被改成纯状态推进，只 `return state.copy(...)`，
        // **内部不再调用 TimerStore.save()**。于是本分支（最常走的一条）的返回值
        // 被直接丢弃，`offAt = t` 从未持久化：
        //   磁盘 offAt 恒为 -1 → 亮屏时 `leftLong = offAt > 0 && ...` 恒假
        //   → 判不出「离开过」→ 长息屏后亮屏不归零。
        // 当时现象：息屏那一刻算出的 offAt 在数分钟后读回仍是 -1，
        // 即「算得对，但从未落盘」。
        // 修法：显式落盘，不再依赖被调用方的副作用。
        if (!s.phaseResting && !s.awaitingRest) {
            val settled = recordWorkSession(context, f.copy(offAt = t))
            // `recordWorkSession` 可能早退（增量不足 5 秒）而返回**未带 offAt** 的 state，
            // 故在此强制带上本次息屏时刻，确保无论走哪条 return 都能记住「何时离开」。
            TimerStore.save(context, settled.copy(offAt = t))
        } else {
            TimerStore.save(context, f.copy(offAt = t))
        }

        // 休息进行中：锁屏不打断，保留结束闹钟
        if (s.phaseResting) {
            AlarmScheduler.scheduleRestDeadline(context, s.restStart + restMs(context))
            return
        }
        // 等待用户确认休息（超时态）：结算超时时长 + 冻结累计 + 撤销超时闹钟。
        // 这里保留 awaitingRest 落盘、但关掉 overdueCounting（息屏期间不再累计超时、
        // 也不再重复提醒）；是否作废该状态交由亮屏时按息屏时长判定：
        // 短息屏（< 阈值）续算剩余时间，长息屏清零重算并作废超时态。
        if (s.awaitingRest) {
            // v2.6.12：息屏即作废「到点未休息」超时态（不再等亮屏按息屏时长判定）。
            // 先把超时时长结算入统计，再清掉 awaitingRest / overdueCounting，
            // 从根本上杜绝「息屏→亮屏」误弹超时提醒。
            val mark = recordOverdue(context, s, t)
            TimerStore.save(
                context,
                f.copy(
                    offAt = t,
                    awaitingRest = false,
                    overdueCounting = false,
                    overdueRecordedMs = overdueRecordedAfter(context, s, t),
                    recordedAccumMs = mark
                )
            )
            AlarmScheduler.cancelOverdueReminder(context)
            RestNotifier.cancelReminder(context)
            RestOverlayService.stop(context)
            return
        }

        // 非唤醒兜底闹钟：只在设备下次醒来时投递，用于进程被杀后的校正（不耗电）
        val remaining = workMs(context) - elapsedMs(context)
        if (remaining > 0) {
            AlarmScheduler.scheduleWorkCheckFallback(context, t + remaining)
        }
    }

    /** 进程启动时同步一次真实屏幕状态（挂在 Application.onCreate） */
    fun syncScreenState(context: Context) {
        if (isScreenOn(context)) {
            onScreenOn(context)
        } else {
            // 进程可能在息屏期间被杀，启动时直接观察到「屏幕是灭的」——
            // 这同样是一次有效息屏边沿，必须置位，否则随后亮屏会漏掉归零判定。
            onScreenOff(context)
        }
    }

    // ==================== 到点提醒 / 休息 ====================

    /**
     * 用眼到点：只发提醒通知，不自动进入休息。
     * @param notify false 时只切换到「等待休息」状态而不发通知
     */
    fun onWorkDeadline(context: Context, notify: Boolean = true) {
        var s = TimerStore.load(context)
        if (!s.reminderEnabled) return
        if (s.phaseResting) return
        if (s.awaitingRest) return
        // 跨天：到点处理前先归零（若从昨天用到今天，昨天那段记到昨天）
        s = rollOverIfDayChanged(context, s, now()).also { if (it !== s) TimerStore.save(context, it) }

        val t = now()
        val workMs = workMs(context)
        // v2.6.17：`elapsed` 必须由**本函数已持有的 s** 算出，不能再调 elapsedMs()
        // 重新 load 一次 —— 两次读取之间状态可能已被其它出口改写（息屏广播、
        // resync 亮屏、闹钟并发），判据与随后结算用的累计就会来自不同的快照，
        // 正是「亮屏瞬间误判已到点」的来源。
        val elapsed = accumulatedAt(s, t, isScreenOn(context))

        if (elapsed < workMs) {
            // 提前触发（如兜底闹钟）：重排到真正到点
            AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - elapsed))
            return
        }
        if (!isScreenOn(context)) {
            // 息屏：冻结累计，等下次亮屏重算
            // v2.6.16：此处由闹钟触发，屏幕已灭，screenOnAt 可能已陈旧，
            // 明确告知「屏幕不在亮」以免把息屏时段计入用眼。
            TimerStore.save(context, frozen(s, t, screenActuallyOn = false).copy(offAt = t))
            AlarmScheduler.cancelWorkAlarm(context)
            return
        }

        // 到点：进入「等待休息 + 超时累计」态。
        // v2.4.0 起不再把累计冻结在 workMs，而是保留 screenOnAt 继续走动，
        // 这样用户不休息期间的时间会照常累计并计入统计（修复统计漏算）。
        // 到点这一轮先把「到点前的 workMs」结算入统计（带本段真实起点，
        // 跨天时能正确归属到昨天），再把段起点重置为 t 起算超时段。
        // v2.4.4：recordWorkSession 会把 recordedAccumMs 抬到 workMs，
        // 因此这一步必须**先于**下面 deadlineState 的落盘，避免被覆盖。
        // v2.6.15：到点结算必须用**当前真实累计**（含已走过的超时零头），
        // 而不是把 workAccumMs 压回 workMs。旧实现先在 recordWorkSession 里
        // 把基准抬到 1200s，随后又把 workAccumMs 压回 1200s 并把
        // accumStartWallMs 重置为 t —— 于是「超时段」与「到点前段」在基准上
        // 打架：下一段从 0 长到 1200s 时会被整段再记一遍（统计虚高根因）。
        val curAccum = accumulatedAt(s, t, isScreenOn(context))
        val settled = recordWorkSession(context, s.copy(workAccumMs = curAccum))
        val deadlineState = settled.copy(
            workAccumMs = curAccum,
            screenOnAt = t,
            offAt = -1L,
            awaitingRest = true,
            overdueCounting = true,
            overdueRecordedMs = 0L,
            // 到点前的那段已在上一步结算；之后累计的「超时段」从 t 起算，
            // 故段起点重置为 t，且已记基准抬到 workMs（超时段从 0 开始计）。
            accumStartWallMs = t
        )
        TimerStore.save(context, deadlineState)
        AlarmScheduler.cancelWorkAlarm(context)
        // 排超时二次提醒（未休息则每 overdueMs 重复）
        AlarmScheduler.scheduleOverdueReminder(context, t + overdueMs(context))
        if (notify && shouldNotify(context)) {
            // 一次 load 取两个值（原来 load 两次），并把真实间隔传给通知文案
            val settings = SettingsStore.load(context)
            RestNotifier.notifyRestReminder(context, settings.workMinutes, settings.restSeconds)
        }
        // v2.4.2 到点强制全屏：改用「悬浮窗」绕开 Android 15/16 对全屏通知的限制。
        // 语义 = 「到点不等用户点击，直接开始休息并全屏展示」。
        // 触发条件与产品约定严格一致：
        //   - 开关关闭            → 不动（维持上面已发的「等待确认休息」横幅）
        //   - 息屏/锁屏           → 不动（屏没亮谈不上护眼）
        //   - App 在前台          → 直接 beginRest，由 App 内休息页渲染倒计时
        //   - App 在后台 + 亮屏   → beginRest + 后台全屏 Overlay 盖住其他应用
        val autoSettings = SettingsStore.load(context)
        if (autoSettings.autoFullScreen && isScreenOn(context)) {
            if (AppForeground.isForeground) {
                // 前台：自动进入休息，Compose 会随状态切成休息页
                beginRest(context)
                RestOverlayService.stop(context)
            } else if (RestOverlayService.canDrawOverlay(context)) {
                // 后台亮屏且有悬浮窗权限：先真正开始休息（写状态机），再叠全屏覆盖
                beginRest(context)
                RestOverlayService.startIfScreenOn(
                    context, autoSettings.restSeconds, TimerStore.load(context).tipIndex
                )
            }
            // 后台但无悬浮窗权限：降级——保持横幅（onWorkDeadline 上方已发），
            // 用户可在自检页授权后重测；不动状态机，避免「无 UI 却已在休息」。
        }
    }

    /**
     * 超时二次提醒（v2.4.0）：到点后每 [AppSettings.overdueRemindMinutes] 分钟触发一次。
     *
     * 若用户仍未开始休息：再次弹通知提醒，并结算这段时间的超时时长入统计；
     * 若已休息 / 已稍后再说 / 已息屏：什么都不做（闹钟已被各自的出口撤销）。
     */
    fun onOverdueReminder(context: Context) {
        val s = TimerStore.load(context)
        if (!s.reminderEnabled) return
        if (s.phaseResting) return
        if (!s.awaitingRest) return

        val t = now()
        // 息屏了：冻结并撤销超时闹钟，不打扰
        if (!isScreenOn(context)) {
            // v2.6.16：闹钟触发路径，屏幕已灭，按「不在亮」处理。
            val f = frozen(s, t, screenActuallyOn = false)
            recordOverdue(context, s, t)
            TimerStore.save(context, f.copy(offAt = t, overdueCounting = false,
                overdueRecordedMs = overdueRecordedAfter(context, s, t)))
            AlarmScheduler.cancelOverdueReminder(context)
            return
        }

        // 结算这段超时时长入统计
        recordOverdue(context, s, t)
        TimerStore.save(context, s.copy(
            screenOnAt = if (s.screenOnAt > 0) s.screenOnAt else t,
            overdueCounting = true,
            overdueRecordedMs = overdueRecordedAfter(context, s, t)
        ))
        // 继续排下一次超时提醒
        AlarmScheduler.scheduleOverdueReminder(context, t + overdueMs(context))
        if (shouldNotify(context)) {
            val settings = SettingsStore.load(context)
            val totalMin = (elapsedMs(context) / 60_000L).toInt()
            val overdueMin = (totalMin - settings.workMinutes).coerceAtLeast(0)
            RestNotifier.notifyOverdueReminder(context, overdueMin, totalMin)
        }
    }

    /** 进入休息（用户点通知按钮 / App 内「现在休息一下」）——此时才开始休息倒计时 */
    fun beginRest(context: Context) {
        var s = TimerStore.load(context)
        if (s.phaseResting) return
        // 跨天：开始休息前先归零（避免昨天那段随休息一并结算到新的一天）
        s = rollOverIfDayChanged(context, s, now()).also { if (it !== s) TimerStore.save(context, it) }
        val t = now()
        // v2.6.13：先冻结累计并结算「到点前那一段」，再单独结算超时增量。
        // 原实现只调 recordOverdue（它按 `curAccum - workMs` 算增量、
        // 并把基准抬到**含超时**的 curAccum），一旦到点后 user 又用了一段时间，
        // 超时增量与冻结累计的口径就会错位，后续出口会把差额再记一遍。
        val frozenState = frozen(s, t)
        recordWorkSession(context, frozenState)
        recordOverdue(context, s, t)
        TimerStore.save(
            context,
            frozenState.copy(
                phaseResting = true,
                restStart = t,
                awaitingRest = false,
                overdueCounting = false,
                overdueRecordedMs = 0L,
                tipIndex = EyeTips.nextIndex(s.tipIndex)
            )
        )
        AlarmScheduler.cancelWorkAlarm(context)
        AlarmScheduler.cancelOverdueReminder(context)
        AlarmScheduler.scheduleRestDeadline(context, t + restMs(context))
        RestNotifier.cancelReminder(context)
        // 已在休息：撤掉可能还盖着的后台全屏覆盖，避免和 App 内休息页叠两层
        RestOverlayService.stop(context)
        // 清掉上一轮「休息结束」的痕迹，并掐掉可能还在响的提示音
        RestNotifier.cancelRestEnd(context)
        RestSoundPlayer.stop()
    }

    /** 休息自然结束：记录统计、提示音收尾并衔接下一个用眼周期 */
    fun finishRest(context: Context) {
        val s = rollOverIfDayChanged(context, TimerStore.load(context), now())
        // 幂等闸门：休息页 ticker 到点与 ACTION_REST_DONE 闹钟可能同时到达，
        // 先到者落盘 phaseResting=false，后到者直接返回 —— 这也是「只响一声」的根本保证。
        if (!s.phaseResting) return
        val settings = SettingsStore.load(context)
        StatsStore.addRest(context, settings.restSeconds)
        // 提示音、振动、通知三条路径各管一件事（放在状态机里，息屏 / 应用被杀时
        // 由闹钟拉起也能生效）：
        // 1. 响声**只由 [RestSoundPlayer] 自己播**，且只播一次 —— 通知不再发声，
        //    所以「响两次」在结构上就没有第二个来源（v2.3.9 的教训：交给通知渠道播
        //    在部分 ROM 上会一声都不响，且我们既控制不了也查不出原因）；
        // 2. 振动由 [RestVibrator] 自己发，走「通知」用途，不再被系统的触摸振动设置丢掉；
        // 3. 通知固定静默，只负责在通知栏留一条痕迹。
        if (settings.restEndSoundEnabled) {
            RestSoundPlayer.play(context, settings.restEndSoundSource)
        }
        if (AppSettings.isRestEndVibrateActive(
                settings.restEndSoundEnabled,
                settings.restEndSoundVibrate
            )
        ) {
            RestVibrator.vibrateOnce(context)
        }
        RestNotifier.notifyRestEnd(context)
        endRestPhase(context, s)
    }

    /** 手动结束休息（点「我已休息好，继续工作」）——按实际休息秒数计入统计 */
    fun skipRest(context: Context) {
        val s = TimerStore.load(context)
        // 主动继续时立刻收起「休息结束」那条提醒，并掐掉还在响的提示音
        RestNotifier.cancelRestEnd(context)
        RestSoundPlayer.stop()
        if (s.phaseResting) {
            val restSec = SettingsStore.load(context).restSeconds
            val gone = if (s.restStart > 0) {
                ((now() - s.restStart) / 1000).toInt()
            } else 0
            StatsStore.addRest(context, gone.coerceIn(0, restSec))
        }
        endRestPhase(context, s)
    }

    /**
     * 延长本次休息：把休息起点往后挪 [extraSeconds] 秒，等价于给倒计时加时。
     *
     * 只在「正在休息」时生效；同时把到点闹钟重新排到新的结束时刻，
     * 否则闹钟会按旧时间把休息页收掉。
     */
    fun extendRest(context: Context, extraSeconds: Int) {
        if (extraSeconds <= 0) return
        val t = now()
        val s = rollOverIfDayChanged(context, TimerStore.load(context), t)
        if (!s.phaseResting || s.restStart <= 0L) return
        val newStart = s.restStart + extraSeconds * 1000L
        TimerStore.save(context, s.copy(restStart = newStart))
        AlarmScheduler.scheduleRestDeadline(context, newStart + restMs(context))
    }

    /** 忽略提醒 / 稍后再说：清掉提醒状态，重新起算一个用眼周期 */
    fun postponeRest(context: Context) {
        val s0 = TimerStore.load(context)
        val s = rollOverIfDayChanged(context, s0, now())
        RestNotifier.cancelReminder(context)
        val t = now()
        val screenOn = isScreenOn(context)
        // v2.6.13：先结算到点前的累计，再单算超时增量（口径互不覆盖）。
        val fr = frozen(s, t)
        recordWorkSession(context, fr)
        recordOverdue(context, s, t)
        TimerStore.save(
            context,
            s.copy(
                phaseResting = false,
                restStart = -1L,
                awaitingRest = false,
                overdueCounting = false,
                overdueRecordedMs = 0L,
            ).newSegment(at = t, screenOn = screenOn)
        )
        AlarmScheduler.cancelOverdueReminder(context)
        // 稍后再说 / 忽略：若正盖着后台全屏，一并收起
        RestOverlayService.stop(context)
        if (s.reminderEnabled && screenOn) {
            AlarmScheduler.scheduleWorkDeadline(context, t + workMs(context))
        } else {
            AlarmScheduler.cancelAll(context)
        }
    }

    /**
     * 结束休息阶段。
     * 注意：必须先落盘退出休息态，否则 startWork 路径会因 phaseResting=true 提前返回，
     * 导致界面卡在休息页（这正是「点我已休息好无反应」的根因）。
     */
    private fun endRestPhase(context: Context, state: TimerState) {
        val t = now()
        val screenOn = isScreenOn(context)
        // v2.6.16：休息结束时屏幕可能已经熄灭，用真实屏幕状态判定，
        // 否则会把休息期间的墙钟跨度算成新一段的用眼。
        recordWorkSession(context, frozen(state, t, screenActuallyOn = screenOn))
        TimerStore.save(
            context,
            state.copy(
                phaseResting = false,
                restStart = -1L,
                awaitingRest = false,
                overdueCounting = false,
                overdueRecordedMs = 0L,
            ).newSegment(at = t, screenOn = screenOn)
        )
        AlarmScheduler.cancelRestAlarm(context)
        AlarmScheduler.cancelOverdueReminder(context)
        // 退出休息态：收起后台全屏覆盖（自然结束 / 手动结束 都汇聚到这里）
        RestOverlayService.stop(context)
        if (state.reminderEnabled && screenOn) {
            AlarmScheduler.scheduleWorkDeadline(context, t + workMs(context))
        } else {
            AlarmScheduler.cancelAll(context)
        }
    }

    // ==================== 开关 / 自愈 ====================

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        val s = TimerStore.load(context)
        if (enabled) {
            if (s.reminderEnabled) return
            val t = now()
            val screenOn = isScreenOn(context)
            TimerStore.save(
                context,
                s.copy(
                    reminderEnabled = true,
                    phaseResting = false,
                    restStart = -1L,
                    awaitingRest = false,
                    overdueCounting = false,
                    overdueRecordedMs = 0L,
                ).newSegment(at = t, screenOn = screenOn)
            )
            if (screenOn) {
                AlarmScheduler.scheduleWorkDeadline(context, t + workMs(context))
            } else {
                AlarmScheduler.cancelAll(context)
            }
        } else {
            RestNotifier.cancelReminder(context)
            TimerStore.save(context, TimerState(reminderEnabled = false))
            AlarmScheduler.cancelAll(context)
        }
    }

    /**
     * 依据持久化状态自愈：补齐「进程被杀 / 闹钟丢失 / 设备重启」期间错过的推进。
     * 应用冷启动、回到前台、设备重启后调用。
     */
    fun resync(context: Context, notifyMissed: Boolean) {
        var s = TimerStore.load(context)
        if (!s.reminderEnabled) {
            RestNotifier.cancelReminder(context)
            TimerStore.save(context, TimerState(reminderEnabled = false))
            AlarmScheduler.cancelAll(context)
            return
        }

        val t = now()
        // 跨天：自愈前先把昨天那段归零结算
        run {
            val rolled = rollOverIfDayChanged(context, s, t)
            if (rolled !== s) { TimerStore.save(context, rolled); s = rolled }
        }
        val workMs = workMs(context)
        val restMs = restMs(context)
        val screenOn = isScreenOn(context)

        // 休息中
        if (s.phaseResting) {
            val gone = if (s.restStart > 0) (t - s.restStart) / 1000L else restMs / 1000L
            if (gone >= restMs / 1000L) {
                finishRest(context)
            } else {
                AlarmScheduler.scheduleRestDeadline(context, s.restStart + restMs)
            }
            return
        }

        // 等待用户确认休息（超时态）
        if (s.awaitingRest) {
            AlarmScheduler.cancelWorkAlarm(context)
            val leftLong = !screenOn && s.offAt > 0 && t - s.offAt >= LONG_OFF_RESET_MS
            if (leftLong) {
                // 息屏期间已达「真正离开」阈值：作废上一轮的到点欠账与超时态。
                // 此时屏幕仍关着，只落盘清空状态，不排新的到点闹钟（亮屏时再起算）。
                TimerStore.save(
                    context,
                    TimerState(reminderEnabled = true)
                )
                AlarmScheduler.cancelOverdueReminder(context)
                RestNotifier.cancelReminder(context)
                RestOverlayService.stop(context)
            } else if (screenOn) {
                val resumed = if (s.screenOnAt > 0) s else s.copy(screenOnAt = t)
                recordOverdue(context, resumed, t)
                TimerStore.save(context, resumed.copy(offAt = -1L, overdueCounting = true,
                    overdueRecordedMs = overdueRecordedAfter(context, resumed, t)))
                AlarmScheduler.scheduleOverdueReminder(context, t + overdueMs(context))
            } else {
                val f = frozen(s, t)
                recordOverdue(context, s, t)
                TimerStore.save(context, f.copy(offAt = t, overdueCounting = false,
                    overdueRecordedMs = overdueRecordedAfter(context, s, t)))
                AlarmScheduler.cancelOverdueReminder(context)
            }
            return
        }

        if (!screenOn) {
            // 息屏：冻结累计并撤销唤醒源
            // v2.6.16：明确传入「屏幕不亮」，拦截陈旧的 screenOnAt。
            val f = frozen(s, t, screenActuallyOn = false).copy(offAt = t)
            TimerStore.save(context, f)
            AlarmScheduler.cancelWorkAlarm(context)
            val remaining = workMs - f.workAccumMs
            if (remaining > 0) AlarmScheduler.scheduleWorkCheckFallback(context, t + remaining)
            return
        }

        // 亮屏：校正累计值并确保到点闹钟存在
        if (s.screenOnAt <= 0) {
            val leftLong = s.offAt > 0 && t - s.offAt >= LONG_OFF_RESET_MS
            if (leftLong || s.workAccumMs <= 0L) {
                // v2.6.17：新段起点必须用「此刻」，否则下面 scheduleWorkDeadline
                // 用的是 t + workMs（此刻 + 目标），而新段屏幕起点若沿用旧值，
                // 下一次到点结算就会把中间的息屏跨度算成用眼。
                TimerStore.save(context, s.newSegment(at = t, screenOn = true))
                AlarmScheduler.scheduleWorkDeadline(context, t + workMs)
                return
            }
            // v2.6.13：与 onScreenOn 一致 —— 续算前把已结算基准对齐到冻结累计，
            // 否则旧基准会在续算段里再记一遍（统计虚高根因）。
            TimerStore.save(
                context,
                s.copy(
                    screenOnAt = t,
                    offAt = -1L,
                    recordedAccumMs = s.workAccumMs,
                    recordedGeneration = s.accumGeneration
                )
            )
        }

        val elapsed = elapsedMs(context)
        if (elapsed >= workMs) {
            onWorkDeadline(context, notify = notifyMissed)
        } else {
            AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - elapsed))
        }
    }
}