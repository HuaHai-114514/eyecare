package com.java.myapplication

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.java.myapplication.timer.EyeTimer
import com.java.myapplication.timer.ScreenStateReceiver

/**
 * 进程入口：注册亮屏/息屏监听。
 *
 * ACTION_SCREEN_ON / ACTION_SCREEN_OFF 属于受限制的隐式广播（Android 8+ 不允许
 * Manifest 静态注册），只能通过动态注册接收；挂在 Application 上可以保证
 * 进程一旦存活就持续监听，进程被系统回收后再次启动时会自动重新注册。
 */
class EyeCareApplication : Application() {

    private val screenStateReceiver = ScreenStateReceiver()

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }

        // 同步一次真实屏幕状态：息屏中则立即暂停计时，亮屏则起算新周期
        EyeTimer.syncScreenState(this)
    }
}