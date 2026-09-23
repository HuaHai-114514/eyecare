package com.java.myapplication.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.java.myapplication.notify.RestNotifier

/**
 * 后台提醒自检（v2.3.6 新增）。
 *
 * 为什么需要这一页：这个应用最难的部分不是计时，而是「到点真的能提醒到你」。
 * 而 Android 有四五处相互独立的开关在决定这件事（通知权限、精确闹钟、全屏通知、
 * 电池优化、厂商自启动），任何一处关着都会表现为「提醒不准时」，但应用过去
 * 完全没有任何反馈渠道。这一页把「为什么不准」变成一条用户自己能查的清单。
 *
 * 说明：自启动（MIUI/HyperOS 的应用省电策略）**没有公开 API 可以查询**，
 * 所以这里诚实地标为「需手动确认」，不做假检测。
 */
private data class DiagItem(
    val title: String,
    val ok: Boolean,
    val detail: String,
    val actionLabel: String?,
    val target: Intent?
)

@Composable
fun DiagnosticsPanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 用户从系统设置返回后，点「重新检测」即可刷新（避免依赖生命周期监听库）
    var refreshTick by remember { mutableStateOf(0) }
    val items = remember(refreshTick) { collectDiagnostics(context) }
    val problems = items.count { !it.ok }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    "提醒可靠性自检",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (problems == 0) "五项检查全部通过 🌿" else "有 $problems 项需要注意",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (problems == 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items.forEach { item -> DiagRow(item) }
                Text(
                    "改过系统设置后，点「重新检测」刷新状态。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = {
            TextButton(onClick = { refreshTick++ }) { Text("重新检测") }
        }
    )
}

@Composable
private fun DiagRow(item: DiagItem) {
    val context = LocalContext.current
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (item.ok) "✅" else "⚠️",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.actionLabel != null && item.target != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { openSystemPage(context, item.target) }) {
                    Text(item.actionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 跳系统页面：个别 ROM 缺对应 Activity，失败时静默兜底（不崩、不卡） */
private fun openSystemPage(context: Context, intent: Intent) {
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}

private fun appDetailIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))

/** 逐项采集五项检查的当前状态 */
private fun collectDiagnostics(context: Context): List<DiagItem> {
    val pkg = context.packageName
    val list = mutableListOf<DiagItem>()

    // 1) 通知权限（Android 13+）
    val canNotify = RestNotifier.canPostNotifications(context)
    list.add(
        DiagItem(
            title = "通知权限",
            ok = canNotify,
            detail = if (canNotify) "已允许发送通知"
            else "未允许，到点提醒无法显示，请开启",
            actionLabel = if (canNotify) null else "去开启",
            target = if (canNotify) null else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        )
    )

    // 2) 精确闹钟（Android 12+ 需单独授权）
    val canExact = canScheduleExactAlarms(context)
    list.add(
        DiagItem(
            title = "精确闹钟",
            ok = canExact,
            detail = if (canExact) "已允许，可精确到点提醒"
            else "系统限制，提醒可能延后几分钟",
            actionLabel = if (canExact) null else "去开启",
            target = if (canExact) null else Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:$pkg"))
        )
    )

    // 3) 全屏通知（Android 14+ 普通应用默认被拒）
    val canFull = RestNotifier.hasFullScreenPermission(context)
    list.add(
        DiagItem(
            title = "全屏通知",
            ok = canFull,
            detail = if (canFull) "已允许，到点可弹出全屏休息页"
            else "未允许，「到点自动全屏」只会降级为横幅通知",
            actionLabel = if (canFull) null else "去开启",
            target = if (canFull) null else fullScreenIntentSettingsIntent(pkg)
        )
    )

    // 4) 电池优化
    val ignoringBattery = isIgnoringBatteryOptimizations(context)
    list.add(
        DiagItem(
            title = "电池优化",
            ok = ignoringBattery,
            detail = if (ignoringBattery) "已允许后台常驻"
            else "系统可能限制后台运行，建议设为「不限制」",
            actionLabel = if (ignoringBattery) null else "去设置",
            // 跳通用列表页，不声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（避免上架审查问题）
            target = if (ignoringBattery) null
            else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    )

    // 5) 自启动：MIUI/HyperOS 私有能力，无公开 API，只能人工确认
    list.add(
        DiagItem(
            title = "自启动 / 后台弹出",
            ok = false,
            detail = "系统不提供查询接口，请手动确认：设置 → 应用管理 → 护眼时光 → 允许自启动",
            actionLabel = "应用详情",
            target = appDetailIntent(context)
        )
    )

    return list
}

/** Android 12+ 的精确闹钟授权状态；低版本无此限制 */
private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return try {
        am.canScheduleExactAlarms()
    } catch (_: Exception) {
        true
    }
}

/** 是否已被排除在电池优化之外（= 允许后台常驻） */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return try {
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }
}

/** 全屏通知设置页（仅 Android 14+ 存在该页面） */
private fun fullScreenIntentSettingsIntent(pkg: String): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    return Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        .setData(Uri.parse("package:$pkg"))
}
