package com.cxrunner.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一个可下载的更新来源。
 *
 * 目前固定两种：GitHub 直链（由 release 的 assets 得到）和蓝奏云（从 release 正文里正则提取）。
 * 蓝奏云那条带上提取到的密码，方便用户在弹窗里直接看到、复制。
 */
data class UpdateSource(
    val label: String,
    val url: String,
    val password: String? = null,
    val recommended: Boolean = false,
)

/** 一次版本检查的结果 */
sealed interface UpdateCheckResult {
    /** 已是最新（含「当前版本比线上还新」的情况） */
    data class UpToDate(val latest: String) : UpdateCheckResult

    /** 发现新版本 */
    data class Available(
        val latest: String,
        val current: String,
        val notes: String,
        val githubUrl: String?,
        val lanzou: UpdateSource?,
    ) : UpdateCheckResult

    /** 网络/解析失败；[reason] 供弹窗展示，[repoUrl] 是让用户自己去看的源码地址 */
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * 版本检查与更新提示的状态管理。
 *
 * 有三条使用路径：
 *   1. 设置页底部「检查更新」——用户手动点，结果直接展示；
 *   2. 冷启动时自动检查——每 [CHECK_INTERVAL_MS]（一天）最多一次；
 *   3. 发现新版本后的提醒弹窗——可勾选「不再提醒」永久静音。
 *
 * 全程只在后台线程跑网络请求，**不触碰脚本的运行状态**（不 stop、不 requestStop），
 * 所以正在刷课的时候弹更新提示也不会打断任务。
 */
object UpdateManager {

    /** release 所在仓库，失败提示里的「源码地址」也用它 */
    const val REPO_URL = "https://github.com/WannaBeCute/chaoxing-apk"

    private const val RELEASE_API =
        "https://api.github.com/repos/WannaBeCute/chaoxing-apk/releases/latest"

    /** 自动检查间隔：一天 */
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private lateinit var stateFile: File

    /** 最近一次成功检查到的「最新版本号」，用于弹窗文案（不持久化） */
    @Volatile
    var lastKnownLatest: String = ""

    fun init(context: Context) {
        stateFile = File(context.filesDir, "update_state.json")
        currentVersionLabel = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 当前安装版本的 versionName（如 `1.1` / `1.1-test`），由 init 读取 */
    @Volatile
    var currentVersionLabel: String = ""
        private set

    // ------------------------------------------------------------------ //
    // 状态读写
    // ------------------------------------------------------------------ //

    private data class State(
        val lastCheckAt: Long = 0L,
        val muted: Boolean = false,
        /** 已经提醒过的版本号，避免同一版本每次开机都弹 */
        val notifiedVersion: String = "",
    )

    private fun loadState(): State {
        if (!::stateFile.isInitialized || !stateFile.exists()) return State()
        return try {
            val o = JSONObject(stateFile.readText())
            State(
                lastCheckAt = o.optLong("lastCheckAt", 0L),
                muted = o.optBoolean("muted", false),
                notifiedVersion = o.optString("notifiedVersion", ""),
            )
        } catch (e: Exception) {
            State()
        }
    }

    private fun saveState(s: State) {
        if (!::stateFile.isInitialized) return
        try {
            val o = JSONObject()
            o.put("lastCheckAt", s.lastCheckAt)
            o.put("muted", s.muted)
            o.put("notifiedVersion", s.notifiedVersion)
            stateFile.writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    /** 用户是否勾选了「不再提醒」 */
    fun isMuted(): Boolean = loadState().muted

    fun setMuted(muted: Boolean) {
        val s = loadState()
        saveState(s.copy(muted = muted))
    }

    /** 距离下次自动检查还剩多久（毫秒），<=0 表示该查了 */
    fun remainingUntilNextCheck(): Long {
        val s = loadState()
        if (s.lastCheckAt <= 0L) return 0L
        return CHECK_INTERVAL_MS - (System.currentTimeMillis() - s.lastCheckAt)
    }

    fun shouldAutoCheck(): Boolean = remainingUntilNextCheck() <= 0L

    /** 这个版本是否已经提醒过（同版本不重复弹） */
    fun alreadyNotified(version: String): Boolean =
        loadState().notifiedVersion.equals(version, ignoreCase = false)

    fun markNotified(version: String) {
        val s = loadState()
        saveState(s.copy(notifiedVersion = version))
    }

    private fun markChecked() {
        val s = loadState()
        saveState(s.copy(lastCheckAt = System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ //
    // 检查
    // ------------------------------------------------------------------ //

    /**
     * 查询 GitHub 最新的 release 并与当前版本比较。**必须在后台线程调用**。
     *
     * @param currentLabel 当前 versionName，例如 `1.1` 或 `1.1-test`
     */
    fun check(currentLabel: String): UpdateCheckResult {
        val body = try {
            httpGet(RELEASE_API)
        } catch (e: Exception) {
            return UpdateCheckResult.Failed(networkReason(e))
        }

        val release = try {
            JSONObject(body)
        } catch (e: Exception) {
            return UpdateCheckResult.Failed("返回内容无法解析（可能是网络被劫持或接口限流）")
        }

        val tag = release.optString("tag_name").ifBlank { release.optString("name") }
        if (tag.isBlank()) {
            return UpdateCheckResult.Failed("未从 GitHub 返回中找到版本号")
        }
        val latest = cleanVersion(tag)

        // 下载来源
        val assets = release.optJSONArray("assets")
        val githubUrl = if (assets != null && assets.length() > 0) {
            // 优先挑 apk，没有就退回第一个附件
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
                ?: assets.optJSONObject(0)?.optString("browser_download_url")
        } else null

        val bodyText = release.optString("body")
        val lanzou = parseLanzou(bodyText)

        // 比较：先去掉 -test 这类后缀，只比数字部分
        val current = cleanVersion(currentLabel)
        return if (compareVersions(latest, current) > 0) {
            UpdateCheckResult.Available(
                latest = latest,
                current = currentLabel,
                notes = extractNotes(bodyText),
                githubUrl = githubUrl,
                lanzou = lanzou,
            )
        } else {
            UpdateCheckResult.UpToDate(latest)
        }
    }

    /** 自动检查：满足间隔才真的请求；返回结果（不该查时返回 null） */
    fun checkIfDue(currentLabel: String, force: Boolean = false): UpdateCheckResult? {
        if (!force && !shouldAutoCheck()) return null
        val result = check(currentLabel)
        // 失败也记录时间，否则断网时每次进前台都会重试，反而更耗电
        markChecked()
        if (result is UpdateCheckResult.Available) {
            lastKnownLatest = result.latest
        }
        return result
    }

    // ------------------------------------------------------------------ //
    // 解析工具
    // ------------------------------------------------------------------ //

    /**
     * 从 release 正文里提取蓝奏云链接与密码。
     *
     * 约定的写法（见仓库的 release 模板）：
     * ```
     * ## 📥 下载
     * [蓝奏云链接，密码：wbxl](https://wwbli.lanzouq.com/iIrm34bfdjzi)
     * ```
     * 所以真正的链接在 markdown 圆括号里，密码在中括号的「密码：」后面，
     * 只取 `[链接](url)` 那种形式，避免误抓正文里其他 url。
     */
    private val LANZOU_ENTRY = Regex(
        """\[([^\]]*?)蓝奏云([^\]]*?)\]\s*\(\s*(https?://[^\s)]+)\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val PASSWORD_IN_LABEL = Regex(
        """(?:密码|提取码|访问码)\s*[:：]?\s*([A-Za-z0-9]{2,12})"""
    )
    private val PASSWORD_IN_TEXT = Regex(
        """(?:密码|提取码|访问码)\s*[:：]?\s*([A-Za-z0-9]{2,12})"""
    )

    fun parseLanzou(body: String?): UpdateSource? {
        if (body.isNullOrBlank()) return null
        val m = LANZOU_ENTRY.find(body) ?: return null
        val label = m.groupValues[1] + m.groupValues[2]
        val url = m.groupValues[3].trim()
        if (url.isBlank()) return null
        val pwd = PASSWORD_IN_LABEL.find(label)?.groupValues?.get(1)
            ?: PASSWORD_IN_TEXT.find(body)?.groupValues?.get(1)
        return UpdateSource(
            label = "从蓝奏云下载（国内用户推荐）",
            url = url,
            password = pwd,
            recommended = true,
        )
    }

    /** 取 release 正文里「更新内容」那一节，弹窗只展示这部分，太长就截断 */
    private fun extractNotes(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val text = body.replace("\r\n", "\n")
        // 抓「## ✨ 更新内容」到下一个 "## " 之间（withoutEnd 保证正文末尾也能取到）
        val section = Regex(
            """^#{1,3}\s*[^\n]*更新内容[^\n]*\n([\s\S]*?)(?=^#{1,3}\s|\z)""",
            setOf(RegexOption.MULTILINE)
        ).find(text)?.groupValues?.get(1)?.trim()
        val raw = section?.takeIf { it.isNotBlank() } ?: return ""

        // 简单清掉 markdown 标记，弹窗里纯文本更好读
        val cleaned = raw
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""(?m)^\s*[-*+]\s+"""), "· ")
            .trim()

        val lines = cleaned.lines().filter { it.isNotBlank() }
        val trimmed = lines.take(10).joinToString("\n")
        return if (lines.size > 10) "$trimmed\n…" else trimmed
    }

    /** `v1.1` / `1.1-test` / `release-1.2.0` → 只留数字部分 `1.1` */
    fun cleanVersion(raw: String): String {
        val m = Regex("""(\d+(?:\.\d+)*)""").find(raw) ?: return raw.trim()
        return m.groupValues[1]
    }

    /** 逐段比较版本号，返回 >0 表示 a 比 b 新 */
    fun compareVersions(a: String, b: String): Int {
        val pa = cleanVersion(a).split('.').map { it.toIntOrNull() ?: 0 }
        val pb = cleanVersion(b).split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun networkReason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "无法连接 GitHub（请检查网络或稍后再试）"
        is java.net.SocketTimeoutException -> "连接 GitHub 超时（国内网络可能需要代理）"
        is java.io.IOException -> "网络请求失败：${e.message ?: "IO 错误"}"
        else -> "检查更新失败：${e.message ?: e.javaClass.simpleName}"
    }

    /** 极简 GET：只用系统自带的 HttpURLConnection，不额外引入依赖 */
    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "cxrunner-app")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
