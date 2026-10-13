package com.runpulse.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 运动会话表。sessionId 为客户端生成的 UUID 字符串主键（幂等，天然支持断网后离线开始）。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    /** run | trail | bike，与云端契约一致 */
    val sportType: String,
    val startTime: Long,
    val endTime: Long? = null,
    val isActive: Boolean = true,
    /** 暂停功能：当前是否处于暂停中（纯本地状态，云端无概念） */
    val isPaused: Boolean = false,
    /** 已累计的暂停时长（毫秒），用于净运动时长 = end-start-totalPausedMs */
    val totalPausedMs: Long = 0
)
