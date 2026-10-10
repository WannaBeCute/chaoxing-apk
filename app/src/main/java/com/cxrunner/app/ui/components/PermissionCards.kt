package com.cxrunner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 权限卡片的状态。
 *
 * 之所以不是简单的 Boolean：像「开机自启」这种开关，AOSP 原生根本没有、
 * 各厂商 ROM 各用各的私有实现，**大部分设备其实查不出结论**。
 * 硬塞成 true/false 就会骗人（要么一直显示未授权逼用户自己确认，要么假装已授权）。
 * 所以单独留一个 UNKNOWN：查不到就如实说「无法检测」，既不误报也不骚扰用户。
 *
 * 枚举顺序即排序权重：MISSING 最需要用户处理、排最前，GRANTED 排最后。
 */
enum class PermissionCardStatus {
    /** 未允许，需要用户去处理 */
    MISSING,

    /** 系统查不出结论（如 AOSP 没有这个开关） */
    UNKNOWN,

    /** 已允许 */
    GRANTED,
}

data class PermissionCardState(
    val id: String,
    val title: String,
    val detail: String,
    val status: PermissionCardStatus,
    val grantedText: String = "已获取",
    val missingText: String = "去开启",
    val unknownText: String = "无法检测",
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * 主页顶部的方形权限卡片，横向可滑动。
 * 卡片数量与种类会随运行设置变化。
 */
@Composable
fun PermissionCardRow(cards: List<PermissionCardState>) {
    if (cards.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.forEach { card -> PermissionCard(card) }
    }
}

@Composable
private fun PermissionCard(state: PermissionCardState) {
    val shape = RoundedCornerShape(10.dp)
    // 三态三色：已允许=主色，未允许=错误色，查不出来=中性 tertiary（不吓唬用户）
    val container = when (state.status) {
        PermissionCardStatus.GRANTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        PermissionCardStatus.MISSING -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.30f)
        PermissionCardStatus.UNKNOWN -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f)
    }
    val border = when (state.status) {
        PermissionCardStatus.GRANTED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        PermissionCardStatus.MISSING -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
        PermissionCardStatus.UNKNOWN -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.50f)
    }
    val accent = when (state.status) {
        PermissionCardStatus.GRANTED -> MaterialTheme.colorScheme.primary
        PermissionCardStatus.MISSING -> MaterialTheme.colorScheme.error
        PermissionCardStatus.UNKNOWN -> MaterialTheme.colorScheme.tertiary
    }
    Column(
        modifier = Modifier
            .width(112.dp)
            .height(104.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .clickable { state.onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(state.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            // 卡片只有 112dp 宽，标题/说明常常放不下。这里改用自动横向滚动
            // （basicMarquee：内容放得下时静止，放不下时来回滚动，不出现滚动条），
            // 保证长文案也能完整读到，不再被裁掉。
            Text(
                state.title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                state.detail,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (state.status) {
                    PermissionCardStatus.GRANTED -> Icons.Filled.CheckCircle
                    PermissionCardStatus.MISSING -> Icons.Filled.RadioButtonUnchecked
                    PermissionCardStatus.UNKNOWN -> Icons.Filled.Help
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                when (state.status) {
                    PermissionCardStatus.GRANTED -> state.grantedText
                    PermissionCardStatus.MISSING -> state.missingText
                    PermissionCardStatus.UNKNOWN -> state.unknownText
                },
                fontSize = 10.sp,
                color = accent,
            )
        }
    }
}
