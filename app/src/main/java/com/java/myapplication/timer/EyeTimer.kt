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

/**
 * 用眼/休息状态机的唯一事实来源。
 *
 * 计时语义（v1.8 · 累计亮屏用眼模型）：
 * 1. 只累计「亮屏使用」的时长：亮屏开始计时，息屏立即暂停累计并撤销到点闹钟
 *    （息屏期间不持有任何唤醒源，零唤醒、最省电）。
 * 2. 短暂息屏（< [LONG_OFF_RESET_MS]）视为「还在用」，回来后续算；
 *    长时间息屏视为「真正离开」，再次亮屏时用眼时长从 0 重新起算。
 * 3. 工作到点只发提醒通知，不自动开始休息；用户点通知的「开始休息」后才起算休息倒计时。
 *
 * 超时语义（v2.4.0）：
 * 到点后若用户一直不开始休息，进入「超时用眼」态：
 * - 超时时长**继续累计**并计入用眼统计（不再冻结在到点值）；
 * - 每隔 [AppSettings.overdueRemindMinutes] 分钟重复提醒一次，直到开始休息；
 * - 超时期间息屏 = 暂停累计 + 撤销超时闹钟；亮屏后若仍未休息则恢复超时累计与提醒。
 */
object EyeTimer {

    /** 息屏超过该时长视为「真正离开」，再次亮屏时累计清零（可调） */
    const val LONG_OFF_RESET_MS = 5 * 60 * 1000L

    /** 低于该秒数的用眼片段视为抖动噪声，不计入统计 */
    private const val MIN_RECORD_SECONDS = 5

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

    /** 当前周期已累计的亮屏用眼毫秒 = 已落盘累计 + 本次亮屏已过去时长 */
    fun elapsedMs(context: Context): Long {
        val s = TimerStore.load(context)
        return if (s.screenOnAt > 0) {
            s.workAccumMs + (now() - s.screenOnAt).coerceAtLeast(0L)
        } else {
            s.workAccumMs
        }
    }

    /** 把「本次亮屏已过去的时间」结算进累计值，并停止累计（进入暂停/冻结态） */
    private fun frozen(s: TimerState, at: Long): TimerState {
        val acc = if (s.screenOnAt > 0) {
            s.workAccumMs + (at - s.screenOnAt).coerceAtLeast(0L)
        } else {
            s.workAccumMs
        }
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
        val curAccum = accumulatedAt(s, at)
        val fullSpan = (at - segStart).coerceAtLeast(1L)
        val beforeSpan = (todayStart - segStart).coerceIn(0L, fullSpan)
        val beforeMs = (curAccum.toDouble() * (beforeSpan.toDouble() / fullSpan.toDouble())).toLong()
        val beforeSec = (beforeMs / 1000L).toInt()
        if (beforeSec >= MIN_RECORD_SECONDS) {
            // 归属起点日：整段（0 点前的这一截）记到昨天
            StatsStore.addWorkSession(context, beforeSec, startWallMs = segStart)
        }

        // 归零并从「今天 0 点」重新起算：
        // - workAccumMs 只保留 0 点之后到 at 的用量（即 curAccum - beforeMs）
        // - screenOnAt 若之前 >0（正在累计）则保持「正在累计」：设为 at
        // - 段起点重置为「今天 0 点」
        val afterMs = (curAccum - beforeMs).coerceAtLeast(0L)
        return s.copy(
            workAccumMs = afterMs,
            screenOnAt = if (s.screenOnAt > 0) at else -1L,
            accumStartWallMs = todayStart
        )
    }

    private fun recordWorkSession(context: Context, state: TimerState) {
        val seconds = (state.workAccumMs / 1000L).toInt()
        // 过滤掉抖动产生的一两秒噪声
        if (seconds < MIN_RECORD_SECONDS) return
        // v2.4.x：带本段累计的墙钟起点，让跨 0 点的时长能按天切分到各自日期。
        // 起点未知(-1)时传入 -1，StatsStore 会退化为「全部记到当天」（兼容旧数据）。
        StatsStore.addWorkSession(context, seconds, startWallMs = state.accumStartWallMs)
    }

    /**
     * 结算「超时用眼」增量（v2.4.0）。
     *
     * 到点后未休息的时间里，累计值会超过 workMs。这里只把**超出到点值的部分**
     * 写入统计，并用 [TimerState.overdueRecordedMs] 记录已结算的毫秒数去重，
     * 避免每次 tick / 重复提醒把同一段时长反复计入。
     */
    private fun recordOverdue(context: Context, s: TimerState, at: Long) {
        if (!s.overdueCounting) return
        val curAccum = accumulatedAt(s, at)
        val deltaSec = overdueDeltaSeconds(curAccum, workMs(context), s.overdueRecordedMs)
        if (deltaSec < MIN_RECORD_SECONDS) return
        // 超时段的墙钟起点：上回到点时刻（screenOnAt，到点时被重置为到点时刻），
        // 若未知则用本段累计起点兜底。
        val overdueStart = if (s.screenOnAt > 0) s.screenOnAt else s.accumStartWallMs
        StatsStore.addWorkSession(context, deltaSec, startWallMs = overdueStart)
    }

    /** 把「已落盘累计 + 本次亮屏已过去时长」算成当前总累计毫秒（纯函数，便于测试） */
    internal fun accumulatedAt(s: TimerState, at: Long): Long =
        if (s.screenOnAt > 0) {
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
        val curAccum = accumulatedAt(s, at)
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
     * - 休息或等待确认休息中：不打断；
     * - 同一亮屏会话（进程重启等）：继续累计，仅确保闹钟存在；
     * - 新会话：长时间离开则清零重算，短暂息屏则续算剩余时间。
     */
    fun onScreenOn(context: Context) {
        var s = TimerStore.load(context)
        if (!s.reminderEnabled) return
        if (s.phaseResting) return
        // 跨天：亮屏时先把昨天那段结算掉、今天从 0 重新累计
        s = rollOverIfDayChanged(context, s, now()).also { if (it !== s) TimerStore.save(context, it) }
        if (s.awaitingRest) {
            // 超时用眼态：亮屏则恢复持续累计，并重排超时提醒闹钟（v2.4.0）
            val t = now()
            val resumed = if (s.screenOnAt > 0) s else s.copy(screenOnAt = t)
            TimerStore.save(context, resumed.copy(offAt = -1L, overdueCounting = true))
            AlarmScheduler.scheduleOverdueReminder(context, t + overdueMs(context))
            return
        }

        val t = now()
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

        val leftLong = s.offAt > 0 && t - s.offAt >= LONG_OFF_RESET_MS
        if (leftLong || s.workAccumMs <= 0L) {
            // 真正离开过：从 0 起算（新段起点 = 本次亮屏时刻）
            TimerStore.save(context, s.copy(workAccumMs = 0L, screenOnAt = t, offAt = -1L,
                accumStartWallMs = t))
            AlarmScheduler.scheduleWorkDeadline(context, t + workMs)
        } else {
            // 短暂息屏：续算剩余时间
            TimerStore.save(context, s.copy(screenOnAt = t, offAt = -1L))
            AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - s.workAccumMs))
        }
    }

    /** 息屏：暂停累计并撤销到点闹钟（息屏期间不唤醒设备） */
    fun onScreenOff(context: Context) {
        val s = TimerStore.load(context)
        if (!s.reminderEnabled) {
            AlarmScheduler.cancelAll(context)
            return
        }

        val t = now()
        val f = frozen(s, t)
        TimerStore.save(context, f.copy(offAt = t))
        AlarmScheduler.cancelWorkAlarm(context)
        // 息屏时若已累计出可观用眼时长，视为本次用眼周期结束，结算入统计
        if (!s.phaseResting && !s.awaitingRest) {
            recordWorkSession(context, f)
        }

        // 休息进行中：锁屏不打断，保留结束闹钟
        if (s.phaseResting) {
            AlarmScheduler.scheduleRestDeadline(context, s.restStart + restMs(context))
            return
        }
        // 等待用户确认休息（超时态）：息屏 = 暂停累计 + 撤销超时闹钟（v2.4.0）
        if (s.awaitingRest) {
            recordOverdue(context, s, t)
            TimerStore.save(context, f.copy(offAt = t, overdueCounting = false,
                overdueRecordedMs = overdueRecordedAfter(context, s, t)))
            AlarmScheduler.cancelOverdueReminder(context)
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
        if (isScreenOn(context)) onScreenOn(context) else onScreenOff(context)
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
        val elapsed = elapsedMs(context)

        if (elapsed < workMs) {
            // 提前触发（如兜底闹钟）：重排到真正到点
            AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - elapsed))
            return
        }
        if (!isScreenOn(context)) {
            // 息屏：冻结累计，等下次亮屏重算
            TimerStore.save(context, frozen(s, t).copy(offAt = t))
            AlarmScheduler.cancelWorkAlarm(context)
            return
        }

        // 到点：进入「等待休息 + 超时累计」态。
        // v2.4.0 起不再把累计冻结在 workMs，而是保留 screenOnAt 继续走动，
        // 这样用户不休息期间的时间会照常累计并计入统计（修复统计漏算）。
        val deadlineState = s.copy(
            workAccumMs = workMs,
            screenOnAt = t,
            offAt = -1L,
            awaitingRest = true,
            overdueCounting = true,
            overdueRecordedMs = 0L,
            // 到点前的那段在下一行被 recordWorkSession 结算；
            // 之后累计的「超时段」从 t 起算，故段起点重置为 t。
            accumStartWallMs = t
        )
        TimerStore.save(context, deadlineState)
        // 到点这一轮先结算到点前的 workMs
        recordWorkSession(context, TimerState(workAccumMs = workMs))
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
            val f = frozen(s, t)
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
        // 超时态下开始休息：先把未结算的超时时长计入统计，再清标记
        recordOverdue(context, s, t)
        TimerStore.save(
            context,
            frozen(s, t).copy(
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

    /** 忽略提醒 / 稍后再说：清掉提醒状态，重新起算一个用眼周期 */
    fun postponeRest(context: Context) {
        val s0 = TimerStore.load(context)
        val s = rollOverIfDayChanged(context, s0, now())
        RestNotifier.cancelReminder(context)
        val t = now()
        val screenOn = isScreenOn(context)
        recordWorkSession(context, frozen(s, t))
        recordOverdue(context, s, t)
        TimerStore.save(
            context,
            s.copy(
                phaseResting = false,
                restStart = -1L,
                awaitingRest = false,
                overdueCounting = false,
                overdueRecordedMs = 0L,
                workAccumMs = 0L,
                screenOnAt = if (screenOn) t else -1L,
                offAt = if (screenOn) -1L else t,
                accumStartWallMs = if (screenOn) t else -1L
            )
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
        recordWorkSession(context, frozen(state, t))
        TimerStore.save(
            context,
            state.copy(
                phaseResting = false,
                restStart = -1L,
                awaitingRest = false,
                overdueCounting = false,
                overdueRecordedMs = 0L,
                workAccumMs = 0L,
                screenOnAt = if (screenOn) t else -1L,
                offAt = if (screenOn) -1L else t,
                accumStartWallMs = if (screenOn) t else -1L
            )
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
                    workAccumMs = 0L,
                    screenOnAt = if (screenOn) t else -1L,
                    offAt = if (screenOn) -1L else t,
                    accumStartWallMs = if (screenOn) t else -1L
                )
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

        // 等待用户确认休息（超时态）：保持提醒，并恢复超时累计与循环闹钟
        if (s.awaitingRest) {
            AlarmScheduler.cancelWorkAlarm(context)
            if (screenOn) {
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
            val f = frozen(s, t).copy(offAt = t)
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
                TimerStore.save(context, s.copy(workAccumMs = 0L, screenOnAt = t, offAt = -1L,
                    accumStartWallMs = t))
                AlarmScheduler.scheduleWorkDeadline(context, t + workMs)
                return
            }
            TimerStore.save(context, s.copy(screenOnAt = t, offAt = -1L))
        }

        val elapsed = elapsedMs(context)
        if (elapsed >= workMs) {
            onWorkDeadline(context, notify = notifyMissed)
        } else {
            AlarmScheduler.scheduleWorkDeadline(context, t + (workMs - elapsed))
        }
    }
}