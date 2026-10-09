package com.cxrunner.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.SettingsStore
import com.cxrunner.app.service.ScheduleScheduler

class RunnerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppFiles.init(this)
        SettingsStore.init(this)
        AppFiles.prepare()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        // 冷启动时补登记定时启动闹钟：
        // Android 会杀掉进程但不一定会发 BOOT_COMPLETED，被用户手动划掉后
        // 闹钟也可能丢失，这里以进程启动作为一个兜底重排点。
        ScheduleScheduler.sync(this)
    }
}
