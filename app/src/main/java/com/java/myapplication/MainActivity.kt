package com.java.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import com.java.myapplication.notify.RestNotifier
import com.java.myapplication.ui.EyeCareApp
import com.java.myapplication.ui.EyeCareViewModel
import com.java.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    // 与 Compose viewModel() 共享同一 Activity 作用域实例
    private val viewModel: EyeCareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 通知「开始休息」按钮：由系统代发 Activity 启动（getActivity PendingIntent），
        // 携带 ACTION_REST_NOW 进入即表示用户已确认开始休息。
        handleRestNow(intent)
        setContent {
            // 自动夜间模式开关由 ViewModel 实时推送，拨动后无需重启应用即可生效
            val autoNight by viewModel.autoNightModeState
            MyApplicationTheme(autoNight = autoNight) {
                EyeCareApp()
            }
        }
    }

    // Activity 已存在时从通知按钮再次进入（CLEAR_TOP + singleTop）走这里
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRestNow(intent)
    }

    // 点击通知「开始休息」按钮：切换计时状态到休息。
    // 界面侧由 onResume → onForeground → syncFromStore 读到 phaseResting=true 后渲染休息页。
    private fun handleRestNow(intent: Intent?) {
        if (intent?.action != RestNotifier.ACTION_REST_NOW) return
        viewModel.startRest(this)
    }

    // 回到前台：自愈补齐后台期间流逝的时间 + 立即刷新读数 + 恢复界面刷新协程
    override fun onResume() {
        super.onResume()
        viewModel.onForeground(this)
    }

    // 退到后台：停掉界面刷新协程；计时由亮/灭屏广播与系统闹钟接管
    override fun onPause() {
        super.onPause()
        viewModel.onBackground()
    }
}