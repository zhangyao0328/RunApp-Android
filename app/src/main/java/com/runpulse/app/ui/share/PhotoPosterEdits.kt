package com.runpulse.app.ui.share

/**
 * 照片海报编辑状态（poster 1080×1350 坐标系）。
 * 文本字段为 null 时表示使用默认计算值。
 */
data class PhotoPosterEdits(
    var trackVisible: Boolean = true,
    var trackCenterX: Float = SharePosterRenderer.TRACK_DEFAULT_CX,
    var trackCenterY: Float = SharePosterRenderer.TRACK_DEFAULT_CY,
    var distanceText: String? = null,
    var durationText: String? = null,
    var paceText: String? = null,
    var elevationText: String? = null,
)

/** 可编辑字段 */
enum class PhotoEditField {
    DISTANCE, DURATION, PACE, ELEVATION
}

/** 照片海报默认文本（TrackStats 计算值） */
data class PhotoPosterDefaults(
    val distance: String,
    val duration: String,
    val pace: String,
    val elevation: String,
    val paceLabel: String,
)
