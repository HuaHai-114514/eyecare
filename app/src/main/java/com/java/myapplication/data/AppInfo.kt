package com.java.myapplication.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 应用元信息（v2.3.13 新增）。
 *
 * 把「仓库地址」「版本号读取」这类散落信息收在一处：
 * 设置页「关于」区块、免责声明页、引导页都用这里的常量，
 * 以后换仓库地址只用改一行。
 */
object AppInfo {

    /** 开源仓库地址（应用内可点击跳转） */
    const val REPO_URL = "https://github.com/HuaHai-141225/eyecare"

    /** 仓库地址的短展示形式（去掉 https:// 前缀，省界面空间） */
    const val REPO_URL_SHORT = "github.com/HuaHai-141225/eyecare"

    /**
     * 读取当前安装包的版本名（如 "2.3.13"）。
     *
     * 用 PackageManager 而不是 BuildConfig，是为了不动构建配置；
     * 任何异常都吞掉返回「未知」，绝不因为读版本把界面搞崩。
     */
    fun versionName(context: Context): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "未知"
    } catch (_: Exception) {
        "未知"
    }
}
