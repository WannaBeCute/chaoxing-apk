package com.cxrunner.app.data

import org.json.JSONObject

/** 单个任务的实时进度（由 Python 侧从 tqdm 帧解析后写入 progress.json） */
data class ProgressItem(
    val name: String,
    val percent: Int,
    val current: String,
    val total: String,
    /**
     * 视频已播完、正等服务端判定通过。
     *
     * tqdm 到 100% 就停在那儿，但脚本还要补交进度等 `isPassed` 变 true，
     * 这段时间界面上显示「提交ing...」比一个不动的 100% 更能说明"还在干活"。
     */
    val submitting: Boolean = false,
) {
    /** 进度文案：正常是 `57%`，提交中是 `提交ing...` */
    val percentLabel: String get() = if (submitting) "提交ing..." else "$percent%"
}

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
                        // 兼容旧版 Python 写出的、没有该字段的 progress.json
                        submitting = o.optBoolean("submitting", false),
                    )
                )
            }
            // 进度面板空间有限，按百分比从高到低排，优先展示快完成的任务
            list.sortedByDescending { it.percent }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 同名任务只保留一条（进度最高的那条）。
     *
     * 同一个视频在「视频失败回退音频」「章节重试」时会被处理两次，
     * 旧进度条关闭前也可能留下一条记录。面板跟通知栏都应该一个任务占一行，
     * 否则用户看到的就是「同一个视频出现好几次」。
     */
    fun distinctByName(list: List<ProgressItem>): List<ProgressItem> {
        if (list.size <= 1) return list
        val seen = LinkedHashMap<String, ProgressItem>()
        for (item in list) {
            val key = item.name.trim()
            val prev = seen[key]
            if (prev == null || item.percent > prev.percent) seen[key] = item
        }
        return seen.values.toList()
    }
}