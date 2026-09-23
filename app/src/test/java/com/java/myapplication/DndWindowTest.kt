package com.java.myapplication

import com.java.myapplication.data.AppSettings
import com.java.myapplication.ui.DndWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免打扰时段判断的纯逻辑测试（v2.3.6 新增）。
 *
 * 不需要 Context / 不需要真机，直接 `./gradlew testDebugUnitTest` 就能跑。
 * 重点覆盖两个最容易写错的地方：区间边界（含头不含尾）与跨午夜。
 */
class DndWindowTest {

    private fun settings(
        enabled: Boolean = true,
        start: Int,
        end: Int
    ) = AppSettings(
        dndEnabled = enabled,
        dndStartMinute = start,
        dndEndMinute = end
    )

    private fun hm(h: Int, m: Int) = h * 60 + m

    @Test
    fun `开关关闭时永远不在免打扰内`() {
        val s = settings(enabled = false, start = hm(12, 0), end = hm(14, 0))
        assertFalse(DndWindow.isInDndWindow(s, hm(12, 30)))
        assertFalse(DndWindow.isInDndWindow(s, hm(13, 59)))
    }

    @Test
    fun `普通区间含头不含尾`() {
        val s = settings(start = hm(12, 0), end = hm(14, 0))
        assertFalse("11:59 应在区间外", DndWindow.isInDndWindow(s, hm(11, 59)))
        assertTrue("12:00 是起点，应包含", DndWindow.isInDndWindow(s, hm(12, 0)))
        assertTrue("13:59 仍在区间内", DndWindow.isInDndWindow(s, hm(13, 59)))
        assertFalse("14:00 是终点，应排除", DndWindow.isInDndWindow(s, hm(14, 0)))
    }

    @Test
    fun `跨午夜区间判断正确`() {
        val s = settings(start = hm(23, 0), end = hm(7, 0))
        assertTrue("23:00 是起点", DndWindow.isInDndWindow(s, hm(23, 0)))
        assertTrue("23:30 在区间内", DndWindow.isInDndWindow(s, hm(23, 30)))
        assertTrue("00:30 在区间内", DndWindow.isInDndWindow(s, hm(0, 30)))
        assertTrue("06:59 在区间内", DndWindow.isInDndWindow(s, hm(6, 59)))
        assertFalse("07:00 是终点，应排除", DndWindow.isInDndWindow(s, hm(7, 0)))
        assertFalse("12:00 不在区间内", DndWindow.isInDndWindow(s, hm(12, 0)))
    }

    @Test
    fun `起止相等视为不启用`() {
        val s = settings(start = hm(12, 0), end = hm(12, 0))
        assertFalse(DndWindow.isInDndWindow(s, hm(12, 0)))
        assertFalse(DndWindow.isInDndWindow(s, hm(0, 0)))
        assertFalse(DndWindow.isInDndWindow(s, hm(23, 59)))
    }
}