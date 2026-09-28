package com.cxrunner.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** APP 持久化设置 */
data class AppSettings(
    var config: AppConfig = AppConfig(),
    var themeMode: ThemeMode = ThemeMode.BLACK,
    var runMode: RunMode = RunMode.DIRECT,
    var fontSize: Int = 12,
    var autoRefresh: Boolean = true,
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
            val cfg = obj.optJSONObject("config")
            if (cfg != null) {
                val map = linkedMapOf<String, String>()
                cfg.keys().forEach { map[it] = cfg.optString(it) }
                settings.config.applyIniValues(map)
            }
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
            val cfg = JSONObject()
            settings.config.toIniValues().forEach { (k, v) -> cfg.put(k, v) }
            obj.put("config", cfg)
            file.writeText(obj.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
