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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
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
import com.cxrunner.app.data.RunMode
import com.cxrunner.app.data.RunnerController
import com.cxrunner.app.service.RunnerService
import com.cxrunner.app.ui.components.InputPromptDialog
import com.cxrunner.app.ui.components.LogConsole
import com.cxrunner.app.ui.components.PermissionCardRow
import com.cxrunner.app.ui.components.PermissionCardState
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onOpenSettings: () -> Unit,
    onEditCookies: () -> Unit,
    onOpenTikuSettings: () -> Unit,
    onToast: (String) -> Unit,
    resumeTick: Int = 0,
) {
    val context = LocalContext.current

    val running by RunnerController.running.collectAsState()
    val statusText by RunnerController.statusText.collectAsState()
    val inputRequest by RunnerController.inputRequest.collectAsState()
    val logTick by RunnerController.logTick.collectAsState()

    var logText by remember { mutableStateOf("") }
    var logOffset by remember { mutableLongStateOf(0L) }

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

    // 自动刷新
    LaunchedEffect(running, settings.autoRefresh, logTick) {
        while (running && settings.autoRefresh) {
            delay(800)
            readLog()
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
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                    "超星学习通助手",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Python 脚本后台常驻运行",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

        LogConsole(
            text = logText,
            fontSize = settings.fontSize,
            autoRefresh = settings.autoRefresh,
            onAutoRefreshChange = { onSettingsChange(settings.copy(autoRefresh = it)) },
            onRefresh = { readLog(); onToast("已刷新") },
            onClear = {
                try {
                    AppFiles.logFile.writeText("")
                } catch (_: Exception) {
                }
                logText = ""
                logOffset = 0
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

    return cards
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
