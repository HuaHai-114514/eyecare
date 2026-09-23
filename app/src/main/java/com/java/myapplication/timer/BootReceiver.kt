package com.java.myapplication.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 设备重启后系统闹钟全部清空，这里依据持久化状态重排闹钟，
 * 保证重启后 20-20-20 循环继续正确运转。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        EyeTimer.resync(context, notifyMissed = true)
    }
}
