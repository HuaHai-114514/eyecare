package com.java.myapplication.timer

import android.content.Context
import android.os.PowerManager
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.EyeTips
import com.java.myapplication.data.SettingsStore
import com.java.myapplication.data.StatsStore
import com.java.myapplication.data.TimerState
import com.java.myapplication.data.TimerStore
import com.java.myapplication.notify.RestNotifier
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
    private fun recordWorkSession(context: Context, state: TimerState) {
        val seconds = (state.workAccumMs / 1000L).toInt()
        // 过滤掉抖动产生的一两秒噪声
        if (seconds < MIN_RECORD_SECONDS) return
        StatsStore.addWorkSession(context, seconds)
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
        val s = TimerStore.load(context)
        if (!s.reminderEnabled) return
        if (s.phaseResting) return
        if (s.awaitingRest) {
            TimerStore.save(context, s.copy(offAt = -1L))
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
            // 真正离开过：从 0 起算
            TimerStore.save(context, s.copy(workAccumMs = 0L, screenOnAt = t, offAt = -1L))
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
        // 等待用户确认休息：保留通知，等用户回来处理
        if (s.awaitingRest) return

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
        val s = TimerStore.load(context)
        if (!s.reminderEnabled) return
        if (s.phaseResting) return
        if (s.awaitingRest) return

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

        TimerStore.save(
            context,
            frozen(s, t).copy(workAccumMs = workMs, awaitingRest = true)
        )
        recordWorkSession(context, frozen(s, t))
        AlarmScheduler.cancelWorkAlarm(context)
        if (notify && shouldNotify(context)) {
            // 一次 load 取两个值（原来 load 两次），并把真实间隔传给通知文案
            val settings = SettingsStore.load(context)
            RestNotifier.notifyRestReminder(context, settings.workMinutes, settings.restSeconds)
        }
    }

    /** 进入休息（用户点通知按钮 / App 内「现在休息一下」）——此时才开始休息倒计时 */
    fun beginRest(context: Context) {
        val s = TimerStore.load(context)
        if (s.phaseResting) return
        val t = now()
        TimerStore.save(
            context,
            frozen(s, t).copy(
                phaseResting = true,
                restStart = t,
                awaitingRest = false,
                tipIndex = EyeTips.nextIndex(s.tipIndex)
            )
        )
        AlarmScheduler.cancelWorkAlarm(context)
        AlarmScheduler.scheduleRestDeadline(context, t + restMs(context))
        RestNotifier.cancelReminder(context)
        // 清掉上一轮「休息结束」的痕迹，并掐掉可能还在响的提示音
        RestNotifier.cancelRestEnd(context)
        RestSoundPlayer.stop()
    }

    /** 休息自然结束：记录统计、提示音收尾并衔接下一个用眼周期 */
    fun finishRest(context: Context) {
        val s = TimerStore.load(context)
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
        val s = TimerStore.load(context)
        RestNotifier.cancelReminder(context)
        val t = now()
        val screenOn = isScreenOn(context)
        recordWorkSession(context, frozen(s, t))
        TimerStore.save(
            context,
            s.copy(
                phaseResting = false,
                restStart = -1L,
                awaitingRest = false,
                workAccumMs = 0L,
                screenOnAt = if (screenOn) t else -1L,
                offAt = if (screenOn) -1L else t
            )
        )
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
                workAccumMs = 0L,
                screenOnAt = if (screenOn) t else -1L,
                offAt = if (screenOn) -1L else t
            )
        )
        AlarmScheduler.cancelRestAlarm(context)
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
                    workAccumMs = 0L,
                    screenOnAt = if (screenOn) t else -1L,
                    offAt = if (screenOn) -1L else t
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
        val s = TimerStore.load(context)
        if (!s.reminderEnabled) {
            RestNotifier.cancelReminder(context)
            TimerStore.save(context, TimerState(reminderEnabled = false))
            AlarmScheduler.cancelAll(context)
            return
        }

        val t = now()
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

        // 等待用户确认休息：保持提醒，无需闹钟
        if (s.awaitingRest) {
            if (screenOn) TimerStore.save(context, s.copy(offAt = -1L))
            AlarmScheduler.cancelWorkAlarm(context)
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
                TimerStore.save(context, s.copy(workAccumMs = 0L, screenOnAt = t, offAt = -1L))
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