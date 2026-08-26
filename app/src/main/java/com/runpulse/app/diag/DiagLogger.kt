package com.runpulse.app.diag

import android.content.Context
import android.util.Log
import com.runpulse.app.api.ApiClient
import com.runpulse.app.api.DiagEvent
import com.runpulse.app.api.DiagEventsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志器：每条事件同时落两个地方——
 * 1. 本地文件 `/sdcard/Android/data/com.runpulse.app/files/logs/runpulse-diag.log`
 *    （无需权限，骑完 adb pull 即可拿回分析，是进程被杀场景下的 ground truth）；
 * 2. 云端 POST /api/v1/events（best-effort，失败只影响云端不缺本地）。
 *
 * 用途：分析"运动中被系统杀进程"（看 app_process_start / session_resume 出现次数）
 * 和"坐标漂移/定位断流"（看 location_gap 事件）。
 */
object DiagLogger {

    private const val TAG = "DiagLogger"
    private const val MAX_LOG_BYTES = 2 * 1024 * 1024L // 超 2MB 截断重写，防无限增长

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        dir.mkdirs()
        logFile = File(dir, "runpulse-diag.log")
    }

    /**
     * 记录一条诊断事件。
     * @param sessionId 关联的运动会话，无则传 null
     * @param event     事件名，如 session_start / location_gap / upload_fail
     * @param detail    键值对形式的补充信息，如 "gapMs=23000 pid=12345"
     */
    fun log(context: Context, sessionId: String?, event: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val line = "${tsFormat.format(Date(now))} | ${sessionId ?: "-"} | $event | $detail"
        writeFile(line)
        Log.d(TAG, line)
        // 云端 best-effort：进程将死时这条可能发不出去，本地文件兜底
        scope.launch {
            try {
                ApiClient.api.postEvents(
                    DiagEventsRequest(listOf(DiagEvent(sessionId, event, detail, now)))
                )
            } catch (e: Exception) {
                Log.w(TAG, "事件上报云端失败：$event", e)
            }
        }
    }

    @Synchronized
    private fun writeFile(line: String) {
        val file = logFile ?: return
        try {
            if (file.length() > MAX_LOG_BYTES) {
                file.writeText("") // 简单截断，1.0 不搞滚动分片
            }
            file.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "写诊断日志文件失败", e)
        }
    }
}
