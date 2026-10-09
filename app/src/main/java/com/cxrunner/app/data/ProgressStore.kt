package com.cxrunner.app.data

import org.json.JSONObject

/** 单个任务的实时进度（由 Python 侧从 tqdm 帧解析后写入 progress.json） */
data class ProgressItem(
    val name: String,
    val percent: Int,
    val current: String,
    val total: String,
)

/**
 * 读取 Python 侧写出的 ui/progress.json。
 *
 * 进度条原本是用 `\r` 直接刷进日志文件的，一个 22 分钟的视频能刷出上千帧，
 * 日志窗口根本没法看（详见 android_bootstrap.LogWriter 的注释）。
 * 现在改成：帧不进日志，解析成结构化数据落到 progress.json，
 * APP 每秒读一次渲染成真正的进度条。
 */
object ProgressStore {

    fun read(): List<ProgressItem> {
        val file = AppFiles.progressFile
        if (!file.exists()) return emptyList()
        return try {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("items") ?: return emptyList()
            val list = mutableListOf<ProgressItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                list.add(
                    ProgressItem(
                        name = name,
                        percent = o.optInt("pct", 0).coerceIn(0, 100),
                        current = o.optString("cur"),
                        total = o.optString("total"),
                    )
                )
            }
            // 进度面板空间有限，按百分比从高到低排，优先展示快完成的任务
            list.sortedByDescending { it.percent }
        } catch (e: Exception) {
            emptyList()
        }
    }
}