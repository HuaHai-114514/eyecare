package com.java.myapplication

import com.java.myapplication.data.AppSettings
import com.java.myapplication.notify.RestSoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置项里「当天 0 点起的分钟数」与 HH:mm 文本互转的纯逻辑测试（v2.3.6 新增）。
 *
 * 这两个函数直接决定免打扰时段在界面上的显示与保存：
 * 显示错 → 用户看不懂；解析错 → 用户改了时间却没生效。
 */
class AppSettingsTimeTest {

    // ==================== formatMinuteOfDay ====================

    @Test
    fun `格式化边界值`() {
        assertEquals("00:00", AppSettings.formatMinuteOfDay(0))
        assertEquals("12:00", AppSettings.formatMinuteOfDay(720))
        assertEquals("23:59", AppSettings.formatMinuteOfDay(1439))
    }

    @Test
    fun `格式化越界值自动收敛到合法范围`() {
        // 负数收敛到 0 点
        assertEquals("00:00", AppSettings.formatMinuteOfDay(-1))
        assertEquals("00:00", AppSettings.formatMinuteOfDay(-9999))
        // 超出一天收敛到 23:59
        assertEquals("23:59", AppSettings.formatMinuteOfDay(1440))
        assertEquals("23:59", AppSettings.formatMinuteOfDay(99999))
    }

    // ==================== parseMinuteOfDay ====================

    @Test
    fun `解析正常时间`() {
        assertEquals(0, AppSettings.parseMinuteOfDay("00:00", -1))
        assertEquals(720, AppSettings.parseMinuteOfDay("12:00", -1))
        assertEquals(1439, AppSettings.parseMinuteOfDay("23:59", -1))
    }

    @Test
    fun `解析容忍非零填充与空格`() {
        assertEquals(9 * 60 + 5, AppSettings.parseMinuteOfDay("9:5", -1))
        assertEquals(720, AppSettings.parseMinuteOfDay("  12:00  ", -1))
    }

    @Test
    fun `非法输入一律回退`() {
        val fallback = 720
        assertEquals(fallback, AppSettings.parseMinuteOfDay("25:00", fallback))
        assertEquals(fallback, AppSettings.parseMinuteOfDay("12:60", fallback))
        assertEquals(fallback, AppSettings.parseMinuteOfDay("abc", fallback))
        assertEquals(fallback, AppSettings.parseMinuteOfDay("12", fallback))
        assertEquals(fallback, AppSettings.parseMinuteOfDay("12:00:30", fallback))
        assertEquals(fallback, AppSettings.parseMinuteOfDay("", fallback))
        assertEquals(fallback, AppSettings.parseMinuteOfDay("-1:00", fallback))
    }

    // ==================== describeRestEndSound（v2.3.8） ====================

    @Test
    fun `提示音文案三分支`() {
        // 关闭时不应出现「音源/振动」之类的措辞，避免用户以为还响着
        assertEquals("已关闭", AppSettings.describeRestEndSound(enabled = false, vibrate = true))
        assertEquals("已关闭", AppSettings.describeRestEndSound(enabled = false, vibrate = false))
        // 开启时区分是否振动，并带上音源名（v2.3.12 起默认显示「跟随系统通知铃声」）
        assertEquals(
            "已开启（跟随系统通知铃声 + 振动）",
            AppSettings.describeRestEndSound(enabled = true, vibrate = true)
        )
        assertEquals(
            "已开启（跟随系统通知铃声）",
            AppSettings.describeRestEndSound(enabled = true, vibrate = false)
        )
        // 指定音源时应显示该音源名
        assertEquals(
            "已开启（内置 · 清音 + 振动）",
            AppSettings.describeRestEndSound(
                enabled = true,
                vibrate = true,
                sourceLabel = "内置 · 清音"
            )
        )
    }

    // ==================== RestSoundSource（v2.3.12） ====================

    @Test
    fun `音源选项与默认值`() {
        // 默认仍是「跟随系统通知铃声」
        assertEquals(RestSoundSource.SYSTEM_RINGTONE, RestSoundSource.DEFAULT)
        // 设置页共 6 项，且顺序里默认项排第一
        assertEquals(6, RestSoundSource.CHOICES.size)
        assertEquals(RestSoundSource.SYSTEM_RINGTONE, RestSoundSource.CHOICES.first())
        // 5 个内置音源都有 raw 资源，系统音源没有
        assertEquals(5, RestSoundSource.CHOICES.count { it.rawRes != null })
        assertEquals(null, RestSoundSource.SYSTEM_RINGTONE.rawRes)
    }

    @Test
    fun `音源从键还原_未知值回退默认`() {
        // 正常持久化往返
        RestSoundSource.entries.forEach { s ->
            assertEquals(s, RestSoundSource.fromKey(s.name))
        }
        // null / 空串 / 乱值 / 已删除的旧枚举名，一律回退默认，不崩
        assertEquals(RestSoundSource.DEFAULT, RestSoundSource.fromKey(null))
        assertEquals(RestSoundSource.DEFAULT, RestSoundSource.fromKey(""))
        assertEquals(RestSoundSource.DEFAULT, RestSoundSource.fromKey("NOT_A_SOURCE"))
        assertEquals(RestSoundSource.DEFAULT, RestSoundSource.fromKey("BUILTIN_REMOVED"))
    }

    @Test
    fun `提示音与振动默认都开启`() {
        // 声 + 振是休息结束「必须被察觉」的默认保障
        assertTrue(AppSettings().restEndSoundEnabled)
        assertTrue(AppSettings().restEndSoundVibrate)
    }

    // ==================== isRestEndVibrateActive（v2.3.9） ====================

    @Test
    fun `提示音关闭时不再振动`() {
        // 关掉提示音 = 关掉这次打扰，不该只关声音还震一下
        assertFalse(AppSettings.isRestEndVibrateActive(enabled = false, vibrate = true))
        assertFalse(AppSettings.isRestEndVibrateActive(enabled = false, vibrate = false))
    }

    @Test
    fun `提示音开启时振动跟随子开关`() {
        assertTrue(AppSettings.isRestEndVibrateActive(enabled = true, vibrate = true))
        assertFalse(AppSettings.isRestEndVibrateActive(enabled = true, vibrate = false))
    }
}