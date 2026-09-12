package com.runpulse.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 轨迹点表。
 * 设计要点：
 * - 每个定位回调点【立即插入】本表，绝不在内存里攒批，进程被杀也只丢最后一个点；
 * - uploaded 标记是否已成功上传，供断网补传；
 * - (sessionId, uploaded) 联合索引加速“查某次运动未上传的点”这一高频查询。
 */
@Entity(
    tableName = "track_points",
    indices = [Index(value = ["sessionId", "uploaded"])]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val lat: Double,
    val lng: Double,
    val altitude: Double,
    /** m/s */
    val speed: Float,
    /** m */
    val accuracy: Float,
    /** 心率可空，1.0 暂不接入 BLE 心率带，固定为 null */
    val heartRate: Int? = null,
    /** 步频（SPM），仅跑步时通过加速度计检测，其他运动类型为 null */
    val cadence: Int? = null,
    /** 定位点系统时间（epoch 毫秒） */
    val timestamp: Long,
    val uploaded: Boolean = false
)
