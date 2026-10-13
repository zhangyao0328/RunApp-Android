package com.runpulse.app.upload

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.runpulse.app.api.ApiClient
import com.runpulse.app.api.BatchPointsRequest
import com.runpulse.app.api.EndSessionRequest
import com.runpulse.app.api.PointDto
import com.runpulse.app.api.StartSessionRequest
import com.runpulse.app.coach.CoachSpeaker
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.CoachMessageEntity
import com.runpulse.app.diag.DiagLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对账式同步 Worker：逐会话确保 本地数据 → 云端 闭环。
 *
 * 每个本地会话按顺序执行三步（全部依赖服务端幂等性，重复执行无副作用）：
 * 1. POST /sessions/start —— 幂等 upsert，确保云端有 session 行。
 *    修复 TrackingService 里 start 上报失败导致的"孤儿轨迹点"（云端有 point 无 session，
 *    管理后台永远看不到该记录）；
 * 2. 分批补传 uploaded = 0 的轨迹点（失败不标记，重试幂等）；
 * 3. 本地已结束的会话 POST /sessions/end —— 覆盖写，天然幂等。
 *    修复 end 上报失败导致的云端"永远进行中"（AI 实时分析不停、后台状态错误）。
 *
 * start/end 的确认进度记在 sync_prefs 的字符串集合里（无需 Room migration）；
 * 已闭环（end 确认）的会话之后整体跳过，周期任务的开销不随历史数据增长。
 * GPX 导入的调试会话（sessionId 以 "debug-" 前缀）刻意不上云，见 GpxImporter。
 *
 * 任一环节失败即返回 Result.retry() 交给 WorkManager 退避重试，数据仍在本地。
 * 收尾照旧轮询一次 analysis 接口，新教练消息交给 [CoachSpeaker] 做 TTS 播报。
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

        // 会话同步进度：start/end 已获云端 2xx 确认的 sessionId 集合
        private const val SYNC_PREFS_NAME = "sync_prefs"
        private const val KEY_STARTED_SESSIONS = "started_sessions"
        private const val KEY_ENDED_SESSIONS = "ended_sessions"

        /** GpxImporter 生成的调试会话前缀，刻意不上云 */
        private const val DEBUG_SESSION_PREFIX = "debug-"
    }

    private val dao = AppDatabase.get(applicationContext).trackingDao()
    private val api = ApiClient.api

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncPrefs = applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        val allOk = syncAllSessions(syncPrefs)

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

    /**
     * 对账式同步主流程：遍历本地全量会话，逐个确保 start → points → end 云端闭环。
     * 返回 true 表示本次全部环节成功。
     */
    private suspend fun syncAllSessions(syncPrefs: SharedPreferences): Boolean {
        var allOk = true
        val started = syncPrefs.getStringSet(KEY_STARTED_SESSIONS, emptySet())!!.toMutableSet()
        val ended = syncPrefs.getStringSet(KEY_ENDED_SESSIONS, emptySet())!!.toMutableSet()
        var dirty = false

        for (session in dao.getAllSessions()) {
            val id = session.sessionId
            if (id.startsWith(DEBUG_SESSION_PREFIX)) continue
            // 已闭环的会话整体跳过：周期任务开销不随历史数据增长
            if (ended.contains(id)) continue

            // 1. 幂等 upsert，确保云端有 session 行
            if (!started.contains(id)) {
                val ok = runCatching {
                    api.startSession(
                        StartSessionRequest(id, session.sportType, session.startTime)
                    ).isSuccessful
                }.getOrElse { e ->
                    Log.w(TAG, "同步会话开始失败：session=$id", e)
                    false
                }
                if (!ok) {
                    DiagLogger.log(applicationContext, id, "sync_start_fail", "")
                    allOk = false
                    continue // 本会话留待下次重试，继续处理其他会话
                }
                started.add(id)
                dirty = true
            }

            // 2. 补传该会话全部未上传轨迹点（传完才允许走 end）
            if (!uploadPendingPoints(id)) {
                allOk = false
                continue
            }

            // 3. 本地已结束的会话确保云端 end 已上报
            if (!session.isActive && !ended.contains(id)) {
                val endTime = session.endTime ?: continue // 异常数据：已结束却无 endTime，跳过
                val ok = runCatching {
                    api.endSession(EndSessionRequest(id, endTime)).isSuccessful
                }.getOrElse { e ->
                    Log.w(TAG, "同步会话结束失败：session=$id", e)
                    false
                }
                if (!ok) {
                    DiagLogger.log(applicationContext, id, "sync_end_fail", "")
                    allOk = false
                    continue
                }
                ended.add(id)
                // end 确认意味着 start 早已闭环，从 started 精简掉
                started.remove(id)
                dirty = true
            }
        }

        if (dirty) {
            syncPrefs.edit()
                .putStringSet(KEY_STARTED_SESSIONS, started)
                .putStringSet(KEY_ENDED_SESSIONS, ended)
                .apply()
        }
        return allOk
    }

    /** 把某会话的未上传点按批全部传完，全部成功才返回 true */
    private suspend fun uploadPendingPoints(sessionId: String): Boolean {
        while (true) {
            val points = dao.getPendingPoints(sessionId, BATCH_SIZE)
            if (points.isEmpty()) return true
            if (!uploadBatch(sessionId, points)) return false
        }
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
                        cadence = it.cadence,
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
