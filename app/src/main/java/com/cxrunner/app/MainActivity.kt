package com.cxrunner.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.AppSettings
import com.cxrunner.app.data.SettingsStore
import com.cxrunner.app.data.UpdateCheckResult
import com.cxrunner.app.data.UpdateManager
import com.cxrunner.app.service.ScheduleScheduler
import com.cxrunner.app.ui.HelpScreen
import com.cxrunner.app.ui.MainScreen
import com.cxrunner.app.ui.SettingsScreen
import com.cxrunner.app.ui.components.TextEditorDialog
import com.cxrunner.app.ui.components.UpdateAvailableDialog
import com.cxrunner.app.ui.components.UpdateSourceDialog
import com.cxrunner.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边显示：Android 15(API 35)+ 已是强制行为，这里显式开启以保证
        // 各版本表现一致，再由 Compose 侧的 WindowInsets 负责避让系统栏。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            var settings by remember { mutableStateOf(SettingsStore.load()) }
            var screen by remember { mutableStateOf("main") }
            var openTikuSection by remember { mutableStateOf(false) }
            var showCookies by remember { mutableStateOf(false) }
            var cookiesText by remember { mutableStateOf("") }
            val tick by resumeTick
            val uriHandler = LocalUriHandler.current

            fun toast(message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }

            fun reloadAfterReset() {
                settings = AppSettings()
                SettingsStore.save(settings)
                openTikuSection = false
                // 恢复出厂会关掉定时启动，这里同步把已登记的闹钟撤掉
                ScheduleScheduler.sync(this@MainActivity)
            }

            // ---------------- 自动检查更新 ----------------
            // 每 24 小时最多一次；只在冷启动做，不打断正在跑的脚本
            // （这里只发网络请求 + 弹对话框，不动 RunnerController 的任何状态）。
            var pendingUpdate by remember { mutableStateOf<UpdateCheckResult.Available?>(null) }
            var updateUiState by remember { mutableStateOf(0) } // 0=未处理 1=显示弹窗 2=选源
            LaunchedEffect(Unit) {
                if (UpdateManager.isMuted()) return@LaunchedEffect
                if (!UpdateManager.shouldAutoCheck()) return@LaunchedEffect
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    UpdateManager.checkIfDue(UpdateManager.currentVersionLabel)
                }
                if (result is UpdateCheckResult.Available &&
                    !UpdateManager.alreadyNotified(result.latest)
                ) {
                    pendingUpdate = result
                }
            }

            // 定时启动的闹钟依赖设置项：开关、时间窗、运行模式任一变化都要重排。
            // LaunchedEffect 保证只在这些字段真正变化时触发一次。
            LaunchedEffect(
                settings.schedule.enabled,
                settings.schedule.startMinute,
                settings.schedule.endMinute,
                settings.runMode,
            ) {
                ScheduleScheduler.sync(this@MainActivity)
            }

            AppTheme(settings.themeMode) {
                when (screen) {
                    "settings" -> {
                        BackHandler { screen = "main"; openTikuSection = false }
                        SettingsScreen(
                            settings = settings,
                            onChange = { settings = it; SettingsStore.save(it) },
                            onBack = { screen = "main"; openTikuSection = false },
                            onToast = { toast(it) },
                            onEditCookies = {
                                cookiesText = try {
                                    if (AppFiles.cookiesFile.exists()) AppFiles.cookiesFile.readText() else ""
                                } catch (e: Exception) {
                                    ""
                                }
                                showCookies = true
                            },
                            tikuExpanded = openTikuSection,
                            onFactoryReset = {
                                AppFiles.factoryReset()
                                reloadAfterReset()
                                toast("已恢复出厂设置")
                                screen = "main"
                            },
                        )
                    }
                    "help" -> {
                        BackHandler { screen = "main" }
                        HelpScreen(onBack = { screen = "main" })
                    }
                    else -> MainScreen(
                        settings = settings,
                        onSettingsChange = { settings = it; SettingsStore.save(it) },
                        onOpenSettings = { screen = "settings" },
                        onOpenHelp = { screen = "help" },
                        onEditCookies = {
                            cookiesText = try {
                                if (AppFiles.cookiesFile.exists()) AppFiles.cookiesFile.readText() else ""
                            } catch (e: Exception) {
                                ""
                            }
                            showCookies = true
                        },
                        onOpenTikuSettings = { openTikuSection = true; screen = "settings" },
                        onToast = { toast(it) },
                        resumeTick = tick,
                    )
                }

                if (showCookies) {
                    TextEditorDialog(
                        title = "编辑 cookies.txt",
                        initialText = cookiesText,
                        onDismiss = { showCookies = false },
                        onSave = { text ->
                            try {
                                AppFiles.prepare()
                                AppFiles.cookiesFile.writeText(text.trim())
                                toast("cookies.txt 已保存")
                            } catch (e: Exception) {
                                toast("保存失败：${e.message}")
                            }
                            showCookies = false
                        },
                    )
                }

                // ---------------- 启动时的更新提醒 ----------------
                // 注意：这个弹窗只是浮在界面上，不会暂停/停止脚本；
                // 如果用户正在刷课，关闭弹窗后任务照常继续。
                val upd = pendingUpdate
                if (upd != null) {
                    if (updateUiState != 1) {
                        UpdateAvailableDialog(
                            currentVersion = upd.current,
                            latestVersion = upd.latest,
                            notes = upd.notes,
                            canMute = true,
                            onOpenSourcePicker = { updateUiState = 1 },
                            onMuteAndClose = {
                                UpdateManager.setMuted(true)
                                UpdateManager.markNotified(upd.latest)
                                pendingUpdate = null
                                toast("已关闭更新提醒，可在设置里重新打开")
                            },
                            onClose = {
                                // 只是关掉：明天还会再提醒
                                UpdateManager.markNotified(upd.latest)
                                pendingUpdate = null
                                updateUiState = 0
                            },
                        )
                    } else {
                        UpdateSourceDialog(
                            githubUrl = upd.githubUrl,
                            lanzou = upd.lanzou,
                            onOpen = { source ->
                                runCatching { uriHandler.openUri(source.url) }
                                    .onFailure { toast("无法打开浏览器：${it.message}") }
                                UpdateManager.markNotified(upd.latest)
                                pendingUpdate = null
                                updateUiState = 0
                            },
                            onDismiss = {
                                UpdateManager.markNotified(upd.latest)
                                pendingUpdate = null
                                updateUiState = 0
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}
