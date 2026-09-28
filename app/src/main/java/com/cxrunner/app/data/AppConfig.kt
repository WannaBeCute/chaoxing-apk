package com.cxrunner.app.data

/** 运行模式：直接运行 / 按配置文件运行 */
enum class RunMode(val label: String) {
    DIRECT("直接运行"),
    CONFIG("按配置文件运行");

    companion object {
        fun fromName(name: String?): RunMode = entries.firstOrNull { it.name == name } ?: DIRECT
    }
}

/** 题库 provider 列表（顺序即回退顺序） */
val TIKU_PROVIDERS = listOf(
    "TikuYanxi", "TikuLike", "TikuAdapter", "AI", "SiliconFlow", "TikuGo", "TikuManual"
)

val NOTIFY_PROVIDERS = listOf("ServerChan", "Qmsg", "Bark", "Telegram")

/**
 * 与 config_template.ini 一一对应的配置项。
 * 所有默认值均取自项目自带的配置模板。
 */
data class AppConfig(
    // ---------------- [common] ----------------
    var useCookies: Boolean = false,
    var username: String = "xxx",
    var password: String = "xxx",
    var courseList: String = "",
    var speed: Float = 1.0f,
    var jobs: Int = 4,
    var notopenAction: String = "retry",
    var retryInterval: Float = 1.0f,
    var addLearningCount: Boolean = false,
    var targetCount: Int = 100,
    var workMaxRetries: Int = 3,

    // ---------------- [tiku] ----------------
    var tikuProvider: List<String> = listOf("TikuYanxi"),
    var checkLlmConnection: Boolean = true,
    var submit: Boolean = false,
    var coverRate: Float = 0.9f,
    var delay: Float = 1.0f,
    var tokens: String = "",
    var likeapiSearch: Boolean = false,
    var likeapiVision: Boolean = true,
    var likeapiModel: String = "glm-4.5-air",
    var likeapiRetry: Boolean = true,
    var likeapiRetryTimes: Int = 3,
    var tikuUrl: String = "",
    var goAuthorization: String = "",
    var goMinInterval: Float = 1.0f,
    var goRetryTimes: Int = 3,
    var goRetryBackoff: Float = 1.2f,
    var endpoint: String = "",
    var key: String = "",
    var model: String = "",
    var minIntervalSeconds: Int = 3,
    var httpProxy: String = "",
    var siliconflowKey: String = "",
    var siliconflowModel: String = "deepseek-ai/DeepSeek-R1",
    var siliconflowEndpoint: String = "https://api.siliconflow.cn/v1/chat/completions",
    var manualModeDefault: String = "batch",
    var manualModeSeparator: String = ";",
    var trueList: String = "正确,对,√,是",
    var falseList: String = "错误,错,×,否,不对,不正确",

    // ---------------- [notification] ----------------
    var notifyProvider: String = "ServerChan",
    var notifyUrl: String = "",
    var tgChatId: String = "XXXXXX",
) {
    /** key = "section.key" -> 写入配置文件的字符串值 */
    fun toIniValues(): Map<String, String> = mapOf(
        "common.use_cookies" to useCookies.s(),
        "common.username" to username,
        "common.password" to password,
        "common.course_list" to courseList,
        "common.speed" to speed.num(),
        "common.jobs" to jobs.toString(),
        "common.notopen_action" to notopenAction,
        "common.retry_interval" to retryInterval.num(),
        "common.add_learning_count" to addLearningCount.s(),
        "common.target_count" to targetCount.toString(),
        "common.work_max_retries" to workMaxRetries.toString(),

        "tiku.provider" to tikuProvider.joinToString(","),
        "tiku.check_llm_connection" to checkLlmConnection.s(),
        "tiku.submit" to submit.s(),
        "tiku.cover_rate" to coverRate.num(),
        "tiku.delay" to delay.num(),
        "tiku.tokens" to tokens,
        "tiku.likeapi_search" to likeapiSearch.s(),
        "tiku.likeapi_vision" to likeapiVision.s(),
        "tiku.likeapi_model" to likeapiModel,
        "tiku.likeapi_retry" to likeapiRetry.s(),
        "tiku.likeapi_retry_times" to likeapiRetryTimes.toString(),
        "tiku.url" to tikuUrl,
        "tiku.go_authorization" to goAuthorization,
        "tiku.go_min_interval" to goMinInterval.num(),
        "tiku.go_retry_times" to goRetryTimes.toString(),
        "tiku.go_retry_backoff" to goRetryBackoff.num(),
        "tiku.endpoint" to endpoint,
        "tiku.key" to key,
        "tiku.model" to model,
        "tiku.min_interval_seconds" to minIntervalSeconds.toString(),
        "tiku.http_proxy" to httpProxy,
        "tiku.siliconflow_key" to siliconflowKey,
        "tiku.siliconflow_model" to siliconflowModel,
        "tiku.siliconflow_endpoint" to siliconflowEndpoint,
        "tiku.manual_mode_default" to manualModeDefault,
        "tiku.manual_mode_separator" to manualModeSeparator,
        "tiku.true_list" to trueList,
        "tiku.false_list" to falseList,

        "notification.provider" to notifyProvider,
        "notification.url" to notifyUrl,
        "notification.tg_chat_id" to tgChatId,
    )

    /** 把 "section.key" -> value 的映射写回配置对象（用于手动编辑后回填） */
    fun applyIniValues(values: Map<String, String>) {
        fun b(k: String, default: Boolean) = values[k]?.let { it.trim().lowercase() in setOf("1", "true", "yes", "y", "on") } ?: default
        fun i(k: String, default: Int) = values[k]?.trim()?.toIntOrNull() ?: default
        fun f(k: String, default: Float) = values[k]?.trim()?.toFloatOrNull() ?: default
        fun s(k: String, default: String) = values[k] ?: default

        useCookies = b("common.use_cookies", useCookies)
        username = s("common.username", username)
        password = s("common.password", password)
        courseList = s("common.course_list", courseList)
        speed = f("common.speed", speed)
        jobs = i("common.jobs", jobs)
        notopenAction = s("common.notopen_action", notopenAction).ifBlank { "retry" }
        retryInterval = f("common.retry_interval", retryInterval)
        addLearningCount = b("common.add_learning_count", addLearningCount)
        targetCount = i("common.target_count", targetCount)
        workMaxRetries = i("common.work_max_retries", workMaxRetries)

        val provider = s("tiku.provider", tikuProvider.joinToString(","))
        tikuProvider = provider.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        checkLlmConnection = b("tiku.check_llm_connection", checkLlmConnection)
        submit = b("tiku.submit", submit)
        coverRate = f("tiku.cover_rate", coverRate)
        delay = f("tiku.delay", delay)
        tokens = s("tiku.tokens", tokens)
        likeapiSearch = b("tiku.likeapi_search", likeapiSearch)
        likeapiVision = b("tiku.likeapi_vision", likeapiVision)
        likeapiModel = s("tiku.likeapi_model", likeapiModel)
        likeapiRetry = b("tiku.likeapi_retry", likeapiRetry)
        likeapiRetryTimes = i("tiku.likeapi_retry_times", likeapiRetryTimes)
        tikuUrl = s("tiku.url", tikuUrl)
        goAuthorization = s("tiku.go_authorization", goAuthorization)
        goMinInterval = f("tiku.go_min_interval", goMinInterval)
        goRetryTimes = i("tiku.go_retry_times", goRetryTimes)
        goRetryBackoff = f("tiku.go_retry_backoff", goRetryBackoff)
        endpoint = s("tiku.endpoint", endpoint)
        key = s("tiku.key", key)
        model = s("tiku.model", model)
        minIntervalSeconds = i("tiku.min_interval_seconds", minIntervalSeconds)
        httpProxy = s("tiku.http_proxy", httpProxy)
        siliconflowKey = s("tiku.siliconflow_key", siliconflowKey)
        siliconflowModel = s("tiku.siliconflow_model", siliconflowModel)
        siliconflowEndpoint = s("tiku.siliconflow_endpoint", siliconflowEndpoint)
        manualModeDefault = s("tiku.manual_mode_default", manualModeDefault)
        manualModeSeparator = s("tiku.manual_mode_separator", manualModeSeparator)
        trueList = s("tiku.true_list", trueList)
        falseList = s("tiku.false_list", falseList)

        notifyProvider = s("notification.provider", notifyProvider)
        notifyUrl = s("notification.url", notifyUrl)
        tgChatId = s("notification.tg_chat_id", tgChatId)
    }

    private fun Boolean.s() = if (this) "true" else "false"
    private fun Float.num(): String = if (this == kotlin.math.floor(this) && kotlin.math.abs(this) < 1000f) {
        this.toInt().toString() + ".0"
    } else {
        this.toString()
    }

    companion object {
        fun defaults() = AppConfig()
    }
}

private val KEY_LINE = Regex("""^(\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*=\s*)(.*)$""")

/**
 * 以模板为骨架生成 config.ini：只替换键值，注释、空行、缩进与分隔符原样保留。
 */
fun renderConfigIni(template: String, config: AppConfig): String {
    val values = config.toIniValues()
    val src = template.replace("\r\n", "\n")
    val out = StringBuilder()
    var section = ""
    src.split("\n").forEach { raw ->
        val line = raw.trimEnd('\r')
        val trimmed = line.trim()
        when {
            trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                section = trimmed.substring(1, trimmed.length - 1).trim()
                out.append(line).append("\n")
            }
            trimmed.startsWith(";") || trimmed.startsWith("#") || trimmed.isEmpty() -> {
                out.append(line).append("\n")
            }
            else -> {
                val m = KEY_LINE.matchEntire(line)
                if (m == null) {
                    out.append(line).append("\n")
                } else {
                    val key = m.groupValues[2]
                    val value = values["$section.$key"]
                    if (value == null) {
                        out.append(line).append("\n")
                    } else {
                        out.append(m.groupValues[1]).append(key).append(m.groupValues[3]).append(value).append("\n")
                    }
                }
            }
        }
    }
    return out.toString()
}

/** 极简 ini 解析（只取 section.key -> value，忽略注释） */
fun parseConfigIni(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var section = ""
    text.replace("\r\n", "\n").split("\n").forEach { raw ->
        val line = raw.trim()
        if (line.startsWith(";") || line.startsWith("#") || line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1).trim()
            return@forEach
        }
        val idx = line.indexOf('=')
        if (idx <= 0) return@forEach
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        result["$section.$key"] = value
    }
    return result
}
