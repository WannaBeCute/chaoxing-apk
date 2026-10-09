package com.cxrunner.app.data

import android.content.Context
import com.cxrunner.app.ui.theme.ThemeMode
import org.json.JSONObject
import java.io.File

/**
 * 日志字号可调范围。
 *
 * 设置页步进器、日志区「放大/放小」按钮、以及读取旧配置时的兜底**共用这一套**——
 * 之前三处各写各的（读取 8~28、步进器 8~24、缩放按钮 8~24），很容易改漏。
 * 下限放到 5，方便在窄屏上一次性看全堆栈/URL 这类长行。
 */
const val LOG_FONT_SIZE_MIN = 5
const val LOG_FONT_SIZE_MAX = 24

/** APP 持久化设置 */
data class AppSettings(
    var config: AppConfig = AppConfig(),
    var themeMode: ThemeMode = ThemeMode.WHITE,
    var runMode: RunMode = RunMode.DIRECT,
    var fontSize: Int = 12,
    var autoRefresh: Boolean = true,
    var schedule: ScheduleSettings = ScheduleSettings(),

    /**
     * 用户是否已在系统里确认过「自启动」。
     *
     * AOSP 没有这个开关，MIUI 等厂商的实现又查不到稳定的公开 API，
     * 所以用户点过卡片之后就用这个标记把卡片转绿，避免明明开了却一直显示「未授权」。
     */
    var autostartConfirmed: Boolean = false,
)

object SettingsStore {
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "settings.json")
    }

    fun load(): AppSettings {
        val settings = AppSettings()
        if (!::file.isInitialized || !file.exists()) return settings
        return try {
            val obj = JSONObject(file.readText())
            settings.themeMode = ThemeMode.fromName(obj.optString("themeMode"))
            settings.runMode = RunMode.fromName(obj.optString("runMode"))
            settings.fontSize = obj.optInt("fontSize", 12).coerceIn(LOG_FONT_SIZE_MIN, LOG_FONT_SIZE_MAX)
            settings.autoRefresh = obj.optBoolean("autoRefresh", true)
            settings.autostartConfirmed = obj.optBoolean("autostartConfirmed", false)
            val sch = obj.optJSONObject("schedule")
            if (sch != null) {
                settings.schedule = ScheduleSettings(
                    enabled = sch.optBoolean("enabled", false),
                    startMinute = sch.optInt("startMinute", 8 * 60).coerceIn(0, 1439),
                    endMinute = sch.optInt("endMinute", 24 * 60).coerceIn(1, 1440),
                )
            }
            val cfg = obj.optJSONObject("config")
            if (cfg != null) {
                val map = linkedMapOf<String, String>()
                cfg.keys().forEach { map[it] = cfg.optString(it) }
                settings.config.applyIniValues(map)
            }
            // 兼容旧版本：模板占位符 "xxx" 并非有效账号，一律视为「未填写」
            if (settings.config.username.trim() == "xxx") settings.config.username = ""
            if (settings.config.password.trim() == "xxx") settings.config.password = ""
            settings
        } catch (e: Exception) {
            settings
        }
    }

    fun save(settings: AppSettings) {
        if (!::file.isInitialized) return
        try {
            val obj = JSONObject()
            obj.put("themeMode", settings.themeMode.name)
            obj.put("runMode", settings.runMode.name)
            obj.put("fontSize", settings.fontSize)
            obj.put("autoRefresh", settings.autoRefresh)
            obj.put("autostartConfirmed", settings.autostartConfirmed)
            obj.put(
                "schedule",
                JSONObject().apply {
                    put("enabled", settings.schedule.enabled)
                    put("startMinute", settings.schedule.startMinute)
                    put("endMinute", settings.schedule.endMinute)
                },
            )
            val cfg = JSONObject()
            settings.config.toIniValues().forEach { (k, v) -> cfg.put(k, v) }
            obj.put("config", cfg)
            file.writeText(obj.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
