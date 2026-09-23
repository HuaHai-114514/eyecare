package com.java.myapplication.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager

/**
 * 休息结束的提示音 —— 全流程**唯一**的响声来源。
 *
 * 版本演进（这条音源的路绕了三圈，记下来免得再踩）：
 * - v2.3.6 / v2.3.7：用**闹钟铃声**，靠 `USAGE_ALARM` 穿透静音；铃声本身几十秒，响个不停；
 * - v2.3.8：换成**通知铃声** + 短振动，仍然是应用自播 —— 那会儿的问题是"响两次"；
 * - v2.3.9：把发声权整个交给**通知渠道**（应用不播），想在结构上消灭第二个声源。
 *   结果通知是发出去了（id=1004、渠道参数正常），系统却一声没播 ——
 *   这条路既不受我们控制、也没法保证有效，所以放弃；
 * - v2.3.10：发声权收回应用，**只由这里播一次**；通知退化成一条永久静默的记录。
 *   于是"响两次"在结构上不可能（第二条声源根本不存在），而"到底响不响"重新变得可控。
 * - v2.3.11：真机上仍然是"一前一后两声"。查到最后发现**声源在铃声文件里**：
 *   用户的通知铃声 `WaterDrop_preview.ogg` 本身就是"嘀-嗒"两下
 *   （波形：0.03-0.05s 一团、0.08-0.09s 又一团，间隔约 40ms）。
 *   应用只播了一次、音频系统也只 `event:started` 了一次 —— 播出的内容自带两声。
 *   所以改成**优先播应用内置的单脉冲提示音**（raw/rest_end_chime.ogg，0.55s 指数衰减、
 *   全曲只有一个能量段），系统铃声降级为兜底。这样"响几声"终于由我们说了算，
 *   也不再受用户系统铃声是否多脉冲影响。
 *
 * 音源顺序（v2.3.12 起由用户选择，见 [RestSoundSource]）：
 * - `SYSTEM_RINGTONE`：仍按 通知 → 来电 → 闹钟 的优先级取系统铃声（可能多脉冲）；
 * - 内置音源：直接播对应的 `raw/` 资源；
 * - 无论选哪个，内置资源异常时都退回系统铃声 —— 最坏也有一声，不会静默失败。
 *
 * 音量与静音：
 * - 音频属性 `USAGE_NOTIFICATION`，音量跟随**通知音量**通道，与微信、短信一致；
 * - 静音模式下通知流音量为 0，自然不响 —— 这是刻意的取舍（要的是"不打扰"）。
 *
 * 两道"只响一声 / 不响太久"的保险：
 * - [play] 开头先 [stop] 掉上一次，即使被意外调用两次，也只有一个声音在响；
 * - [MAX_PLAY_SECONDS] 兜底：万一用户把通知铃声设成了几十秒的自定义音频，到点强制停止。
 */
object RestSoundPlayer {

    /** 单次播放时长上限（秒）：仅作兜底，正常提示音不到一秒 */
    private const val MAX_PLAY_SECONDS = 8

    /** 系统铃声兜底的取源优先级：通知铃声 → 来电铃声 → 闹钟铃声 */
    private val RINGTONE_TYPES = intArrayOf(
        RingtoneManager.TYPE_NOTIFICATION,
        RingtoneManager.TYPE_RINGTONE,
        RingtoneManager.TYPE_ALARM
    )

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 正在播的播放器（单例持有，便于随时掐掉） */
    @Volatile
    private var current: MediaPlayer? = null

    private val pendingRelease = Runnable { stop() }

    /**
     * 播一声提示音，音源由 [source] 指定；任何异常都吞掉，最坏情况只是没声音。
     *
     * 设置页「试听」和计时结束的正式播放走的是同一条路径 —— 试听听到什么，到点就听到什么。
     */
    fun play(context: Context, source: RestSoundSource = RestSoundSource.DEFAULT) {
        // 先停掉可能还在响的上一次：这是"只响一声"的最后一道保险
        stop()
        val player = try {
            loadFor(context, source) ?: loadSystemRingtone(context)
        } catch (_: Exception) {
            null
        } ?: return
        try {
            player.start()
            current = player
            mainHandler.removeCallbacks(pendingRelease)
            mainHandler.postDelayed(pendingRelease, MAX_PLAY_SECONDS * 1000L)
            player.setOnCompletionListener { mp -> releaseQuietly(mp) }
            player.setOnErrorListener { mp, _, _ ->
                releaseQuietly(mp)
                true
            }
        } catch (_: Exception) {
            releaseQuietly(player)
            stop()
        }
    }

    /**
     * 按 [source] 加载播放器。
     *
     * [RestSoundSource.SYSTEM_RINGTONE] 返回 `null`（交给调用处的兜底链取系统铃声）；
     * 内置音源加载失败也返回 `null`，同样退到系统铃声。
     */
    private fun loadFor(context: Context, source: RestSoundSource): MediaPlayer? {
        val res = source.rawRes ?: return null
        return try {
            MediaPlayer.create(context, res)?.apply { applyAttrs(this) }
        } catch (_: Exception) {
            null
        }
    }

    /** 立刻停下正在响的提示音（休息开始 / 用户主动继续时调用） */
    fun stop() {
        mainHandler.removeCallbacks(pendingRelease)
        val player = current ?: return
        current = null
        releaseQuietly(player)
    }

    /**
     * 兜底：按优先级找一个可用的系统铃声，并统一挂上通知音频属性。
     * 用户把某类铃声设为「无」时该类取不到，自动退到下一类。
     */
    private fun loadSystemRingtone(context: Context): MediaPlayer? {
        for (type in RINGTONE_TYPES) {
            try {
                val uri = RingtoneManager.getDefaultUri(type) ?: continue
                val player = MediaPlayer().apply {
                    setAudioAttributes(notificationAttrs())
                    setDataSource(context, uri)
                    prepare()
                }
                return player
            } catch (_: Exception) {
                // 该类铃声不可用，继续尝试下一类
            }
        }
        return null
    }

    private fun applyAttrs(player: MediaPlayer) {
        try {
            player.setAudioAttributes(notificationAttrs())
        } catch (_: Exception) {
        }
    }

    private fun notificationAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun releaseQuietly(player: MediaPlayer) {
        try {
            if (player.isPlaying) player.stop()
        } catch (_: Exception) {
        }
        try {
            player.reset()
        } catch (_: Exception) {
        }
        try {
            player.release()
        } catch (_: Exception) {
        }
    }
}
