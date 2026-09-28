package com.cxrunner.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 脚本弹出的交互请求 */
data class CourseItem(
    val id: String,
    val clazzId: String,
    val title: String,
)

data class InputRequest(
    val id: Int,
    val kind: String, // text / password / courses / confirm
    val prompt: String,
    val courses: List<CourseItem> = emptyList(),
)

/** 运行状态（进程内单例，UI 与前台服务共享） */
object RunnerController {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _statusText = MutableStateFlow("空闲")
    val statusText: StateFlow<String> = _statusText

    private val _inputRequest = MutableStateFlow<InputRequest?>(null)
    val inputRequest: StateFlow<InputRequest?> = _inputRequest

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode

    private val _logTick = MutableStateFlow(0)
    val logTick: StateFlow<Int> = _logTick

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun setStatus(text: String) {
        _statusText.value = text
    }

    fun setInputRequest(request: InputRequest?) {
        _inputRequest.value = request
    }

    fun setExitCode(code: Int?) {
        _exitCode.value = code
    }

    fun bumpLog() {
        _logTick.value = _logTick.value + 1
    }

    /** 回复脚本的输入请求；value 为空表示取消 */
    fun respond(requestId: Int, value: String?) {
        try {
            val obj = org.json.JSONObject()
            obj.put("id", requestId)
            if (value == null) obj.put("value", org.json.JSONObject.NULL)
            else obj.put("value", value)
            val tmp = java.io.File(AppFiles.uiDir, "response.json.tmp")
            tmp.writeText(obj.toString())
            val target = AppFiles.responseFile
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _inputRequest.value = null
    }

    /** 请求停止脚本 */
    fun requestStop() {
        try {
            AppFiles.stopFile.createNewFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
