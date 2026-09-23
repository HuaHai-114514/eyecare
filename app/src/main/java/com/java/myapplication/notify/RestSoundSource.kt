package com.java.myapplication.notify

import androidx.annotation.RawRes
import com.java.myapplication.R

/**
 * 休息结束提示音的**音源**（v2.3.12 新增）。
 *
 * 背景：v2.3.11 曾把提示音**锁死**为应用内置的单脉冲音，理由是查出来「一前一后两声」
 * 出自系统铃声文件本身（水滴声 `WaterDrop_preview` 天生"嘀-嗒"两下）。锁死虽然根治了
 * "两声"，但也把"想用系统铃声"这个需求一并砍掉了 —— 用户明确希望能自己选。
 *
 * 所以这一版把音源变回**可选项**：既能跟随系统通知铃声（听感熟悉），也能从应用内置的
 * 若干音效里挑一个。两个"双音"音源是刻意提供的（`RISE` / `FALL`），它们本身就含两个
 * 音 —— 选它们就会再次听到"两声"，这是用户的知情选择，不是缺陷。
 *
 * [rawRes] 为 `null` 表示**不是内置资源**，需要走系统铃声（见 [SYSTEM_RINGTONE]）。
 */
enum class RestSoundSource(
    /** 设置页展示名 */
    val label: String,
    /** 内置音频资源；null = 使用系统通知铃声 */
    @param:RawRes val rawRes: Int?,
    /** 一行说明，讲清这个音源的特点（尤其会不会"两声"） */
    val hint: String
) {
    /** 跟随系统「通知铃声」—— 默认。注意系统铃声可能是多脉冲的（会听到两声） */
    SYSTEM_RINGTONE(
        label = "跟随系统通知铃声",
        rawRes = null,
        hint = "使用系统设置里的通知铃声；若该铃声本身是多音（如水滴声），会听到两声"
    ),

    /** 内置 · 清音：最接近 v2.3.11 的默认音，单声、清亮 */
    BUILTIN_CLEAR(
        label = "内置 · 清音",
        rawRes = R.raw.rest_end_chime,
        hint = "单声、清亮，就是 v2.3.11 的默认音"
    ),

    /** 内置 · 柔音：中音、衰减稍慢 */
    BUILTIN_SOFT(
        label = "内置 · 柔音",
        rawRes = R.raw.rest_end_soft,
        hint = "单声、中音、柔和"
    ),

    /** 内置 · 沉音：低音、更长 */
    BUILTIN_LOW(
        label = "内置 · 沉音",
        rawRes = R.raw.rest_end_low,
        hint = "单声、低音、更沉"
    ),

    /** 内置 · 上行双音：嘀-嗒 上扬，类系统铃声（**含两个音**） */
    BUILTIN_RISE(
        label = "内置 · 上行双音",
        rawRes = R.raw.rest_end_rise,
        hint = "「嘀-嗒」上扬两个音（刻意做成两声）"
    ),

    /** 内置 · 下行双音：嗒-嘀 下坠（**含两个音**） */
    BUILTIN_FALL(
        label = "内置 · 下行双音",
        rawRes = R.raw.rest_end_fall,
        hint = "「嗒-嘀」下坠两个音（刻意做成两声）"
    );

    companion object {
        /** 设置页的选项顺序（也是"声音由清到沉、先单后双"的推荐顺序） */
        val CHOICES: List<RestSoundSource> = listOf(
            SYSTEM_RINGTONE,
            BUILTIN_CLEAR,
            BUILTIN_SOFT,
            BUILTIN_LOW,
            BUILTIN_RISE,
            BUILTIN_FALL
        )

        /** 默认音源：跟随系统通知铃声（v2.3.12 起；v2.3.11 曾锁死为内置清音） */
        val DEFAULT: RestSoundSource = SYSTEM_RINGTONE

        /**
         * 从持久化的字符串还原音源。
         *
         * **任何无法识别的值都回退到 [DEFAULT]** —— 旧版本升级上来、存储被写坏、
         * 或者将来删掉某个音源时，都不会因为读不出枚举名而崩溃。
         */
        fun fromKey(key: String?): RestSoundSource =
            entries.firstOrNull { it.name == key } ?: DEFAULT

        /** 是否为内置音源（供 UI / 播放器判断，避免到处写 `rawRes != null`） */
        fun RestSoundSource.isBuiltIn(): Boolean = rawRes != null
    }
}
