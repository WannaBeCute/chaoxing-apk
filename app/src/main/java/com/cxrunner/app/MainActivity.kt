package com.cxrunner.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.SettingsStore
import com.cxrunner.app.ui.MainScreen
import com.cxrunner.app.ui.SettingsScreen
import com.cxrunner.app.ui.components.TextEditorDialog
import com.cxrunner.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var settings by remember { mutableStateOf(SettingsStore.load()) }
            var screen by remember { mutableStateOf("main") }
            var openTikuSection by remember { mutableStateOf(false) }
            var showCookies by remember { mutableStateOf(false) }
            var cookiesText by remember { mutableStateOf("") }
            val tick by resumeTick

            fun toast(message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
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
                        )
                    }
                    else -> MainScreen(
                        settings = settings,
                        onSettingsChange = { settings = it; SettingsStore.save(it) },
                        onOpenSettings = { screen = "settings" },
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}
