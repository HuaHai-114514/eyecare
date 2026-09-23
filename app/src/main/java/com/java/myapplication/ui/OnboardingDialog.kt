package com.java.myapplication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.java.myapplication.data.AppInfo
import com.java.myapplication.data.Disclaimer

/**
 * 首次启动引导（v2.3.13 新增）。
 *
 * 分两步：
 * 1. **免责声明** —— 必须点「我已知悉」才能继续，不能被外部点击/返回键绕过；
 * 2. **可靠性设置** —— 复用自检面板的检查项，逐项给出「去开启」入口，可「稍后设置」跳过。
 *
 * 只要走到 [onFinish]（无论走完还是跳过），就由调用方写入完成标记，之后不再自动弹。
 * 想重看可靠性设置，走「护眼设置 → 提醒可靠性自检」；
 * 想重看免责声明，走「护眼设置 → 关于 → 免责声明」。
 */
@Composable
fun OnboardingDialog(onFinish: () -> Unit) {
    var step by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = {
            // 第 0 步（免责声明）不允许点外部或按返回键绕过；第 1 步允许（等于「稍后设置」）
            if (step == 1) onFinish()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    if (step == 0) "🌿 欢迎使用护眼时光" else "🔔 让提醒准时到达",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (step == 0) "开始前，请花一分钟了解下面这些"
                    else "Android 有几处开关会影响提醒，建议现在设置好",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                if (step == 0) DisclaimerStep() else SetupGuideStep()
            }
        },
        confirmButton = {
            Button(
                onClick = { if (step == 0) step = 1 else onFinish() },
                shape = CircleShape
            ) {
                Text(if (step == 0) "我已知悉，继续" else "完成")
            }
        },
        dismissButton = {
            if (step == 1) {
                TextButton(onClick = onFinish) {
                    Text("稍后设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}

// ============ 第 1 步：免责声明 ============
@Composable
private fun DisclaimerStep() {
    SoftCard {
        Text(
            Disclaimer.TEXT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "继续即表示你已阅读并同意以上说明。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ============ 第 2 步：可靠性设置引导 ============
@Composable
private fun SetupGuideStep() {
    val context = LocalContext.current
    // 用户从系统设置页返回后，点「刷新状态」可重新采集（不依赖生命周期监听库）
    var refreshTick by remember { mutableStateOf(0) }
    val items = remember(refreshTick) { collectDiagnostics(context) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item -> DiagRow(item) }

        Spacer(Modifier.height(2.dp))
        Text(
            "改完系统设置后回到这里，点「刷新状态」确认是否生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { refreshTick++ }) {
            Text("刷新状态", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 只读的免责声明弹窗（v2.3.13 新增）。
 *
 * 供设置页「关于」区块随时回看，文案与首次启动引导完全一致（同源 [Disclaimer.TEXT]）。
 */
@Composable
fun DisclaimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                "免责声明",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                SoftCard {
                    Text(
                        Disclaimer.TEXT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "项目仓库：${AppInfo.REPO_URL}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = CircleShape) { Text("知道了") }
        }
    )
}
