package com.java.myapplication.ui

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.EyeTips
import com.java.myapplication.data.SettingsStore
import com.java.myapplication.data.StatsStore
import com.java.myapplication.data.TimerState
import com.java.myapplication.data.TimerStore
import com.java.myapplication.timer.AlarmScheduler
import com.java.myapplication.timer.EyeTimer
import com.java.myapplication.timer.SitReminder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 用眼阶段：IDLE 计时中 / AWAITING 到点等待用户确认休息 / RESTING 休息倒计时中
enum class RestPhase { IDLE, AWAITING, RESTING }

/**
 * 界面状态层：只负责把 EyeTimer 的持久化状态实时呈现到 Compose。
 * 读数每次按「已落盘累计 + 本次亮屏已过去时长」重算，不承担计时职责；
 * 进程冻结/被回收期间流逝的亮屏时长在回到界面时一次性补齐。
 */
class EyeCareViewModel : ViewModel() {

    var settings by mutableStateOf(AppSettings())
        private set

    // 当前周期已累计的亮屏用眼秒数
    var elapsedSeconds by mutableIntStateOf(0)
        private set

    // 当前阶段
    var phase by mutableStateOf(RestPhase.IDLE)
        private set

    // 剩余休息秒数
    var restRemaining by mutableIntStateOf(0)
        private set

    // 当前展示的健康知识
    var currentTip by mutableStateOf(EyeTips.random())
        private set

    // 今日休息次数 / 时长
    var todayCount by mutableIntStateOf(0)
        private set
    var todaySeconds by mutableIntStateOf(0)
        private set
    // 提醒开关
    var reminderEnabled by mutableStateOf(true)
        private set

    // 今日用眼总时长（秒）/ 最长连续用眼（秒）
    var todayWorkSeconds by mutableIntStateOf(0)
        private set
    var todayLongestStreak by mutableIntStateOf(0)
        private set

    private var tickerJob: Job? = null

    // 息屏暂停提示（纯派生值，不再重复计算存储）
    val pausedByScreenOff: Boolean
        get() = reminderEnabled && phase == RestPhase.IDLE && !screenOnNow

    // 最近一次同步到的屏幕状态（供 pausedByScreenOff 使用）
    private var screenOnNow: Boolean = true

    /** 自动夜间模式开关（供 MainActivity 的 Compose 主题实时读取） */
    val autoNightModeState: State<Boolean> =
        derivedStateOf { settings.autoNightMode }

    /** 免打扰时段内应当静音（供 RestNotifier 判断） */
    val inDndWindow: Boolean
        get() = DndWindow.isInDndWindow(settings, StatsStore.minuteOfDay())

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        tickerJob = null
    }

    fun init(context: Context) {
        settings = SettingsStore.load(context)
        todayCount = StatsStore.todayRestCount(context)
        todaySeconds = StatsStore.todayRestSeconds(context)
        todayWorkSeconds = StatsStore.todayWorkSeconds(context)
        todayLongestStreak = StatsStore.todayLongestStreak(context)

        val state = TimerStore.load(context)
        reminderEnabled = state.reminderEnabled
        currentTip = EyeTips.byIndex(state.tipIndex)

        EyeTimer.syncScreenState(context)
        EyeTimer.resync(context, notifyMissed = true)
        syncFromStore(context)

        // 久坐提醒：应用启动时按设置重排（息屏会被 onScreenOff 撤销）
        SitReminder.resync(context)
    }

    // 界面刷新循环：每秒重算读数（不承担计时职责）
    fun startTicking(context: Context) {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                clockTick(context)
            }
        }
    }

    // 回到前台：自愈 + 立即刷新
    fun onForeground(context: Context) {
        EyeTimer.syncScreenState(context)
        EyeTimer.resync(context, notifyMissed = true)
        SitReminder.resync(context)
        syncFromStore(context)
        startTicking(context)
    }

    // 退到后台：停掉界面刷新协程（计时交给亮/灭屏广播与系统闹钟）
    fun onBackground() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // 每秒界面刷新 + 休息结束兜底自愈（避免卡在 0 秒休息页）
    fun clockTick(context: Context) {
        // 一次 tick 只读一次盘：以前这里 load → save → load → SettingsStore.load，
        // 下面 syncFromStore 还要再读一遍同样三个 store。SharedPreferences 读是内存缓存、
        // save 是整份 apply，看着便宜，但每秒都做、且正好落在动画帧上就贵了。
        val s0 = TimerStore.load(context)

        // 跨天归零：界面每秒 tick 是「0 点整」最直接的感知点，
        // 到点即把昨天那段结算掉，让「今日用眼」从 0 重新累计。
        // 只在真的跨天时落盘 —— 以前无条件 save，等于每秒写一次 pref。
        val rolled = EyeTimer.rollOverIfDayChanged(context, s0, System.currentTimeMillis())
        val state = if (rolled != s0) {
            TimerStore.save(context, rolled)
            rolled
        } else {
            s0
        }

        if (state.phaseResting && state.restStart > 0) {
            val restSec = settings.restSeconds
            if (System.currentTimeMillis() - state.restStart >= restSec * 1000L) {
                EyeTimer.finishRest(context)
            }
        }
        syncFromStore(context, state)
    }

    // 从持久化状态实时计算界面读数
    private fun syncFromStore(context: Context) {
        syncFromStore(context, TimerStore.load(context))
    }

    /**
     * 同上，但由调用方传入已经读好的计时状态 —— 每秒 tick 那条路径上
     * 刚读过一次盘，没必要为了这个函数再读一遍。
     */
    private fun syncFromStore(context: Context, s: TimerState) {
        val appSettings = SettingsStore.load(context)
        val workSec = appSettings.workMinutes * 60
        val restSec = appSettings.restSeconds
        val now = System.currentTimeMillis()
        val screenOn = EyeTimer.isScreenOn(context)

        reminderEnabled = s.reminderEnabled
        settings = appSettings
        currentTip = EyeTips.byIndex(s.tipIndex)

        val accumSec = if (s.screenOnAt > 0) {
            (s.workAccumMs + (now - s.screenOnAt)) / 1000L
        } else {
            s.workAccumMs / 1000L
        }

        screenOnNow = screenOn

        when {
            s.phaseResting -> {
                phase = RestPhase.RESTING
                val goneSec = if (s.restStart > 0) (now - s.restStart) / 1000L else restSec.toLong()
                restRemaining = (restSec - goneSec).coerceAtLeast(0L).toInt()
                // 休息期界面也显示已累计用眼（保持在目标值附近）
                elapsedSeconds = accumSec.coerceIn(0L, workSec.toLong()).toInt()
            }
            s.awaitingRest -> {
                phase = RestPhase.AWAITING
                elapsedSeconds = workSec
                restRemaining = 0
            }
            else -> {
                phase = RestPhase.IDLE
                elapsedSeconds = accumSec.coerceIn(0L, workSec.toLong()).toInt()
                restRemaining = 0
            }
        }

        val c = StatsStore.todayRestCount(context)
        val sec = StatsStore.todayRestSeconds(context)
        if (c != todayCount || sec != todaySeconds) {
            todayCount = c
            todaySeconds = sec
        }
        todayWorkSeconds = StatsStore.todayWorkSeconds(context)
        todayLongestStreak = StatsStore.todayLongestStreak(context)
    }

    // 用户点通知/应用内「开始休息」：此时才开始休息倒计时
    fun startRest(context: Context) {
        EyeTimer.beginRest(context)
        syncFromStore(context)
    }

    // 稍后再说：忽略本次提醒，重新起算一个用眼周期
    fun postponeRest(context: Context) {
        EyeTimer.postponeRest(context)
        syncFromStore(context)
    }

    // 用户手动结束休息（「我已休息好」）
    fun skipRest(context: Context) {
        EyeTimer.skipRest(context)
        syncFromStore(context)
    }

    /** 休息中想多歇一会儿：给本次休息加时 [extraSeconds] 秒 */
    fun extendRest(context: Context, extraSeconds: Int) {
        EyeTimer.extendRest(context, extraSeconds)
        syncFromStore(context)
    }

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        EyeTimer.setReminderEnabled(context, enabled)
        SitReminder.resync(context)
        syncFromStore(context)
    }

    fun updateSettings(context: Context, newSettings: AppSettings) {
        val old = settings
        settings = newSettings
        SettingsStore.save(context, newSettings)

        // 久坐提醒：开关或间隔变化时立即重排（先撤销旧的，避免多个闹钟并存）
        if (old.sitReminderEnabled != newSettings.sitReminderEnabled ||
            old.sitIntervalMinutes != newSettings.sitIntervalMinutes
        ) {
            SitReminder.resync(context)
        }

        val s = TimerStore.load(context)
        if (!s.reminderEnabled) return
        val workMs = newSettings.workMinutes * 60 * 1000L
        val now = System.currentTimeMillis()

        when {
            s.phaseResting -> {
                val restEnd = s.restStart + newSettings.restSeconds * 1000L
                if (now >= restEnd) {
                    EyeTimer.finishRest(context)
                } else {
                    AlarmScheduler.scheduleRestDeadline(context, restEnd)
                }
            }
            s.awaitingRest -> AlarmScheduler.cancelWorkAlarm(context)
            else -> {
                val elapsed = EyeTimer.elapsedMs(context)
                if (elapsed >= workMs) {
                    EyeTimer.onWorkDeadline(context, notify = true)
                } else {
                    AlarmScheduler.scheduleWorkDeadline(context, now + (workMs - elapsed))
                }
            }
        }
        syncFromStore(context)
    }

    // 格式化时间 mm:ss
    fun formatTime(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%02d:%02d".format(m, s)
    }

    // 进度 0f..1f
    val workProgress: Float
        get() {
            val total = settings.workMinutes * 60
            if (total <= 0) return 0f
            return (elapsedSeconds.toFloat() / total).coerceIn(0f, 1f)
        }

    companion object {
        /** 低于该秒数的用眼片段视为噪声，不计入统计 */
        private const val MIN_RECORD_SECONDS = 5
    }
}