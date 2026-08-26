package com.runpulse.app.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.runpulse.app.api.ApiClient
import com.runpulse.app.api.BatchPointsRequest
import com.runpulse.app.api.PointDto
import com.runpulse.app.coach.CoachSpeaker
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.CoachMessageEntity
import com.runpulse.app.diag.DiagLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 轨迹点攒批上传 Worker。
 *
 * 可靠性设计：
 * - 只按数据库中 uploaded = 0 的点工作，天然幂等，周期任务/一次性任务重复触发无副作用；
 * - 网络失败或非 2xx 时【不标记】uploaded，返回 Result.retry() 交给 WorkManager 退避重试，
 *   实现断网补传；
 * - 每批最多 [BATCH_SIZE] 个点，多批次多会话循环处理直到传完或失败；
 * - 上传成功后立即轮询一次 analysis 接口，新教练消息交给 [CoachSpeaker] 做 TTS 播报。
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "UploadWorker"
        private const val BATCH_SIZE = 100
        private const val PREFS_NAME = "coach_prefs"
        private const val KEY_LAST_ANALYSIS_ID_PREFIX = "last_analysis_id_"
    }

    private val dao = AppDatabase.get(applicationContext).trackingDao()
    private val api = ApiClient.api

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var allOk = uploadAllPendingPoints()

        // 有活跃会话时，轮询一次云端分析并播报新消息
        val active = dao.getActiveSession()
        if (active != null) {
            if (!pollAnalysisOnce(active.sessionId)) {
                // 分析接口失败不应阻塞轨迹上传主流程，只记日志
                Log.w(TAG, "analysis 轮询失败，session=${active.sessionId}")
            }
        }

        // Result.retry() 交给 WorkManager 指数退避；轨迹数据仍在本地，下次补传
        if (allOk) Result.success() else Result.retry()
    }

    /** 逐会话循环上传所有未上传点，全部成功才返回 true */
    private suspend fun uploadAllPendingPoints(): Boolean {
        var allOk = true
        val sessionIds = dao.getSessionIdsWithPendingPoints()
        for (sessionId in sessionIds) {
            while (true) {
                val points = dao.getPendingPoints(sessionId, BATCH_SIZE)
                if (points.isEmpty()) break
                val ok = uploadBatch(sessionId, points)
                if (!ok) {
                    allOk = false
                    break // 该会话失败，先传下一个会话，整体交给 retry
                }
            }
        }
        return allOk
    }

    /** 上传单批，成功则标记 uploaded；任何失败都不动数据库，等待重试 */
    private suspend fun uploadBatch(
        sessionId: String,
        points: List<com.runpulse.app.data.TrackPointEntity>
    ): Boolean {
        return try {
            val body = BatchPointsRequest(
                sessionId = sessionId,
                points = points.map {
                    PointDto(
                        lat = it.lat,
                        lng = it.lng,
                        altitude = it.altitude,
                        speed = it.speed,
                        accuracy = it.accuracy,
                        heartRate = it.heartRate,
                        timestamp = it.timestamp
                    )
                }
            )
            val response = api.uploadPoints(body)
            if (response.isSuccessful) {
                dao.markUploaded(points.map { it.id })
                DiagLogger.log(applicationContext, sessionId, "upload_ok", "count=${points.size}")
                Log.d(TAG, "上传成功：session=$sessionId, count=${points.size}, received=${response.body()?.received}")
                true
            } else {
                DiagLogger.log(applicationContext, sessionId, "upload_fail", "http=${response.code()} count=${points.size}")
                Log.w(TAG, "上传失败 HTTP ${response.code()}")
                false
            }
        } catch (e: Exception) {
            DiagLogger.log(applicationContext, sessionId, "upload_fail", "exception=${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "上传异常", e)
            false
        }
    }

    /** 轮询 analysis 接口，把 since_id 之后的新消息逐条 TTS 播报 */
    private suspend fun pollAnalysisOnce(sessionId: String): Boolean {
        return try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = KEY_LAST_ANALYSIS_ID_PREFIX + sessionId
            val sinceId = prefs.getLong(key, 0L)

            val response = api.getAnalysis(sessionId, sinceId)
            if (!response.isSuccessful) return false

            val messages = response.body()?.messages.orEmpty()
                .filter { it.id > sinceId }
                .sortedBy { it.id }

            if (messages.isNotEmpty()) {
                // 先落库：serverId 唯一索引去重，详情页可回看历史点评；再 TTS 播报
                dao.insertCoachMessages(
                    messages.map {
                        CoachMessageEntity(
                            serverId = it.id,
                            sessionId = sessionId,
                            timestamp = it.timestamp,
                            text = it.text
                        )
                    }
                )
                val speaker = CoachSpeaker.get(applicationContext)
                messages.forEach { speaker.speak(it.text) }
                prefs.edit().putLong(key, messages.last().id).apply()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "analysis 轮询异常", e)
            false
        }
    }
}
