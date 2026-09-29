package com.java.myapplication.notify

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 休息结束的短振动。
 *
 * ## 为什么必须显式指定 usage（v2.3.10 修的就是这个）
 *
 * v2.3.8 / v2.3.9 用的是 `vibrate(effect)` 这种不带用途的重载，它默认按
 * **USAGE_TOUCH（触摸反馈）** 记账。结果在真机上每次都被系统直接丢弃：
 *
 * ```
 * usage: TOUCH | com.java.myapplication | played: Step=300ms | ignored_for_settings
 * ```
 *
 * `ignored_for_settings` 的含义是"系统设置忽略了这个用途的振动"——手机上手感/触摸振动
 * 是关着的，于是振动从来没真正发生过（v2.3.8 时代也一样，只是那时有铃声盖着没察觉）。
 *
 * 修法是逐层把用途声明成 **USAGE_NOTIFICATION（通知振动）**：
 * - API 33+：`vibrate(effect, VibrationAttributes.createForUsage(...))`
 * - API 26-32：`vibrate(effect, AudioAttributes{USAGE_NOTIFICATION})`
 * - API 24-25：无用途概念，原始重载即可
 *
 * 副作用（与整体取舍一致）：系统里关掉"通知振动"时它也不会振。
 * 刻意不给 `USAGE_ALARM` —— 休息结束要的是"和通知一样"的打扰级别，不越过静音。
 */
object RestVibrator {

    /** 振动时长（毫秒）：一次短促的「嗡」，不做节奏型长振动 */
    private const val VIBRATE_MS = 300L

    /** 一次短振动；设备无马达、系统关闭通知振动、取不到服务时都静默跳过 */
    fun vibrateOnce(context: Context) {
        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(
                    VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE
                )
                // 先按「通知」用途振；若该用途被系统设置忽略、没振成，
                // 再退回不带用途的原始重载（默认按触摸用途）做最后兜底。
                // 这样即使系统里「通知振动」关着，也还能振一下，不至于毫无反馈。
                if (!vibrateWithUsage(vibrator, effect)) {
                    vibrateDefault(vibrator, effect)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATE_MS)
            }
        } catch (_: Exception) {
            // 最坏情况只是没震动，绝不因此打断休息结束流程
        }
    }

    /**
     * 按系统版本选择带「通知」用途的振动重载。
     * @return true 表示重载调用未抛异常（不代表系统一定真的振了，仅用于决定是否兜底）
     */
    private fun vibrateWithUsage(vibrator: Vibrator, effect: VibrationEffect): Boolean {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION)
                )
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        effect,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 最后兜底：不带任何用途的原始振动重载（默认触摸用途） */
    private fun vibrateDefault(vibrator: Vibrator, effect: VibrationEffect) {
        try {
            vibrator.vibrate(effect)
        } catch (_: Exception) {
        }
    }

    /**
     * API 31+ 优先走 VibratorManager；若拿不到（部分 ROM 会返回 null）
     * 则回退老的 VIBRATOR_SERVICE，避免「拿不到服务 → 直接不震」。
     */
    private fun resolveVibrator(context: Context): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val v = vm?.defaultVibrator
            if (v != null) return v
            // 回退：仍尝试老接口（部分 ROM VibratorManager 不可用）
        }
        @Suppress("DEPRECATION")
        return context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}