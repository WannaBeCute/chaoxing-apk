package com.cxrunner.app.ui

import android.Manifest
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
import com.cxrunner.app.data.ProgressItem
import com.cxrunner.app.data.ProgressStore
import com.cxrunner.app.data.RunMode
import com.cxrunner.app.data.RunnerController
import com.cxrunner.app.service.RunnerService
import com.cxrunner.app.service.ScheduleScheduler
import com.cxrunner.app.ui.components.InputPromptDialog
import com.cxrunner.app.ui.components.LogConsole
import com.cxrunner.app.ui.components.PermissionCardRow
import com.cxrunner.app.ui.components.PermissionCardState
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
            onZoomIn = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(24))) },
            onZoomOut = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(8))) },
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
        granted = notifGranted,
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
        granted = ignoring,
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
        granted = networkOk,
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
            granted = has,
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
            granted = ready,
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
            granted = exactOk,
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

        // 开机自启：各厂商 ROM 的入口不统一，能找到的就直接跳过去
        cards += PermissionCardState(
            id = "autostart",
            title = "开机自启",
            detail = "重启后自动恢复定时任务",
            granted = false,
            grantedText = "请手动确认",
            missingText = "去设置",
            icon = Icons.Filled.PowerSettingsNew,
            onClick = { onOpenAutostart?.invoke() },
        )
    }

    return cards
}

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
