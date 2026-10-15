package com.java.myapplication

import com.java.myapplication.timer.ScreenStateReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 亮屏广播分发测试（v2.6.21 新增）。
 *
 * 背景（用户实测到的回归）：「息屏一段时间后亮屏，用眼计时没有从 0 重新开始」，
 * 而且**时好时坏** —— 锁屏后再解锁时正常，未锁屏直接亮屏时不生效。
 *
 * 根因：`ACTION_USER_PRESENT` **只在「锁屏 → 解锁」时才发**。若息屏前本就没有锁屏
 * （看视频 / 读长文时自动息屏），亮屏时系统**只发 `ACTION_SCREEN_ON`**。
 * 曾一度把 `SCREEN_ON` 改成「只同步久坐提醒、不调 EyeTimer.onScreenOn」，
 * 于是归零重算那条路径在这类场景下一次都不执行。
 *
 * 这里锁住的事实：**两个 action 都必须被判定为「用户回到屏幕前」**。
 * 若日后有人再试图只保留 `USER_PRESENT`，本测试会立刻失败。
 *
 * 注：`Intent.ACTION_*` 是 Android 框架常量，纯 JVM 单测里取不到（android.jar 桩会
 * 抛异常 / 返回 null），故此处直接使用平台字面量 —— 它们属于 Android 公开稳定契约，
 * 不会随版本变化。
 */
class ScreenEdgeDispatchTest {

    private val screenOn = "android.intent.action.SCREEN_ON"
    private val userPresent = "android.intent.action.USER_PRESENT"
    private val screenOff = "android.intent.action.SCREEN_OFF"

    @Test
    fun `SCREEN_ON 必须触发亮屏会话`() {
        // 未锁屏的亮屏只有这一条广播 —— 漏掉它就等于「长息屏后不归零」
        assertTrue(
            "SCREEN_ON 未触发 onScreenOn：未锁屏亮屏将完全不走归零重算",
            ScreenStateReceiver.startsScreenOnSession(screenOn)
        )
    }

    @Test
    fun `USER_PRESENT 必须触发亮屏会话`() {
        assertTrue(
            "USER_PRESENT 未触发 onScreenOn：锁屏解锁后不会恢复计时",
            ScreenStateReceiver.startsScreenOnSession(userPresent)
        )
    }

    @Test
    fun `SCREEN_OFF 不得触发亮屏会话`() {
        assertFalse(
            "息屏广播被当成亮屏处理：会导致归零判定与边沿检测彻底错乱",
            ScreenStateReceiver.startsScreenOnSession(screenOff)
        )
    }

    @Test
    fun `无关广播不得触发亮屏会话`() {
        assertFalse(ScreenStateReceiver.startsScreenOnSession(null))
        assertFalse(ScreenStateReceiver.startsScreenOnSession("android.intent.action.BOOT_COMPLETED"))
    }
}
