package com.cxrunner.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.cxrunner.app.MainActivity
import com.cxrunner.app.R
import com.cxrunner.app.data.AppConfig
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.CourseItem
import com.cxrunner.app.data.InputRequest
import com.cxrunner.app.data.RunMode
import com.cxrunner.app.data.RunnerController
import com.cxrunner.app.data.SettingsStore
import com.cxrunner.app.data.renderConfigIni
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 前台服务：在后台常驻运行 chaoxing 脚本。
 * Python 由 Chaquopy 在本进程内执行，日志写入 run.log，交互通过 ui 目录的文件协议完成。
 */
class RunnerService : Service() {

    companion object {
        const val ACTION_START = "com.cxrunner.app.action.START"
        const val ACTION_STOP = "com.cxrunner.app.action.STOP"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "runner_channel"
        private const val NOTIF_ID = 20240921
    }

    private var worker: Thread? = null
    private var watcher: Thread? = null
    private var stopped: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RunnerController.requestStop()
                RunnerController.setStatus("正在停止…")
            }
            ACTION_START -> startRun(intent.getStringExtra(EXTRA_MODE))
            else -> {
                // 进程被系统杀掉后重启，不自动重新运行脚本
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        RunnerController.requestStop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    private fun startRun(modeName: String?) {
        val mode = RunMode.fromName(modeName)
        val settings = SettingsStore.load()

        AppFiles.prepare()
        resetUiFiles()

        val argv = buildArgv(mode, settings.config)
        val params = JSONObject().apply {
            put("workdir", AppFiles.workDir.absolutePath)
            put("log", AppFiles.logFile.absolutePath)
            put("ui", AppFiles.uiDir.absolutePath)
            put("argv", JSONArray(argv))
        }

        stopped = false
        RunnerController.setRunning(true)
        RunnerController.setExitCode(null)
        RunnerController.setInputRequest(null)
        RunnerController.setStatus("运行中（${mode.label}）")
        RunnerController.bumpLog()

        startForegroundCompat(buildNotification("脚本正在后台运行…"))

        worker = Thread({
            val code = try {
                execPython(params.toString())
            } catch (e: Throwable) {
                e.printStackTrace()
                try {
                    AppFiles.logFile.appendText("\n[APP] 启动失败: ${e.message}\n")
                } catch (_: Throwable) {
                }
                -1
            }
            RunnerController.setExitCode(code)
        }, "python-runner").apply { start() }

        watcher = Thread({ watchLoop() }, "runner-watcher").apply { start() }
    }

    private fun buildArgv(mode: RunMode, config: AppConfig): List<String> {
        val argv = mutableListOf("main.py")
        if (mode == RunMode.CONFIG) {
            val ini = renderConfigIni(AppFiles.readTemplate(this), config)
            AppFiles.configFile.writeText(ini)
            argv += listOf("-c", AppFiles.configFile.absolutePath)
        } else {
            // 直接运行：等价 `python main.py`，把设置面板里的运行参数透传过去
            argv += listOf("-s", fmt(config.speed))
            argv += listOf("-j", config.jobs.toString())
            argv += listOf("-a", config.notopenAction)
            argv += listOf("--retry-interval", fmt(config.retryInterval))
            if (config.addLearningCount) {
                argv += "-lc"
                argv += listOf("-tc", config.targetCount.toString())
            }
        }
        return argv
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "1" }

    private fun execPython(paramsJson: String): Int {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val module = Python.getInstance().getModule("android_bootstrap")
        return module.callAttr("start", paramsJson).toInt()
    }

    private fun watchLoop() {
        var lastRequestId = -1
        while (!stopped) {
            try {
                // 1. 交互请求
                val reqFile = AppFiles.requestFile
                if (reqFile.exists()) {
                    val obj = JSONObject(reqFile.readText())
                    val id = obj.optInt("id", -1)
                    if (id != lastRequestId && id >= 0) {
                        lastRequestId = id
                        val courses = mutableListOf<CourseItem>()
                        val arr = obj.optJSONArray("courses")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val c = arr.optJSONObject(i) ?: continue
                                courses.add(
                                    CourseItem(
                                        id = c.optString("id"),
                                        clazzId = c.optString("clazzId"),
                                        title = c.optString("title"),
                                    )
                                )
                            }
                        }
                        RunnerController.setInputRequest(
                            InputRequest(
                                id = id,
                                kind = obj.optString("kind", "text"),
                                prompt = obj.optString("prompt", ""),
                                courses = courses,
                            )
                        )
                        updateNotification("等待输入…")
                    }
                } else if (RunnerController.inputRequest.value != null) {
                    RunnerController.setInputRequest(null)
                }

                // 2. 结束状态
                val statusFile = AppFiles.statusFile
                if (statusFile.exists()) {
                    val st = JSONObject(statusFile.readText())
                    if (st.optString("state") == "finished") {
                        val code = st.optInt("code", 0)
                        RunnerController.setStatus("已结束（退出码 $code）")
                        RunnerController.setRunning(false)
                        updateNotification("运行结束（退出码 $code）")
                        RunnerController.bumpLog()
                        stopForegroundCompat()
                        stopSelf()
                        return
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun resetUiFiles() {
        listOf(
            AppFiles.requestFile,
            AppFiles.responseFile,
            AppFiles.statusFile,
            AppFiles.stopFile,
        ).forEach {
            try {
                if (it.exists()) it.delete()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ //
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "脚本运行状态", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "显示刷课脚本的运行状态" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, RunnerService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle("超星脚本运行中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, "停止", stopIntent())
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
