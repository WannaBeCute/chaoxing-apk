package com.cxrunner.app.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 运行所需的文件布局：
 *   filesDir/chaoxing           脚本工作目录（cookies.txt / chaoxing.log / config.ini）
 *   filesDir/chaoxing/resource  从 assets 释放的字形映射表
 *   filesDir/run.log            APP 日志窗口读取的实时日志
 *   filesDir/ui                 Python <-> APP 的交互目录
 */
object AppFiles {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** 全局 applicationContext（供无 Context 的 Composable 使用） */
    val context: android.content.Context get() = app

    val workDir: File get() = File(app.filesDir, "chaoxing")
    val logFile: File get() = File(app.filesDir, "run.log")
    val uiDir: File get() = File(app.filesDir, "ui")
    val configFile: File get() = File(workDir, "config.ini")
    val cookiesFile: File get() = File(workDir, "cookies.txt")
    val requestFile: File get() = File(uiDir, "request.json")
    val responseFile: File get() = File(uiDir, "response.json")
    val statusFile: File get() = File(uiDir, "status.json")
    val stopFile: File get() = File(uiDir, "stop.flag")
    val coursesCacheFile: File get() = File(uiDir, "courses_cache.json")

    /** 准备目录并释放 assets 中的资源文件（仅首次或缺失时） */
    fun prepare() {
        workDir.mkdirs()
        uiDir.mkdirs()
        copyAssets("resource", File(workDir, "resource"))
    }

    private fun copyAssets(path: String, dest: File) {
        try {
            val names = app.assets.list(path) ?: return
            dest.mkdirs()
            for (name in names) {
                val sub = if (path.isEmpty()) name else "$path/$name"
                val children = app.assets.list(sub)
                if (!children.isNullOrEmpty()) {
                    copyAssets(sub, File(dest, name))
                } else {
                    val out = File(dest, name)
                    app.assets.open(sub).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readTemplate(context: Context): String {
        return try {
            context.resources.openRawResource(
                context.resources.getIdentifier("config_template", "raw", context.packageName)
            ).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }
}
