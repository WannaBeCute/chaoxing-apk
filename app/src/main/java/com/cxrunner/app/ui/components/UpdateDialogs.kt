package com.cxrunner.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cxrunner.app.data.UpdateSource

/**
 * 发现新版本：先展示版本与更新内容，确认后再让用户选下载源。
 *
 * 之所以做成「两段式」而不是把所有按钮堆在一个弹窗里，
 * 是因为「去更新」之后还要选源，堆在一起会有 4 个按钮，手机上很难点。
 */
@Composable
fun UpdateAvailableDialog(
    currentVersion: String,
    latestVersion: String,
    notes: String,
    canMute: Boolean,
    onOpenSourcePicker: () -> Unit,
    onMuteAndClose: () -> Unit,
    onClose: () -> Unit,
) {
    var muteChecked by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("发现新版本 $latestVersion", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "当前版本：$currentVersion",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("更新内容", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (expanded) 400.dp else 110.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            notes,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(if (expanded) "收起" else "展开全部", fontSize = 12.sp)
                    }
                }
                if (canMute) {
                    Spacer(Modifier.height(8.dp))
                    MuteRow(
                        checked = muteChecked,
                        onToggle = { muteChecked = !muteChecked },
                    )
                    Text(
                        "关闭后不再自动提醒，可随时在「设置 → 检查更新」里重新打开。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (canMute && muteChecked) onMuteAndClose() else onOpenSourcePicker()
            }) { Text(if (canMute && muteChecked) "不再提醒" else "去更新") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}

/** 选下载源：GitHub / 蓝奏云，各给出一个可点击的「打开」 */
@Composable
fun UpdateSourceDialog(
    githubUrl: String?,
    lanzou: UpdateSource?,
    onOpen: (UpdateSource) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下载方式", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "下载后用文件管理器打开安装即可，会覆盖升级、保留现有设置。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!githubUrl.isNullOrBlank()) {
                    SourceRow(
                        icon = Icons.Filled.CloudDownload,
                        title = "从 GitHub 下载",
                        detail = "需要能访问 GitHub",
                        onClick = {
                            onOpen(UpdateSource(label = "GitHub", url = githubUrl))
                        },
                    )
                }
                if (lanzou != null) {
                    SourceRow(
                        icon = Icons.Filled.CloudDownload,
                        title = lanzou.label,
                        detail = buildString {
                            if (lanzou.password.isNullOrBlank()) {
                                append("无需密码")
                            } else {
                                append("密码：")
                                append(lanzou.password)
                            }
                        },
                        highlighted = true,
                        onClick = { onOpen(lanzou) },
                    )
                }
                if (githubUrl.isNullOrBlank() && lanzou == null) {
                    Text(
                        "该版本暂未提供可用的下载地址，请到源码仓库的 Releases 页面手动下载。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 检查失败：说明原因，并给出可点击的源码地址 */
@Composable
fun UpdateFailedDialog(
    reason: String,
    repoUrl: String,
    onOpenRepo: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新失败", fontSize = 16.sp) },
        text = {
            Column {
                Text(reason, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "可以到源码仓库的 Releases 页面手动查看最新版本：",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SourceRow(
                    icon = Icons.Filled.OpenInNew,
                    title = "打开 APK 源码地址",
                    detail = repoUrl,
                    onClick = onOpenRepo,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun MuteRow(checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            onClick = onToggle,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("不再提醒", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    detail: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp)
                Text(
                    detail,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
