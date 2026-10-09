package com.cxrunner.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.RunMode
import com.cxrunner.app.data.RunnerController
import com.cxrunner.app.data.SettingsStore
import java.util.Calendar

/**
 * 定时启动调度器。
 *
 * 触发链路：
 *   ScheduleReceiver（闹钟 / 开机 / 换时区）-> 判断是否在时间窗内
 *   -> 在窗口内且没在跑，就用 CONFIG 模式拉起 RunnerService
 *
 * 只支持「按配置文件运行」：直接运行每次都要现填账号密码，
 * 定时触发时没人输入，脚本会一直卡在 input() 上。
 */
object ScheduleScheduler {

    const val ACTION_START = "com.cxrunner.app.action.SCHEDULE_START"
    private const val REQUEST_CODE = 8801
    private const val TAG = "ScheduleScheduler"

    /** 设置变化后重新登记闹钟 */
    fun sync(context: Context) {
        val app = context.applicationContext
        val settings = SettingsStore.load()
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        if (alarm == null) return
        cancel(app)

        // 仅配置文件模式 + 开关打开才排程
        if (!settings.schedule.enabled || settings.runMode != RunMode.CONFIG) {
            Log.i(TAG, "定时启动未启用（enabled=${settings.schedule.enabled}, mode=${settings.runMode}）")
            return
        }

        val cal = Calendar.getInstance()
        val startAt = nextOccurrence(cal, settings.schedule.startMinute)
        scheduleAlarm(app, alarm, startAt)
        Log.i(TAG, "下次定时启动：$startAt")
    }

    /** 计算下一次触发时刻：今天该点还没到就用今天，否则顺延到明天 */
    private fun nextOccurrence(cal: Calendar, minuteOfDay: Int): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MINUTE, minuteOfDay)
        if (c.timeInMillis <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun scheduleAlarm(context: Context, alarm: AlarmManager, triggerAt: Long) {
        val pi = pendingIntent(context)
        // setExactAndAllowWhileIdle 能在息屏 / Doze 下准时唤醒；Android 12+
        // 没有精确闹钟权限时退化为 setAndAllowWhileIdle（系统会择机触发，可能略晚）。
        try {
            if (canScheduleExact(alarm)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "精确闹钟不可用，退化为非精确闹钟", e)
            runCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
        }
    }

    private fun canScheduleExact(alarm: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarm.canScheduleExactAlarms()
    }

    /** 是否已获得精确闹钟权限（Android 12+ 需要单独授权） */
    fun hasExactAlarmPermission(context: Context): Boolean {
        val alarm = context.applicationContext
            .getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return canScheduleExact(alarm)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_START
            // 数据相同则复用同一个 PendingIntent，避免反复登记时叠加多个闹钟
            data = android.net.Uri.parse("cxrunner://schedule/${SettingsStore.load().schedule.startMinute}")
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarm.cancel(pendingIntent(app)) }
    }
}

/** 闹钟触发入口 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val settings = SettingsStore.load()

        // 开关被关掉 / 模式切回直接运行 -> 取消后续排程
        if (!settings.schedule.enabled || settings.runMode != RunMode.CONFIG) {
            ScheduleScheduler.cancel(app)
            return
        }

        // 已经在跑就不重复拉起，避免叠加多个脚本实例
        if (RunnerController.running.value) {
            ScheduleScheduler.sync(app)
            return
        }

        val cal = Calendar.getInstance()
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        // 闹钟可能因息屏/Doze 被推迟，触发时再确认一次是否仍在时间窗内
        if (!settings.schedule.isInWindow(nowMinute)) {
            ScheduleScheduler.sync(app)
            return
        }

        AppFiles.prepare()
        val svc = Intent(app, RunnerService::class.java).apply {
            action = RunnerService.ACTION_START
            putExtra(RunnerService.EXTRA_MODE, RunMode.CONFIG.name)
            putExtra(RunnerService.EXTRA_SOURCE, "定时启动")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(svc)
            } else {
                app.startService(svc)
            }
        }

        // 排下一次（今天已经过了就顺延到明天）
        ScheduleScheduler.sync(app)
    }
}

/**
 * 开机 / 应用更新 / 换时区后重排闹钟。
 * AlarmManager 的闹钟不会跨重启保留，必须靠这个接收器补登记。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            ScheduleScheduler.sync(context.applicationContext)
        }
    }
}