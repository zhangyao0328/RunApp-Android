package com.runpulse.app.api

import com.google.gson.annotations.SerializedName

/**
 * 与 Go 云端定稿的 API 契约模型，字段名严格一致，勿随意改动。
 */

data class StartSessionRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("sport_type") val sportType: String,
    @SerializedName("start_time") val startTime: Long
)

data class PointDto(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("altitude") val altitude: Double,
    @SerializedName("speed") val speed: Float,
    @SerializedName("accuracy") val accuracy: Float,
    @SerializedName("heart_rate") val heartRate: Int?,
    @SerializedName("cadence") val cadence: Int?,
    @SerializedName("timestamp") val timestamp: Long
)

data class BatchPointsRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("points") val points: List<PointDto>
)

data class BatchPointsResponse(
    @SerializedName("received") val received: Int
)

data class EndSessionRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("end_time") val endTime: Long
)

data class CoachMessage(
    @SerializedName("id") val id: Long,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("text") val text: String
)

data class AnalysisResponse(
    @SerializedName("messages") val messages: List<CoachMessage>
)

/** 诊断事件：进程重启、Service 生命周期、定位断流等，用于分析杀进程/漂移问题 */
data class DiagEvent(
    @SerializedName("session_id") val sessionId: String?,
    @SerializedName("event") val event: String,
    @SerializedName("detail") val detail: String,
    @SerializedName("client_ts") val clientTs: Long
)

data class DiagEventsRequest(
    @SerializedName("events") val events: List<DiagEvent>
)

/** 轨迹文件导入响应 */
data class ImportTrackResponse(
    @SerializedName("sessionId") val sessionId: String?,
    @SerializedName("sportType") val sportType: String?,
    @SerializedName("pointCount") val pointCount: Int?
)
