package com.cxrunner.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.cxrunner.app.data.AppFiles
import com.cxrunner.app.data.SettingsStore

class RunnerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppFiles.init(this)
        SettingsStore.init(this)
        AppFiles.prepare()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
