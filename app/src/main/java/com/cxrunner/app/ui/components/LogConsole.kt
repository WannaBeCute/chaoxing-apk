package com.cxrunner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.AnnotatedString
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
    val listState = rememberLazyListState()
    val shape = RoundedCornerShape(10.dp)

    // 日志里的 ANSI 颜色码在渲染时解析成 SpanStyle，控制字符一并清掉。
    val palette = rememberAnsiPalette()
    val fallbackColor = if (text.isEmpty()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // 按行切分 + LazyColumn 渲染：整段丢给一个 Text 在 3000 行量级下
    // 每次重组都要重新测量整块文本，日志刷不动、看起来就像"没有自动刷新"。
    val lines = remember(text) { if (text.isEmpty()) emptyList() else text.split('\n') }
    val displayLines = remember(lines, palette, fallbackColor) {
        lines.map { line ->
            if (line.isEmpty()) AnnotatedString("") else ansiToAnnotatedString(line, palette, fallbackColor)
        }
    }

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
                colors = appSwitchColors(),
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
                if (lines.isEmpty()) {
                    Text(
                        "（暂无日志，点击「开始运行」启动脚本）",
                        fontSize = fontSize.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(displayLines) { _, line ->
                                Text(
                                    text = line,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize + 4).sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
                // 右侧可拖动滚动条（自绘，避免依赖版本差异较大的 Scrollbar API）
                SimpleVerticalScrollbar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(vertical = 2.dp),
                )
            }

            // 右侧工具栏
            // IconButton 取的是 LocalContentColor，而这里外层只有 Column + background，
            // 没有 Surface 提供内容色，不显式指定的话黑色主题下图标会是纯黑（看不见）。
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
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
    }

    // 新日志到来时自动滚动到底部。
    // 只在用户本来就贴着底部时才跟随，向上翻阅历史时不要把人强行拽回底部。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size) {
        if (atBottom && lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
}

/**
 * 极简纵向滚动条。
 *
 * 交互：**点哪儿跳哪儿**，按住可以自由拖动（滑块中心跟随手指），
 * 拖动时滑块高亮。为了让"可以点击"这件事可见，轨道画了一条非常淡的底线。
 *
 * 用 LazyListState 而不是 ScrollState：滚动位置用"首个可见行 / 总行数"
 * 表示，拖动时按比例换算成目标行号。
 */
@Composable
private fun SimpleVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val idleColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(ScrollbarTouchWidth)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        val trackPx = size.height.toFloat().coerceAtLeast(1f)

                        fun jumpTo(y: Float) {
                            val total = state.layoutInfo.totalItemsCount
                            if (total <= 0) return
                            val visibleCount = state.layoutInfo.visibleItemsInfo.size
                            val ratio0 = if (total > 0) {
                                (visibleCount.toFloat() / total).coerceIn(0.08f, 1f)
                            } else 1f
                            val thumb = trackPx * ratio0
                            val maxOffset = (trackPx * (1f - ratio0)).coerceAtLeast(1f)
                            val ratio = ((y - thumb / 2f) / maxOffset).coerceIn(0f, 1f)
                            val target = (ratio * (total - 1)).toInt().coerceIn(0, total - 1)
                            scope.launch { state.scrollToItem(target) }
                        }

                        jumpTo(down.position.y)
                        down.consume()

                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            jumpTo(change.position.y)
                            change.consume()
                        }
                        dragging = false
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val total = state.layoutInfo.totalItemsCount
        val visible = state.layoutInfo.visibleItemsInfo.size
        // 滑块高度 = 可见占比；至少留 30px，保证内容少时也能点到
        val thumbRatio = if (total > 0) (visible.toFloat() / total).coerceIn(0.08f, 1f) else 1f
        val thumbPx = (trackPx * thumbRatio).coerceIn(30f, trackPx)
        val progress = if (total > 1) {
            state.firstVisibleItemIndex.toFloat() / (total - 1)
        } else 0f
        val offsetPx = (progress * (trackPx - thumbPx)).coerceIn(0f, (trackPx - thumbPx).coerceAtLeast(0f))

        // 极淡的轨道底线，提示这里可以点
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(trackColor),
        )
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(with(density) { thumbPx.toDp() })
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                .clip(RoundedCornerShape(4.dp))
                .background(if (dragging) activeColor else idleColor),
        )
    }
}

/** 滚动条可点击 / 可拖动的触摸区宽度（视觉上滑块仍是 8dp） */
private val ScrollbarTouchWidth = 16.dp
