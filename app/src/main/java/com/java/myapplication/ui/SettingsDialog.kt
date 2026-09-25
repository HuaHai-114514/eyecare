package com.java.myapplication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.java.myapplication.data.AppSettings
import com.java.myapplication.notify.RestSoundPlayer
import com.java.myapplication.notify.RestSoundSource
import com.java.myapplication.ui.components.EyeIcons

/**
 * 护眼设置（v2.3 分组版）。
 *
 * 关键点：保存时基于传入的 [settings] 做 `copy(...)`，只覆盖本次编辑到的字段，
 * 保证其它新字段（免打扰、久坐等）不会因为一次保存被重置为默认值。
 */
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    var workText by remember { mutableStateOf(settings.workMinutes.toString()) }
    var restText by remember { mutableStateOf(settings.restSeconds.toString()) }
    var goalText by remember { mutableStateOf(settings.dailyGoalMinutes.toString()) }

    var autoFullScreen by remember { mutableStateOf(settings.autoFullScreen) }
    var autoNight by remember { mutableStateOf(settings.autoNightMode) }

    var dndEnabled by remember { mutableStateOf(settings.dndEnabled) }
    var dndStartText by remember {
        mutableStateOf(AppSettings.formatMinuteOfDay(settings.dndStartMinute))
    }
    var dndEndText by remember {
        mutableStateOf(AppSettings.formatMinuteOfDay(settings.dndEndMinute))
    }

    var sitEnabled by remember { mutableStateOf(settings.sitReminderEnabled) }
    var sitInterval by remember { mutableStateOf(settings.sitIntervalMinutes) }

    var restEndSound by remember { mutableStateOf(settings.restEndSoundEnabled) }
    var restEndSoundVibrate by remember { mutableStateOf(settings.restEndSoundVibrate) }
    var restEndSoundSource by remember { mutableStateOf(settings.restEndSoundSource) }

    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                "护眼设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // ---------------- 计时 ----------------
                SectionLabel("计时")
                NumberField(
                    label = "用眼提醒间隔（分钟）",
                    value = workText,
                    onChange = { workText = it }
                )
                NumberField(
                    label = "休息时长（秒）",
                    value = restText,
                    onChange = { restText = it }
                )
                NumberField(
                    label = "每日用眼目标（分钟）",
                    value = goalText,
                    onChange = { goalText = it }
                )

                HorizontalDivider()

                // ---------------- 提醒 ----------------
                SectionLabel("提醒")
                SwitchRow(
                    title = "到点自动全屏",
                    subtitle = "用眼到点时直接弹出全屏休息页；关闭则只发通知横幅",
                    checked = autoFullScreen,
                    onChecked = { autoFullScreen = it }
                )
                SwitchRow(
                    title = "启用免打扰时段",
                    subtitle = "时段内只计时不打扰，出时段自动恢复",
                    checked = dndEnabled,
                    onChecked = { dndEnabled = it }
                )
                if (dndEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            TimeField(
                                label = "开始 (HH:mm)",
                                value = dndStartText,
                                onChange = { dndStartText = it }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TimeField(
                                label = "结束 (HH:mm)",
                                value = dndEndText,
                                onChange = { dndEndText = it }
                            )
                        }
                    }
                }
                SwitchRow(
                    title = "休息结束提示音",
                    subtitle = "休息结束时由应用响一声（音源可在下方选择）",
                    checked = restEndSound,
                    onChecked = { restEndSound = it }
                )
                if (restEndSound) {
                    // v2.3.8：音源换成系统通知铃声（本来就只有一两秒），
                    // 所以 v2.3.7 的「播放时长」选项已无意义，改为「是否同时振动」。
                    SwitchRow(
                        title = "同时振动",
                        subtitle = "提示音响起时短振动一下，手机扣在桌上也能察觉",
                        checked = restEndSoundVibrate,
                        onChecked = { restEndSoundVibrate = it }
                    )
                    // v2.3.12：音源改为可选（跟随系统通知铃声 / 若干内置音效）。
                    // 起因是 v2.3.11 查出来「一前一后两声」出自系统铃声文件本身
                    // （水滴声 WaterDrop_preview 天生"嘀-嗒"两下），当时把音源锁死成内置单音；
                    // 这一版把选择权还给用户 —— 两个"双音"音源本身就含两个音，选它们就会再听到两声，
                    // 这是知情选择，不是缺陷。
                    Text(
                        "提示音音源",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 下拉选择：把原先六行平铺单选收成一个下拉框，右侧保留「试听」。
                    // 菜单里只列音源名，当前选中项的说明显示在下方小字里。
                    var soundMenuExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { soundMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = restEndSoundSource.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = EyeIcons.ArrowDropDown,
                                    contentDescription = "展开音源列表",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = soundMenuExpanded,
                                onDismissRequest = { soundMenuExpanded = false }
                            ) {
                                RestSoundSource.CHOICES.forEach { source ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                source.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            restEndSoundSource = source
                                            soundMenuExpanded = false
                                        },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = restEndSoundSource == source,
                                                onClick = null
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { RestSoundPlayer.play(context, restEndSoundSource) }) {
                            Text("试听", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        text = restEndSoundSource.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "音量跟随系统「通知音量」。静音 / 勿扰时会安静，只留通知与振动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                // ---------------- 久坐 ----------------
                SectionLabel("久坐")
                SwitchRow(
                    title = "久坐提醒",
                    subtitle = "按间隔提醒起身活动，与用眼提醒独立计时",
                    checked = sitEnabled,
                    onChecked = { sitEnabled = it }
                )
                if (sitEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppSettings.SIT_INTERVAL_CHOICES.forEach { minutes ->
                            FilterChip(
                                selected = sitInterval == minutes,
                                onClick = { sitInterval = minutes },
                                label = { Text("$minutes 分钟") }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // ---------------- 外观 ----------------
                SectionLabel("外观")
                SwitchRow(
                    title = "自动夜间模式",
                    subtitle = "跟随系统深色状态自动切换护眼配色",
                    checked = autoNight,
                    onChecked = { autoNight = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val wm = workText.toIntOrNull()?.coerceIn(1, 120) ?: settings.workMinutes
                    val rs = restText.toIntOrNull()?.coerceIn(5, 300) ?: settings.restSeconds
                    val goal = goalText.toIntOrNull()?.coerceIn(10, 720) ?: settings.dailyGoalMinutes
                    onSave(
                        settings.copy(
                            workMinutes = wm,
                            restSeconds = rs,
                            dailyGoalMinutes = goal,
                            autoFullScreen = autoFullScreen,
                            dndEnabled = dndEnabled,
                            dndStartMinute = AppSettings.parseMinuteOfDay(
                                dndStartText, settings.dndStartMinute
                            ),
                            dndEndMinute = AppSettings.parseMinuteOfDay(
                                dndEndText, settings.dndEndMinute
                            ),
                            sitReminderEnabled = sitEnabled,
                            sitIntervalMinutes = sitInterval,
                            restEndSoundEnabled = restEndSound,
                            restEndSoundVibrate = restEndSoundVibrate,
                            restEndSoundSource = restEndSoundSource,
                            autoNightMode = autoNight
                        )
                    )
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(3)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ':' }.take(5)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
