package com.runpulse.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 教练点评表。
 * UploadWorker 轮询 /analysis 拿到新消息后先落库再 TTS，供详情页回看历史点评。
 * serverId 为服务端消息 id，加唯一索引 + INSERT IGNORE 实现幂等去重（重复轮询不会产生重复行）。
 */
@Entity(
    tableName = "coach_messages",
    indices = [Index(value = ["serverId"], unique = true)]
)
data class CoachMessageEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: Long,
    val sessionId: String,
    val timestamp: Long,
    val text: String
)
