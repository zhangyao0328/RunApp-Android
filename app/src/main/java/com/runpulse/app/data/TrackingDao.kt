package com.runpulse.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 轨迹采集 DAO：写入侧（插入点/建会话）与上传侧（查未上传点/标记已上传）分开标注。
 */
@Dao
interface TrackingDao {

    // ---------- 采集侧 ----------

    @Insert
    suspend fun insertPoint(point: TrackPointEntity): Long

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET endTime = :endTime, isActive = 0 WHERE sessionId = :sessionId")
    suspend fun endSession(sessionId: String, endTime: Long)

    @Query("UPDATE sessions SET isPaused = 1 WHERE sessionId = :sessionId")
    suspend fun setSessionPaused(sessionId: String)

    @Query("UPDATE sessions SET isPaused = 0, totalPausedMs = :totalPausedMs WHERE sessionId = :sessionId")
    suspend fun setSessionResumed(sessionId: String, totalPausedMs: Long)

    // ---------- 上传侧 ----------

    /** 查某次运动未上传的点，按时间升序，limit 控制单批大小 */
    @Query(
        "SELECT * FROM track_points WHERE sessionId = :sessionId AND uploaded = 0 " +
            "ORDER BY timestamp ASC LIMIT :limit"
    )
    suspend fun getPendingPoints(sessionId: String, limit: Int): List<TrackPointEntity>

    /** 上传成功后按主键批量标记 */
    @Query("UPDATE track_points SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    /** 所有还有未上传点的会话 id（可能跨多次运动，例如上次运动没传完） */
    @Query("SELECT DISTINCT sessionId FROM track_points WHERE uploaded = 0")
    suspend fun getSessionIdsWithPendingPoints(): List<String>

    /** 对账式同步用：全量会话（活跃+已结束），Worker 逐会话确保 start/end 在云端闭环 */
    @Query("SELECT * FROM sessions ORDER BY startTime ASC")
    suspend fun getAllSessions(): List<SessionEntity>

    // ---------- UI 观察侧 ----------

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    fun observeActiveSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    /** 当前活跃会话的全部轨迹点，UI 用它实时计算距离 */
    @Query(
        "SELECT * FROM track_points WHERE sessionId IN " +
            "(SELECT sessionId FROM sessions WHERE isActive = 1) ORDER BY timestamp ASC"
    )
    fun observeActiveSessionPoints(): Flow<List<TrackPointEntity>>

    /** 历史列表：已结束的会话，按开始时间倒序 */
    @Query("SELECT * FROM sessions WHERE isActive = 0 ORDER BY startTime DESC")
    fun observeFinishedSessions(): Flow<List<SessionEntity>>

    /** 详情/统计/调试导入用：按 sessionId 精确查会话 */
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    /** 某次运动的全量轨迹点，按时间升序（历史统计与详情页地图用） */
    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSessionPoints(sessionId: String): List<TrackPointEntity>

    // ---------- 教练点评 ----------

    /** serverId 唯一索引去重，重复插入直接忽略 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCoachMessages(messages: List<CoachMessageEntity>)

    @Query("SELECT * FROM coach_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeCoachMessages(sessionId: String): Flow<List<CoachMessageEntity>>
}
