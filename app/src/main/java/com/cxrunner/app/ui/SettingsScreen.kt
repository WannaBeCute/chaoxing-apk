package com.cxrunner.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cxrunner.app.data.AppConfig
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.AppSettings
import com.cxrunner.app.data.CourseItem
import com.cxrunner.app.data.InputRequest
import com.cxrunner.app.data.NOTIFY_PROVIDERS
import com.cxrunner.app.data.RunMode
import com.cxrunner.app.data.TIKU_PROVIDERS
import com.cxrunner.app.data.parseConfigIni
import com.cxrunner.app.data.renderConfigIni
import com.cxrunner.app.ui.components.ActionButton
import com.cxrunner.app.ui.theme.ThemeMode
import com.cxrunner.app.ui.components.ConfirmDialog
import com.cxrunner.app.ui.components.DropdownItem
import com.cxrunner.app.ui.components.InputPromptDialog
import com.cxrunner.app.ui.components.MonoText
import com.cxrunner.app.ui.components.MultiSelectChips
import com.cxrunner.app.ui.components.SectionCard
import com.cxrunner.app.ui.components.SliderItem
import com.cxrunner.app.ui.components.StepperItem
import com.cxrunner.app.ui.components.SwitchItem
import com.cxrunner.app.ui.components.TextEditorDialog
import com.cxrunner.app.ui.components.TextFieldItem
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onEditCookies: () -> Unit,
    tikuExpanded: Boolean = false,
) {
    val config = settings.config

    fun update(mutator: AppConfig.() -> Unit) {
        val next = config.copy().apply(mutator)
        onChange(settings.copy(config = next))
    }

    var showPreview by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showCoursePicker by remember { mutableStateOf(false) }
    var cachedCourses by remember { mutableStateOf(loadCachedCourses()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("设置", fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val ini = renderConfigIni(AppFiles.readTemplate(AppFiles.context), config)
                AppFiles.configFile.writeText(ini)
                onToast("已生成 ${AppFiles.configFile.name}")
            }) {
                Icon(Icons.Filled.RestartAlt, contentDescription = "生成配置文件")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ---------------- 界面 / 运行 ----------------
            SectionCard(title = "界面与运行", subtitle = "主题、日志字号、运行模式") {
                DropdownItem(
                    label = "UI 主题",
                    options = ThemeMode.entries.map { it.label },
                    selected = settings.themeMode.label,
                    onSelect = { label ->
                        val mode = ThemeMode.entries.first { it.label == label }
                        onChange(settings.copy(themeMode = mode))
                    },
                )
                StepperItem(
                    label = "日志字号",
                    value = settings.fontSize,
                    range = 8..24,
                    onValueChange = { onChange(settings.copy(fontSize = it)) },
                )
                DropdownItem(
                    label = "运行模式",
                    options = RunMode.entries.map { it.label },
                    selected = settings.runMode.label,
                    onSelect = { label ->
                        val mode = RunMode.entries.first { it.label == label }
                        onChange(settings.copy(runMode = mode))
                    },
                    desc = "直接运行 = python main.py；按配置文件运行 = python main.py -c config.ini",
                )
            }

            // ---------------- 账号区 ----------------
            SectionCard(title = "账号区", subtitle = "[common] 登录方式") {
                SwitchItem(
                    label = "使用 Cookie 登录",
                    desc = "开启后忽略账号密码，直接读取 cookies.txt",
                    checked = config.useCookies,
                    onCheckedChange = { update { useCookies = it } },
                )
                if (!config.useCookies) {
                    TextFieldItem(
                        label = "手机号",
                        value = config.username,
                        onValueChange = { update { username = it } },
                    )
                    TextFieldItem(
                        label = "密码",
                        value = config.password,
                        onValueChange = { update { password = it } },
                        password = true,
                    )
                } else {
                    ActionButton(text = "编辑 cookies.txt", onClick = onEditCookies, modifier = Modifier.fillMaxWidth())
                    MonoText("路径：${AppFiles.cookiesFile.absolutePath}")
                }
            }

            // ---------------- 课程区 ----------------
            SectionCard(title = "课程区", subtitle = "[common] 课程与播放") {
                TextFieldItem(
                    label = "课程 ID 列表（逗号分隔）",
                    value = config.courseList,
                    onValueChange = { update { courseList = it } },
                    desc = "留空表示运行时再选择 / 全部课程",
                    mono = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        text = if (cachedCourses.isEmpty()) "选择课程（需先运行一次）" else "选择课程（${cachedCourses.size}）",
                        onClick = {
                            cachedCourses = loadCachedCourses()
                            if (cachedCourses.isEmpty()) onToast("暂无缓存课程，先运行一次脚本即可缓存")
                            else showCoursePicker = true
                        },
                        icon = Icons.Filled.School,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                SliderItem(
                    label = "播放倍速",
                    value = config.speed,
                    range = 1.0f..2.0f,
                    steps = 9,
                    onValueChange = { update { speed = it } },
                    format = { "%.1fx".format(it) },
                )
                StepperItem(
                    label = "同时进行章节数 (jobs)",
                    value = config.jobs,
                    range = 1..16,
                    onValueChange = { update { jobs = it } },
                )
            }

            // ---------------- 行为区 ----------------
            SectionCard(title = "行为区", subtitle = "[common] 刷课行为") {
                DropdownItem(
                    label = "关闭任务点行为",
                    options = listOf("retry", "continue"),
                    selected = config.notopenAction,
                    onSelect = { update { notopenAction = it } },
                    desc = "retry=重试，continue=继续",
                )
                TextFieldItem(
                    label = "重试等待时间（秒）",
                    value = config.retryInterval.toString(),
                    onValueChange = { update { retryInterval = it.toFloatOrNull() ?: retryInterval } },
                    mono = true,
                )
                SwitchItem(
                    label = "提交答题",
                    desc = "关闭则只保存搜到的答案不提交",
                    checked = config.submit,
                    onCheckedChange = { update { submit = it } },
                )
                SliderItem(
                    label = "最低题库覆盖率",
                    value = config.coverRate,
                    range = 0.0f..1.0f,
                    steps = 19,
                    onValueChange = { update { coverRate = it } },
                    format = { "%.2f".format(it) },
                )
                SwitchItem(
                    label = "增加章节学习次数",
                    checked = config.addLearningCount,
                    onCheckedChange = { update { addLearningCount = it } },
                )
                if (config.addLearningCount) {
                    StepperItem(
                        label = "目标总次数",
                        value = config.targetCount,
                        range = 1..1000,
                        onValueChange = { update { targetCount = it } },
                    )
                }
                StepperItem(
                    label = "章节检测最大重做次数",
                    value = config.workMaxRetries,
                    range = 0..10,
                    onValueChange = { update { workMaxRetries = it } },
                )
            }

            // ---------------- 题库区 ----------------
            SectionCard(
                title = "题库区",
                subtitle = "[tiku] 题库与答题",
                collapsible = true,
                initiallyExpanded = tikuExpanded,
            ) {
                MultiSelectChips(
                    label = "provider（多选，按选择顺序回退）",
                    options = TIKU_PROVIDERS,
                    selected = config.tikuProvider,
                    onToggle = { name ->
                        update {
                            val list = tikuProvider.toMutableList()
                            if (list.contains(name)) list.remove(name) else list.add(name)
                            tikuProvider = list
                        }
                    },
                )

                if (config.tikuProvider.contains("TikuYanxi") || config.tikuProvider.contains("TikuLike")) {
                    TextFieldItem(
                        label = "tokens（言溪 / LIKE，逗号分隔）",
                        value = config.tokens,
                        onValueChange = { update { tokens = it } },
                        mono = true,
                        singleLine = false,
                    )
                }
                if (config.tikuProvider.contains("TikuLike")) {
                    SwitchItem("likeapi_search 联网搜索", checked = config.likeapiSearch, onCheckedChange = { update { likeapiSearch = it } })
                    SwitchItem("likeapi_vision 视觉识别", checked = config.likeapiVision, onCheckedChange = { update { likeapiVision = it } })
                    TextFieldItem("likeapi_model", value = config.likeapiModel, onValueChange = { update { likeapiModel = it } }, mono = true)
                    SwitchItem("likeapi_retry 自动重试", checked = config.likeapiRetry, onCheckedChange = { update { likeapiRetry = it } })
                    StepperItem("likeapi_retry_times", value = config.likeapiRetryTimes, range = 0..10, onValueChange = { update { likeapiRetryTimes = it } })
                }
                if (config.tikuProvider.contains("TikuAdapter")) {
                    TextFieldItem("TikuAdapter url", value = config.tikuUrl, onValueChange = { update { tikuUrl = it } }, mono = true)
                }
                if (config.tikuProvider.contains("AI")) {
                    TextFieldItem("endpoint", value = config.endpoint, onValueChange = { update { endpoint = it } }, mono = true, placeholder = "https://example.com/v1")
                    TextFieldItem("key", value = config.key, onValueChange = { update { key = it } }, mono = true, password = true)
                    TextFieldItem("model", value = config.model, onValueChange = { update { model = it } }, mono = true)
                    StepperItem("min_interval_seconds", value = config.minIntervalSeconds, range = 0..60, onValueChange = { update { minIntervalSeconds = it } })
                    TextFieldItem("http_proxy", value = config.httpProxy, onValueChange = { update { httpProxy = it } }, mono = true, placeholder = "http://example.com")
                }
                if (config.tikuProvider.contains("SiliconFlow")) {
                    TextFieldItem("siliconflow_key", value = config.siliconflowKey, onValueChange = { update { siliconflowKey = it } }, mono = true, password = true)
                    TextFieldItem("siliconflow_model", value = config.siliconflowModel, onValueChange = { update { siliconflowModel = it } }, mono = true)
                    TextFieldItem("siliconflow_endpoint", value = config.siliconflowEndpoint, onValueChange = { update { siliconflowEndpoint = it } }, mono = true)
                }
                if (config.tikuProvider.contains("TikuGo")) {
                    TextFieldItem("go_authorization", value = config.goAuthorization, onValueChange = { update { goAuthorization = it } }, mono = true)
                    TextFieldItem("go_min_interval", value = config.goMinInterval.toString(), onValueChange = { update { goMinInterval = it.toFloatOrNull() ?: goMinInterval } }, mono = true)
                    StepperItem("go_retry_times", value = config.goRetryTimes, range = 0..10, onValueChange = { update { goRetryTimes = it } })
                    TextFieldItem("go_retry_backoff", value = config.goRetryBackoff.toString(), onValueChange = { update { goRetryBackoff = it.toFloatOrNull() ?: goRetryBackoff } }, mono = true)
                }
                if (config.tikuProvider.contains("TikuManual")) {
                    DropdownItem(
                        label = "manual_mode_default",
                        options = listOf("batch", "single"),
                        selected = config.manualModeDefault,
                        onSelect = { update { manualModeDefault = it } },
                        desc = "batch=一次性输入全部，single=一题一输入",
                    )
                    TextFieldItem(
                        "manual_mode_separator",
                        value = config.manualModeSeparator,
                        onValueChange = { update { manualModeSeparator = it } },
                        mono = true,
                    )
                }

                SwitchItem(
                    label = "check_llm_connection",
                    desc = "启动时检查大模型连接（AI / SiliconFlow 生效）",
                    checked = config.checkLlmConnection,
                    onCheckedChange = { update { checkLlmConnection = it } },
                )
                TextFieldItem(
                    label = "delay（搜索题目间隔秒）",
                    value = config.delay.toString(),
                    onValueChange = { update { delay = it.toFloatOrNull() ?: delay } },
                    mono = true,
                )
                TextFieldItem("true_list", value = config.trueList, onValueChange = { update { trueList = it } }, mono = true)
                TextFieldItem("false_list", value = config.falseList, onValueChange = { update { falseList = it } }, mono = true)
            }

            // ---------------- 通知区 ----------------
            SectionCard(
                title = "通知区",
                subtitle = "[notification] 外部推送",
                collapsible = true,
                initiallyExpanded = false,
            ) {
                DropdownItem(
                    label = "provider",
                    options = NOTIFY_PROVIDERS,
                    selected = config.notifyProvider,
                    onSelect = { update { notifyProvider = it } },
                )
                TextFieldItem(
                    label = "url",
                    value = config.notifyUrl,
                    onValueChange = { update { notifyUrl = it } },
                    mono = true,
                    singleLine = false,
                    desc = "Server酱 / Qmsg / Bark / Telegram 的推送地址",
                )
                if (config.notifyProvider == "Telegram") {
                    TextFieldItem("tg_chat_id", value = config.tgChatId, onValueChange = { update { tgChatId = it } }, mono = true)
                }
            }

            // ---------------- 配置文件 ----------------
            SectionCard(title = "配置文件参数", subtitle = "输出格式与 config_template.ini 完全一致") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionButton(
                        text = "预览文本",
                        onClick = { showPreview = true },
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        text = "恢复默认",
                        onClick = { showReset = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                MonoText("生成路径：${AppFiles.configFile.absolutePath}")
            }
        }
    }

    if (showPreview) {
        TextEditorDialog(
            title = "config.ini 预览 / 编辑",
            initialText = renderConfigIni(AppFiles.readTemplate(AppFiles.context), config),
            onDismiss = { showPreview = false },
            onSave = { text ->
                val parsed = parseConfigIni(text)
                if (parsed.isEmpty()) {
                    onToast("未能解析配置内容，已放弃保存")
                } else {
                    update { applyIniValues(parsed) }
                    onToast("已按文本更新配置")
                }
                showPreview = false
            },
        )
    }

    if (showReset) {
        ConfirmDialog(
            title = "恢复默认配置",
            message = "将把「配置文件参数」全部恢复为模板默认值，账号密码也会被重置。是否继续？",
            confirmText = "恢复",
            onConfirm = {
                onChange(settings.copy(config = AppConfig.defaults()))
                showReset = false
                onToast("已恢复默认配置")
            },
            onDismiss = { showReset = false },
        )
    }

    if (showCoursePicker && cachedCourses.isNotEmpty()) {
        InputPromptDialog(
            request = InputRequest(
                id = -1,
                kind = "courses",
                prompt = "选择要写入配置文件的课程（留空=全部课程）",
                courses = cachedCourses,
            ),
            onSubmit = { value ->
                update { courseList = value }
                showCoursePicker = false
                onToast("已选择 ${value.split(",").count { it.isNotBlank() }} 门课程")
            },
            onCancel = { showCoursePicker = false },
        )
    }
}

private fun loadCachedCourses(): List<CourseItem> {
    return try {
        val file = AppFiles.coursesCacheFile
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        val list = mutableListOf<CourseItem>()
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            list.add(
                CourseItem(
                    id = o.optString("id"),
                    clazzId = o.optString("clazzId"),
                    title = o.optString("title"),
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}
