package com.java.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 每秒 tick 的「读写次数」回归测试。
 *
 * 背景：`EyeCareViewModel.clockTick` 每秒执行一次，里面直接调用
 * `TimerStore.load/save`、`SettingsStore.load`、`StatsStore.today*`。
 * 报告页还叠了一层缓存键（`todayWorkSeconds to todayCount`），
 * 而 `todayWorkSeconds` 每秒都在变 —— 于是报告页每秒会重算
 * `dailySeries` + `achievementRate` + `workTrendPercent` 三遍。
 *
 * 这些在 DEBUG 构建里看不出问题（JIT 热了以后很快），但在真机上
 * 每一次 `StatsStore.loadAll` 都要把最多 90 天的 map 复制一份，
 * 每秒 7 次复制 + 1 次 SharedPreferences 写入，会稳定地吃掉主线程时间片。
 *
 * 这个测试不测量真实耗时，而是**静态锁住「每秒 tick 的调用预算」**：
 * 一旦有人再加一处每秒落盘/读盘，这里就会红。
 */
class TickerCostTest {

    private val vmSource: String by lazy {
        readSource("app/src/main/java/com/java/myapplication/ui/EyeCareViewModel.kt")
    }

    private fun readSource(relative: String): String {
        // 单测工作目录可能是仓库根，也可能是模块目录 app/，两种基准都试
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        val candidates = mutableListOf<File>()
        repeat(4) {
            dir?.let { d ->
                candidates.add(File(d, relative))
                dir = d.parentFile
            }
        }
        val hit = candidates.firstOrNull { it.exists() }
        assertTrue(
            "找不到源文件 $relative（工作目录 ${System.getProperty("user.dir")}）",
            hit != null
        )
        return hit!!.readText()
    }

    /** 取出函数的函数体（按大括号配平） */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("源码里找不到 $signature", start >= 0)
        val braceStart = src.indexOf('{', start)
        var depth = 0
        var i = braceStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(braceStart, i + 1)
                }
            }
            i++
        }
        error("$signature 的大括号没有配平")
    }

    @Test
    fun `clockTick 每秒最多落盘一次`() {
        val body = bodyOf(vmSource, "fun clockTick(")
        val saves = Regex("TimerStore\\.save\\(").findAll(body).count()
        val loads = Regex("TimerStore\\.load\\(").findAll(body).count()

        assertTrue(
            "clockTick 里 TimerStore.save 调用了 $saves 次，期望 <=1",
            saves <= 1
        )
        assertTrue(
            "clockTick 里 TimerStore.load 调用了 $loads 次，期望 <=1",
            loads <= 1
        )
    }

    @Test
    fun `clockTick 不得绕过 syncFromStore 自行读统计`() {
        val body = bodyOf(vmSource, "fun clockTick(")
        val direct = Regex("StatsStore\\.\\w+\\(").findAll(body).count()
        assertEquals(
            "clockTick 里直接调用了 StatsStore $direct 次；统计读取应集中在 syncFromStore",
            0,
            direct
        )
    }

    @Test
    fun `syncFromStore 每秒的 StatsStore 调用不超过 4 次`() {
        val body = bodyOf(vmSource, "private fun syncFromStore(")
        val calls = Regex("StatsStore\\.\\w+\\(").findAll(body).count()
        assertTrue("syncFromStore 里 StatsStore 调用了 $calls 次，期望 <=4", calls <= 4)
    }
}
