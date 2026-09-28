package com.cxrunner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cxrunner.app.data.InputRequest

/** 脚本请求输入时弹出的对话框 */
@Composable
fun InputPromptDialog(
    request: InputRequest,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(request.id) { mutableStateOf("") }
    val selected = remember(request.id) { mutableStateListOf<String>() }

    val title = when (request.kind) {
        "password" -> "需要登录密码"
        "courses" -> "选择要刷的课程"
        "confirm" -> "脚本询问"
        else -> "需要输入"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val prompt = request.prompt.trim()
                if (prompt.isNotBlank()) {
                    Text(
                        prompt,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when (request.kind) {
                    "courses" -> {
                        if (request.courses.isEmpty()) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                maxLines = 4,
                                placeholder = { Text("课程ID，多个用逗号分隔，留空=全部课程") },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Column {
                                    request.courses.forEach { course ->
                                        val checked = selected.contains(course.id)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (checked) selected.remove(course.id)
                                                    else selected.add(course.id)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = { isChecked ->
                                                    if (isChecked) selected.add(course.id) else selected.remove(course.id)
                                                },
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    course.title.ifBlank { "(无标题)" },
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    "课程ID ${course.id} · 班级 ${course.clazzId}",
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    selected.clear()
                                    selected.addAll(request.courses.map { it.id })
                                }) { Text("全选", fontSize = 12.sp) }
                                TextButton(onClick = { selected.clear() }) { Text("清空", fontSize = 12.sp) }
                            }
                            Text(
                                "已选 ${selected.size} 门（留空表示全部课程）",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    "confirm" -> {
                        Text("是否继续？", fontSize = 13.sp)
                    }
                    else -> {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (request.kind == "password") PasswordVisualTransformation()
                            else VisualTransformation.None,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = when (request.kind) {
                    "courses" -> if (request.courses.isEmpty()) text.trim() else selected.joinToString(",")
                    "confirm" -> "y"
                    else -> text
                }
                onSubmit(value)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/** 配置文件文本编辑弹窗 */
@Composable
fun TextEditorDialog(
    title: String,
    initialText: String,
    readOnly: Boolean = false,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (!readOnly) text = it },
                readOnly = readOnly,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                singleLine = false,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        },
        confirmButton = {
            if (onSave != null && !readOnly) {
                Button(onClick = { onSave(text) }) { Text("保存") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (onSave != null && !readOnly) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 二次确认弹窗 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = { Text(message, fontSize = 13.sp) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun SelectedIcon(selected: Boolean) {
    Icon(
        imageVector = if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
fun OkChip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(text, fontSize = 11.sp)
        }
    }
}
