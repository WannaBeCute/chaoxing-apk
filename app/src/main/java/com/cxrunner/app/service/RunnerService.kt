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
import com.cxrunner.app.data.ProgressStore
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

        /** 启动来源（手动 / 定时启动），仅用于通知文案 */
        const val EXTRA_SOURCE = "source"
        private const val CHANNEL_ID = "runner_channel"
        private const val NOTIF_ID = 20240921

        /** 通知刷新间隔：视频进度每 0.4s 落一次盘，通知每秒刷一次足够，别把通知栏刷爆 */
        private const val NOTICE_REFRESH_MS = 1000L

        /**
         * 停止无响应多久后强制结束进程。
         *
         * 正常停止链路：看门狗 0.5s 内投递中断 → worker 收尾（≤5s）→ status.json
         * 写成 finished → 这边立刻感知，全程 7s 内。超过 15s 还没结束，说明 Python
         * 解释器已经卡死（真机见过：锁内 I/O 停滞、网络栈挂死），唯一的出路就是
         * 结束整个进程——留着只会永远卡在「正在停止」。
         */
        private const val FORCE_STOP_AFTER_MS = 15_000L
    }

    private var worker: Thread? = null
    private var watcher: Thread? = null
    private var stopped: Boolean = false

    /** 本次运行来源（"" = 手动，"定时启动" = 闹钟拉起） */
    private var runSource: String = ""

    /** 没有实时进度时的兜底文案（等待输入 / 定时启动应答等） */
    private var noticeText: String = "脚本正在后台运行…"

    /** 上次刷新通知的时间戳，用于节流 */
    private var lastNoticeRefresh: Long = 0L

    /** 用户点了「停止」，此时通知固定显示「正在停止…」，不再跟视频进度 */
    private var stopping: Boolean = false

    /** 点停止的时间戳；超过 [FORCE_STOP_AFTER_MS] 仍未结束就强制结束进程 */
    private var stopRequestedAt: Long = 0L

    /** 脚本已全部结束，通知固定在「已完成」文案上 */
    private var finishedNotice: Boolean = false

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
                // 立即把通知切到「正在停止」，否则视频进度还会继续刷，
                // 用户点了停止却看不到任何反馈。
                stopping = true
                stopRequestedAt = System.currentTimeMillis()
                noticeText = "正在停止…"
                updateNotification("正在停止…")
            }
            ACTION_START -> startRun(
                intent.getStringExtra(EXTRA_MODE),
                intent.getStringExtra(EXTRA_SOURCE),
            )
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
    private fun startRun(modeName: String?, source: String?) {
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
        stopping = false
        stopRequestedAt = 0L
        finishedNotice = false
        runSource = source ?: ""
        RunnerController.setRunning(true)
        RunnerController.setExitCode(null)
        RunnerController.setInputRequest(null)
        RunnerController.setRunSource(source ?: "")
        val sourceText = source?.let { "（$it）" } ?: ""
        RunnerController.setStatus("运行中（${mode.label}$sourceText）")
        RunnerController.bumpLog()

        noticeText = "脚本正在后台运行…"
        startForegroundCompat(buildNotification(noticeText))

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
                        val kind = obj.optString("kind", "text")

                        // 定时启动时没人守弹窗：课程列表为空触发的「选择要刷的课程」
                        // 必须在这里直接应答（留空 = 全部课程），否则脚本会一直卡在
                        // input() 上、后台挂着不动。放在服务里而不是界面里，
                        // 是为了 APP 不在前台时也能应答。
                        if (runSource == ScheduleScheduler.SOURCE_LABEL && kind == "courses") {
                            RunnerController.respond(id, "")
                            noticeText = "定时启动：默认刷全部课程"
                            RunnerController.bumpLog()
                        } else {
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
                                    kind = kind,
                                    prompt = obj.optString("prompt", ""),
                                    courses = courses,
                                )
                            )
                            noticeText = "等待输入…"
                        }
                    }
                } else if (RunnerController.inputRequest.value != null) {
                    RunnerController.setInputRequest(null)
                    noticeText = "脚本正在后台运行…"
                }

                // 2. 实时刷新通知里的视频进度（每秒最多一次，避免 notify 过频）
                val nowTick = System.currentTimeMillis()
                if (nowTick - lastNoticeRefresh >= NOTICE_REFRESH_MS) {
                    lastNoticeRefresh = nowTick
                    refreshRunNotification()
                }

                // 2.5 停止无响应兜底：Python 解释器卡死时（锁内 I/O 停滞、网络栈
                // 挂死都见过），stop.flag 永远没人消费，status.json 永远不会写成
                // finished，APP 只能永远停在「正在停止」。等够宽限时间后直接结束
                // 进程——这是唯一可靠的出路，界面上也会留下说明。
                if (stopping && stopRequestedAt > 0L &&
                    nowTick - stopRequestedAt >= FORCE_STOP_AFTER_MS
                ) {
                    try {
                        AppFiles.logFile.appendText(
                            "\n[APP] 脚本在 ${FORCE_STOP_AFTER_MS / 1000} 秒内没有响应停止指令，已强制结束进程\n" +
                            "[APP] 这通常意味着脚本内部卡死（可能是网络请求无响应或存储 I/O 停滞），\n" +
                            "[APP] 请把日志末尾的内容反馈给开发者。\n"
                        )
                    } catch (_: Exception) {
                    }
                    RunnerController.setStatus("已强制结束（脚本无响应）")
                    RunnerController.setRunning(false)
                    stopForegroundCompat()
                    stopSelf()
                    Runtime.getRuntime().exit(0)
                }

                // 3. 结束状态
                val statusFile = AppFiles.statusFile
                if (statusFile.exists()) {
                    val st = JSONObject(statusFile.readText())
                    if (st.optString("state") == "finished") {
                        val code = st.optInt("code", 0)
                        finishedNotice = true
                        RunnerController.setStatus("已结束（退出码 $code）")
                        RunnerController.setRunning(false)
                        // 全部任务跑完（退出码 0）时让通知停留 5 秒显示「已完成」，
                        // 否则 stopForeground 会把通知直接抹掉，用户根本看不到结果。
                        if (code == 0) {
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIF_ID,
                                buildNotification("所有课程任务已处理完毕", "超星脚本已完成"),
                            )
                            try {
                                Thread.sleep(5000)
                            } catch (_: InterruptedException) {
                            }
                        } else {
                            updateNotification("运行结束（退出码 $code）")
                        }
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
            AppFiles.progressFile,
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

    private fun buildNotification(
        text: String,
        title: String = "超星脚本运行中",
        progressPercent: Int? = null,
        percentLabel: String? = null,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent())

        // 有视频在播时挂一条确定进度的进度条，通知面板里能直接看到百分比。
        // 注意：100% 但服务端还没确认时也保持 determinate=100，不要突然消失。
        if (progressPercent != null) {
            val pct = progressPercent.coerceIn(0, 100)
            builder.setProgress(100, pct, false)
            builder.setSubText(percentLabel ?: "$pct%")
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    /**
     * 刷新通知里的运行状态。
     *
     * 优先级：
     *   1. 有视频任务在跑 → 显示**百分比最高**的那个任务的进度（多个任务里最靠前的那个，
     *      通常就是最接近完成、用户最关心的）+ 同行标注并发进程数；
     *   2. 没有视频进度 → 显示 [noticeText]（等待输入 / 定时启动应答等）；
     *   3. 本轮任务全部完成 → 由 watchLoop 单独把标题换成「全部任务已完成」。
     */
    private fun refreshRunNotification(title: String = "超星脚本运行中") {
        // 终态/停止中不要再拿旧的视频进度覆盖文案
        if (finishedNotice || stopping) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(if (stopping) "正在停止…" else noticeText))
            return
        }
        val items = ProgressStore.distinctByName(ProgressStore.read())
        val nm = getSystemService(NotificationManager::class.java)

        if (items.isNotEmpty()) {
            // ProgressStore 已按百分比降序排好，first() 就是进度最大的那个
            val lead = items.first()
            val count = items.size
            val detail = buildString {
                append(lead.name)
                // 提交中只显示时长会让人以为还在播（22:05/22:05 看起来像卡住），
                // 换成「提交ing...」后这里只留文件名
                if (!lead.submitting) {
                    append("  ")
                    append(lead.current)
                    if (lead.total.isNotBlank()) {
                        append('/')
                        append(lead.total)
                    }
                }
                if (count > 1) {
                    append("   共 ")
                    append(count)
                    append(" 个进程")
                }
            }
            nm.notify(NOTIF_ID, buildNotification(detail, title, lead.percent, lead.percentLabel))
            return
        }

        nm.notify(NOTIF_ID, buildNotification(noticeText, title))
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
