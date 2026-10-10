package com.cxrunner.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.content.ContentValues
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.AppSettings
import com.cxrunner.app.data.LOG_FONT_SIZE_MAX
import com.cxrunner.app.data.LOG_FONT_SIZE_MIN
import com.cxrunner.app.data.ProgressItem
import com.cxrunner.app.data.ProgressStore
import com.cxrunner.app.data.RunMode
import com.cxrunner.app.data.RunnerController
import com.cxrunner.app.service.RunnerService
import com.cxrunner.app.service.ScheduleScheduler
import com.cxrunner.app.ui.components.InputPromptDialog
import com.cxrunner.app.ui.components.LogConsole
import com.cxrunner.app.ui.components.NoticeDialog
import com.cxrunner.app.ui.components.PermissionCardRow
import com.cxrunner.app.ui.components.PermissionCardState
import com.cxrunner.app.ui.components.PermissionCardStatus
import com.cxrunner.app.ui.components.ProgressPanel
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onEditCookies: () -> Unit,
    onOpenTikuSettings: () -> Unit,
    onToast: (String) -> Unit,
    resumeTick: Int = 0,
) {
    val context = LocalContext.current

    val running by RunnerController.running.collectAsState()
    val statusText by RunnerController.statusText.collectAsState()
    val inputRequest by RunnerController.inputRequest.collectAsState()

    var logText by remember { mutableStateOf("") }
    var logOffset by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableStateOf(emptyList<ProgressItem>()) }

    val refreshPermission = remember { mutableStateOf(0) }

    fun readLog(reset: Boolean = false) {
        val file = AppFiles.logFile
        if (!file.exists()) {
            if (reset) logText = ""
            return
        }
        val len = file.length()
        if (reset || len < logOffset) {
            logOffset = 0
            logText = ""
        }
        if (len > logOffset) {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(logOffset)
                    val size = (len - logOffset).toInt()
                    val buffer = ByteArray(size)
                    raf.readFully(buffer)
                    logText += String(buffer, Charsets.UTF_8)
                }
                logOffset = len
                val lines = logText.split('\n')
                if (lines.size > 3000) {
                    logText = lines.takeLast(3000).joinToString("\n")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(Unit) { readLog(true) }
    LaunchedEffect(resumeTick) { if (resumeTick > 0) { readLog(); refreshPermission.value++ } }

    // 自动刷新：运行中每 800ms 读一次增量日志 + 进度。
    // 原来只在 running 变为 true 那一刻启动一次 while 循环，
    // 循环体内判断的是首次进入时的 running 值，中途 resumeTick 变化也不会重启，
    // 表现就是"日志不刷新"。这里把依赖收敛到 running / autoRefresh，
    // 并且每轮都重新读取最新状态。
    LaunchedEffect(running, settings.autoRefresh) {
        if (!running || !settings.autoRefresh) return@LaunchedEffect
        while (true) {
            delay(800)
            if (!RunnerController.running.value) break
            readLog()
            progress = ProgressStore.read()
        }
    }

    // 运行结束时（running 由 true 变 false，本协程随之重启）再兜底读一次，
    // 把收尾日志补上并清掉进度面板——否则最后几行会停留在旧内容上。
    LaunchedEffect(running) {
        if (!running) {
            readLog()
            progress = emptyList()
        }
    }

    // ------------------------------------------------------------------ //
    // 定时启动 + 课程列表为空
    //
    // 配置文件里没填课程列表时，脚本登录完会问「选择要刷的课程」。
    // 手动运行时弹窗问一句没问题；**定时启动时没人守着弹窗**，脚本会一直
    // 卡在 input() 上、后台挂着不动。所以定时启动拉起的那次运行，
    // 课程请求由 RunnerService 直接自动应答「留空」= 全部课程
    // （见 RunnerService.watchLoop），不依赖界面是否在前台。
    // 这里只负责在条件成立时提示用户一次。
    // ------------------------------------------------------------------ //
    val scheduleOn = settings.schedule.enabled && settings.runMode == RunMode.CONFIG
    val courseListEmpty = settings.config.courseList.isBlank()
    // 「是否已经提示过」必须是**持久化**状态：
    // 放在 remember 里的话，从设置页返回主页时界面重建、标记被清零，弹窗会反复出现。
    // 现在写进 settings.json，只有在设置里重新打开定时启动时才会清零（见 SettingsScreen）。
    var showEmptyCourseNotice by remember { mutableStateOf(false) }
    LaunchedEffect(scheduleOn, courseListEmpty, settings.scheduleEmptyNoticeAck) {
        if (scheduleOn && courseListEmpty && !settings.scheduleEmptyNoticeAck) {
            showEmptyCourseNotice = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermission.value++ }

    val cards = remember(settings.config, refreshPermission.value) {
        buildPermissionCards(
            context = context,
            settings = settings,
            requestNotification = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onEditCookies = onEditCookies,
            onOpenTikuSettings = onOpenTikuSettings,
            onOpenAutostart = { openAutostartSettings(context) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 顶部避开状态栏 / 刘海，底部避开系统导航栏与手势条，
            // 键盘弹出时一并避让（safeDrawing = 系统栏 ∪ 刘海 ∪ IME）
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "超星刷课助手",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "刷课脚本(Python)会在后台常驻运行",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenHelp() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "?",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                }
            }
            Row(modifier = Modifier.padding(start = 8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onOpenSettings() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" 设置", fontSize = 13.sp)
                    }
                }
            }
        }

        PermissionCardRow(cards)

        // 模式选择 + 开始/停止
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeSegmentedControl(
                selected = settings.runMode,
                onSelect = { onSettingsChange(settings.copy(runMode = it)) },
                enabled = !running,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (running) {
                        RunnerController.requestStop()
                        RunnerController.setStatus("正在停止…")
                    } else {
                        val intent = Intent(context, RunnerService::class.java).apply {
                            action = RunnerService.ACTION_START
                            putExtra(RunnerService.EXTRA_MODE, settings.runMode.name)
                        }
                        ContextCompat.startForegroundService(context, intent)
                    }
                },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (running) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(if (running) " 停止运行" else " 开始运行", fontSize = 15.sp)
            }
        }

        // 状态条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotColor = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dotColor),
            )
            Text(
                " ${statusText} · ${settings.runMode.label}",
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "config.ini ${if (AppFiles.configFile.exists()) "已生成" else "未生成"}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ProgressPanel(
            items = progress,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        LogConsole(
            text = logText,
            fontSize = settings.fontSize,
            autoRefresh = settings.autoRefresh,
            onAutoRefreshChange = { onSettingsChange(settings.copy(autoRefresh = it)) },
            onRefresh = { readLog(); progress = ProgressStore.read(); onToast("已刷新") },
            onClear = {
                try {
                    AppFiles.logFile.writeText("")
                } catch (_: Exception) {
                }
                logText = ""
                logOffset = 0
                progress = emptyList()
                onToast("日志已清除")
            },
            onExport = {
                val name = exportLog(context)
                onToast(if (name != null) "已导出到下载目录：$name" else "导出失败")
            },
            onZoomIn = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(LOG_FONT_SIZE_MAX))) },
            onZoomOut = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(LOG_FONT_SIZE_MIN))) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    inputRequest?.let { request ->
        InputPromptDialog(
            request = request,
            onSubmit = { RunnerController.respond(request.id, it) },
            onCancel = {
                if (request.kind == "confirm") RunnerController.respond(request.id, "n")
                else RunnerController.respond(request.id, null)
            },
        )
    }

    if (showEmptyCourseNotice) {
        NoticeDialog(
            message = "当前配置文件里的课程列表为空。若不补充，则自启动时默认刷全部课程",
            confirmText = "我已了解",
            onDismiss = {
                showEmptyCourseNotice = false
                // 记进设置里，之后进主页都不再弹；重新开启定时启动时会被清零
                onSettingsChange(settings.copy(scheduleEmptyNoticeAck = true))
            },
        )
    }
}

@Composable
private fun ModeSegmentedControl(
    selected: RunMode,
    onSelect: (RunMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        RunMode.entries.forEach { mode ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled) { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mode.label,
                    fontSize = 13.sp,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 布尔型检测结果 → 卡片状态（真/假都有明确结论，不会出现「无法检测」）。 */
private fun Boolean.toCardStatus(): PermissionCardStatus =
    if (this) PermissionCardStatus.GRANTED else PermissionCardStatus.MISSING

private fun buildPermissionCards(
    context: Context,
    settings: AppSettings,
    requestNotification: () -> Unit,
    onEditCookies: () -> Unit,
    onOpenTikuSettings: () -> Unit,
    onOpenAutostart: (() -> Unit)? = null,
): List<PermissionCardState> {
    val cards = mutableListOf<PermissionCardState>()

    // 1. 通知权限（前台服务通知）
    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    } else true
    cards += PermissionCardState(
        id = "notification",
        title = "通知权限",
        detail = "运行状态的常驻通知",
        status = notifGranted.toCardStatus(),
        icon = Icons.Filled.Notifications,
        onClick = {
            if (notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else requestNotification()
        },
    )

    // 2. 后台保活（电池优化）
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    cards += PermissionCardState(
        id = "battery",
        title = "后台常驻",
        detail = "关闭电池优化以免被系统休眠",
        status = ignoring.toCardStatus(),
        grantedText = "已放行",
        missingText = "去设置",
        icon = Icons.Filled.Bolt,
        onClick = {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            }
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
    )

    // 3. 网络
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val networkOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        cm?.activeNetwork != null
    } else true
    cards += PermissionCardState(
        id = "network",
        title = "网络访问",
        detail = if (networkOk) "已连接到网络" else "当前无可用网络",
        status = networkOk.toCardStatus(),
        grantedText = "已连接",
        missingText = "去设置",
        icon = Icons.Filled.Wifi,
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
    )

    // 4. 条件卡片：使用 Cookie 登录时才需要 cookies.txt
    if (settings.config.useCookies) {
        val has = AppFiles.cookiesFile.exists() && AppFiles.cookiesFile.readText().isNotBlank()
        cards += PermissionCardState(
            id = "cookies",
            title = "Cookies 文件",
            detail = if (has) "cookies.txt 已就绪" else "缺少 cookies.txt",
            status = has.toCardStatus(),
            grantedText = "已就绪",
            missingText = "去编辑",
            icon = Icons.Filled.Lock,
            onClick = onEditCookies,
        )
    }

    // 5. 条件卡片：启用大模型题库时需要配置 Key
    val needAi = settings.config.tikuProvider.any { it == "AI" || it == "SiliconFlow" }
    if (needAi) {
        val ready = (settings.config.endpoint.isNotBlank() && settings.config.key.isNotBlank()) ||
                settings.config.siliconflowKey.isNotBlank()
        cards += PermissionCardState(
            id = "ai",
            title = "大模型配置",
            detail = if (ready) "API 已配置" else "未填写 Key / Endpoint",
            status = ready.toCardStatus(),
            grantedText = "已配置",
            missingText = "去填写",
            icon = Icons.Filled.Settings,
            onClick = onOpenTikuSettings,
        )
    }

    // 5. 条件卡片：开启定时启动时，需要精确闹钟 + 开机自启
    if (settings.schedule.enabled && settings.runMode == RunMode.CONFIG) {
        // 精确闹钟：Android 12+ 必须单独授权，否则系统会推迟甚至丢弃闹钟
        val exactOk = ScheduleScheduler.hasExactAlarmPermission(context)
        cards += PermissionCardState(
            id = "exact_alarm",
            title = "精确闹钟",
            detail = if (exactOk) "可准时触发定时任务" else "缺少权限，到点可能延迟",
            status = exactOk.toCardStatus(),
            grantedText = "已授权",
            missingText = "去授权",
            icon = Icons.Filled.Alarm,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } else {
                    // Android 11 及以下没有精确闹钟开关，闹钟本身就能按时触发
                    onOpenAutostart?.invoke()
                }
            },
        )

        // 开机自启：各厂商 ROM 的入口不统一，能找到的就直接跳过去。
        // 状态一律由系统实测得出（见 detectAutostart）；查不出结论就如实显示
        // 「无法检测」，既不误报成未授权，也不要求用户自己确认。
        val autostartStatus = detectAutostart(context)
        cards += PermissionCardState(
            id = "autostart",
            title = "开机自启",
            detail = if (autostartStatus == PermissionCardStatus.UNKNOWN) {
                "该 ROM 查不到开关，可点此前往设置"
            } else {
                "重启后自动恢复定时任务"
            },
            status = autostartStatus,
            grantedText = "已允许",
            missingText = "去设置",
            unknownText = "无法检测",
            icon = Icons.Filled.PowerSettingsNew,
            onClick = { onOpenAutostart?.invoke() },
        )
    }

    // 需要处理的排前面：未允许 → 无法检测 → 已允许；
    // 同一组内保持原来的先后顺序（sortedBy 是稳定排序：
    // 网络访问 与 大模型配置 都没配置好时，仍是网络访问在前）。
    return cards.sortedBy { it.status.ordinal }
}

/**
 * 查询「自启动 / 后台自启」的状态，查不出结论就返回 UNKNOWN。
 *
 * 现实：AOSP 原生没有「自启动」这个开关，各厂商 ROM 各用各的 AppOps 操作码，
 * 没有统一的公开 API。所以分三层判定，**全程由系统实测，不猜、也不让用户自己确认**：
 *
 *  1. 先用反射试厂商那套自启动 AppOps（MIUI 常用 `OP_AUTO_START = 10000`），
 *     拿到 ALLOWED / IGNORED / ERRORED 就是明确结论。
 *  2. 查不到，但系统明确限制了本应用后台运行
 *     （`ActivityManager.isBackgroundRestricted`）→ 那自启动一定不生效，判 MISSING。
 *  3. 除此之外真的判断不了（AOSP 没这个开关、该 ROM 也没实现对应 op）→ 如实返回
 *     UNKNOWN，界面显示「无法检测」，既不误报成未授权，也不骚扰用户。
 *
 * 用反射而不是直接调 `AppOpsManager.checkOpNoThrow(int, int, String)` 的原因：
 * 不同 SDK 上那个名字存在多个重载（`(String, Int, String)` 等），
 * 直接调会解析到错误的重载而编译失败，所以按参数签名筛选。
 */
private fun detectAutostart(context: Context): PermissionCardStatus {
    queryAutostartAppOps(context)?.toCardStatus()?.let { return it }
    if (isBackgroundRestricted(context)) return PermissionCardStatus.MISSING
    return PermissionCardStatus.UNKNOWN
}

/** 厂商自启动 AppOps；该 ROM 没实现这个 op（或查不到）时返回 null。 */
private fun queryAutostartAppOps(context: Context): Boolean? {
    return try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) ?: return null
        val method = ops.javaClass.methods.firstOrNull {
            it.name == "checkOpNoThrow" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Integer.TYPE &&
                    it.parameterTypes[1] == Integer.TYPE &&
                    it.parameterTypes[2] == String::class.java
        } ?: return null
        val mode = method.invoke(ops, OP_AUTO_START, context.applicationInfo.uid, context.packageName)
                as? Int ?: return null
        when (mode) {
            OP_MODE_ALLOWED -> true
            OP_MODE_IGNORED, OP_MODE_ERRORED -> false
            else -> null // MODE_DEFAULT 等：系统没表态，交给下面的通用判定
        }
    } catch (e: Throwable) {
        null
    }
}

/** 系统是否限制了本应用的后台运行（Android 9+；被限制时定时任务不会被拉起）。 */
private fun isBackgroundRestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.isBackgroundRestricted == true
    } catch (e: Throwable) {
        false
    }
}

/** MIUI / 多数国产 ROM 的「自启动」AppOps 操作码 */
private const val OP_AUTO_START = 10000

// AppOpsManager.MODE_* 的字面量，避免依赖 SDK 里可能变动/隐藏的常量
private const val OP_MODE_ALLOWED = 0
private const val OP_MODE_IGNORED = 1
private const val OP_MODE_ERRORED = 2

/**
 * 跳转到系统「自启动管理」。
 *
 * 各厂商 ROM 的入口 Activity 都不一样，且 AOSP 原生并没有这个页面，
 * 所以这里按常见厂商依次尝试，都失败就退回应用详情页让用户自己找。
 */
private fun openAutostartSettings(context: Context) {
    val candidates = listOf(
        // 小米 / 红米
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // 华为 / 荣耀
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        // OPPO / 一加 / realme
        "com.coloros.safecenter" to "com.coloros.safecenter.startupappinfo.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // vivo
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // 三星
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        // 魅族
        "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
    )
    for ((pkg, cls) in candidates) {
        val intent = Intent().setClassName(pkg, cls)
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
    }
    // 兜底：应用详情页（部分 ROM 把"自启动"放在这里）
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun exportLog(context: Context): String? {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
    val name = "chaoxing-$stamp.log"
    return try {
        val bytes = if (AppFiles.logFile.exists()) AppFiles.logFile.readBytes() else ByteArray(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return null
            dir.mkdirs()
            java.io.File(dir, name).writeBytes(bytes)
        }
        name
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
