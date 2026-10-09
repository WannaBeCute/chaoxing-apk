package com.cxrunner.app.data

import android.content.Context
import com.cxrunner.app.ui.theme.ThemeMode
import org.json.JSONObject
import java.io.File

/** APP 持久化设置 */
data class AppSettings(
    var config: AppConfig = AppConfig(),
    var themeMode: ThemeMode = ThemeMode.WHITE,
    var runMode: RunMode = RunMode.DIRECT,
    var fontSize: Int = 12,
    var autoRefresh: Boolean = true,
    var schedule: ScheduleSettings = ScheduleSettings(),
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
            settings.fontSize = obj.optInt("fontSize", 12).coerceIn(8, 28)
            settings.autoRefresh = obj.optBoolean("autoRefresh", true)
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
