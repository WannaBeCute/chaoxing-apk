package com.cxrunner.app.data

/**
 * 定时启动设置。
 *
 * 仅对「按配置文件运行」生效：直接运行每次都要现填账号密码，
 * 定时启动没有可用的凭据，定时触发只会卡在输入框上。
 *
 * 默认关闭；开启后默认 08:00 —— 次日 00:00。
 */
data class ScheduleSettings(
    val enabled: Boolean = false,
    /** 当天开始时间，自 00:00 起的分钟数。默认 08:00 -> 480 */
    val startMinute: Int = 8 * 60,
    /** 结束时间，自 00:00 起的分钟数。默认次日 00:00 -> 1440 */
    val endMinute: Int = 24 * 60,
) {
    /** 跨天（结束时间早于开始时间）时按「次日」处理 */
    val spansNextDay: Boolean get() = endMinute <= startMinute

    fun formatMinute(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    val startText: String get() = formatMinute(startMinute)
    val endText: String get() = if (endMinute >= 1440) "24:00" else formatMinute(endMinute)

    /** 当前是否落在时间窗内 */
    fun isInWindow(nowMinute: Int): Boolean {
        return if (spansNextDay) {
            nowMinute >= startMinute || nowMinute < endMinute
        } else {
            nowMinute in startMinute until endMinute
        }
    }
}