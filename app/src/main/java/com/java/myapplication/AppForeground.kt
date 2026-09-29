package com.java.myapplication

/**
 * 极简的「应用是否在前台」标记（v2.4.2 新增）。
 *
 * 为什么不引入 ProcessLifecycleOwner：这里只需要一个布尔值来回答
 * 「到点该走 App 内休息页（前台）还是悬浮窗（后台）」这一个问题，
 * 为它拉一整个 lifecycle-process 依赖并不划算。MainActivity 的
 * onResume / onPause 已经足够精确地维护这个值（Activity 落后台必然走 onPause）。
 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false
}
