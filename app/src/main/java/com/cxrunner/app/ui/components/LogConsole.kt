package com.cxrunner.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 终端风格日志窗口：右侧带可拖动的滚动条与工具栏。
 */
@Composable
fun LogConsole(
    text: String,
    fontSize: Int,
    autoRefresh: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape),
    ) {
        // 顶部：标题 + 自动刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "运行日志",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("自动刷新", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(
                checked = autoRefresh,
                onCheckedChange = onAutoRefreshChange,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // 日志正文
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = text.ifEmpty { "（暂无日志，点击「开始运行」启动脚本）" },
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 4).sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (text.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                // 右侧可拖动滚动条（自绘，避免依赖版本差异较大的 Scrollbar API）
                SimpleVerticalScrollbar(
                    state = scrollState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(vertical = 2.dp),
                )
            }

            // 右侧工具栏
            Column(
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = "导出", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "清除", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onZoomIn, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = "放大", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onZoomOut, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ZoomOut, contentDescription = "放小", modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    // 新日志到来时自动滚动到底部
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
}

/**
 * 极简纵向滚动条：按比例显示滑块位置，支持按住拖动。
 */
@Composable
private fun SimpleVerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val idleColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val activeColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = modifier.fillMaxHeight().width(12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val maxValue = state.maxValue.toFloat().coerceAtLeast(1f)
        val thumbPx = (trackPx * trackPx / (trackPx + maxValue)).coerceIn(30f, trackPx)
        val maxOffset = (trackPx - thumbPx).coerceAtLeast(1f)
        val offsetPx = (state.value / maxValue * maxOffset).coerceIn(0f, maxOffset)

        Box(
            modifier = Modifier
                .width(8.dp)
                .height(with(density) { thumbPx.toDp() })
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                .clip(RoundedCornerShape(4.dp))
                .background(if (dragging) activeColor else idleColor)
                .pointerInput(maxOffset, maxValue) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { _, dragAmount ->
                        val delta = dragAmount / maxOffset * maxValue
                        scope.launch {
                            state.scrollTo(
                                (state.value + delta).roundToInt().coerceIn(0, state.maxValue)
                            )
                        }
                    }
                },
        )
    }
}
