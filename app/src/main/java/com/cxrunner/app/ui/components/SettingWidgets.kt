package com.cxrunner.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardShape = RoundedCornerShape(10.dp)

/**
 * 统一的开关配色。
 *
 * Material3 默认的关闭态是「outline 滑块 + surfaceVariant 轨道」，而在本工程的
 * 黑色 / 白色两套主题里这两个颜色亮度太接近（深色下 #30363D 压 #1C2128，
 * 浅色下 #D0D7DE 压 #EFF2F5），滑块几乎看不出来。
 * 这里把关闭态的滑块换成 onSurfaceVariant、轨道换成 surfaceVariant，
 * 并保留 outline 描边，两个主题下都有足够对比度。
 */
@Composable
fun appSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, CardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) trailing()
            if (collapsible) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = !collapsible || expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * 分组收纳栏的头部。
 *
 * 卡片外观、圆角、描边与内外边距都和 [SectionCard] 的头部**完全一致**，
 * 但它只画头部、不包裹内容：子卡片自己保留 12dp 外边距，
 * 因此收纳栏展开后里面的设置看起来和原来一模一样。
 */
@Composable
fun CollapsibleGroupHeader(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, CardShape)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun SwitchItem(
    label: String,
    desc: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            if (desc != null) {
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = appSwitchColors(),
        )
    }
}

@Composable
fun TextFieldItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    desc: String? = null,
    mono: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 8,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            placeholder = if (placeholder != null) {
                { Text(placeholder, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else null,
        )
        if (desc != null) {
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    format: (Float) -> String = { it.toString() },
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                format(value),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun StepperItem(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    desc: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (desc != null) Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (value > range.first) onValueChange(value - 1) }) {
                    Icon(Icons.Filled.Remove, contentDescription = "减少", modifier = Modifier.size(18.dp))
                }
                Text(
                    value.toString(),
                    modifier = Modifier.width(38.dp),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { if (value < range.last) onValueChange(value + 1) }) {
                    Icon(Icons.Filled.Add, contentDescription = "增加", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun DropdownItem(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    desc: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selected.ifBlank { "未选择" },
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
        if (desc != null) Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MultiSelectChips(
    label: String,
    options: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    desc: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { index, option ->
                val order = selected.indexOf(option)
                val active = order >= 0
                AssistChip(
                    onClick = { onToggle(option) },
                    label = {
                        Text(
                            if (active && selected.size > 1) "${order + 1}. $option" else option,
                            fontSize = 12.sp,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = null,
                )
            }
        }
        if (desc != null) Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MonoText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Int = 11) {
    Text(text, fontSize = size.sp, fontFamily = FontFamily.Monospace, color = color)
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        shape = CardShape,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .clip(CardShape)
            .clickable(enabled = enabled) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
            }
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun VerticalSpacer(height: Int) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.heightIn(min = height.dp))
}

/** 危险操作按钮：用于「恢复出厂设置」这类不可逆动作 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        modifier = modifier
            .clip(CardShape)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f), CardShape)
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
            }
            Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
