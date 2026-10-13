package com.runpulse.app.stats

import com.runpulse.app.data.TrackPointEntity
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轨迹统计工具（纯计算，无 Android 依赖）。
 * 算法与服务端摘要逻辑保持一致：
 * - 精度过滤：accuracy > 25m 的点（室内/弱信号漂移点）不参与统计；
 * - 距离：haversine 大圆距离，两点间瞬时速度 > 30 m/s 视为漂移，跳过该段；
 * - 累计爬升：只统计相邻点之间的上升段。
 */
object TrackStats {

    /** 精度过滤阈值：水平精度超过 25m 的点不参与统计（原始点仍全量落盘/上传） */
    private const val MAX_ACCURACY_M = 25f

    /** 漂移过滤阈值：两点间移动速度超过 30 m/s（108 km/h）则丢弃该段（地图速度着色也复用此值） */
    const val DRIFT_SPEED_LIMIT_MS = 30.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 过滤掉低精度漂移点（accuracy 缺失的点保留）。统计与轨迹渲染统一走这里。 */
    fun filteredPoints(points: List<TrackPointEntity>): List<TrackPointEntity> =
        points.filter { it.accuracy <= MAX_ACCURACY_M }

    /** 两点 haversine 距离（米） */
    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** 轨迹总距离（km），精度过滤 + 漂移过滤 */
    fun distanceKm(points: List<TrackPointEntity>): Double {
        val pts = filteredPoints(points)
        if (pts.size < 2) return 0.0
        var meters = 0.0
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val curr = pts[i]
            val dtSec = (curr.timestamp - prev.timestamp) / 1000.0
            if (dtSec <= 0) continue
            val d = haversineM(prev.lat, prev.lng, curr.lat, curr.lng)
            if (d / dtSec > DRIFT_SPEED_LIMIT_MS) continue // 漂移段不计入
            meters += d
        }
        return meters / 1000.0
    }

    /** 累计爬升（米）：只累加相邻点之间的上升段 */
    fun elevationGainM(points: List<TrackPointEntity>): Double {
        val pts = filteredPoints(points)
        var gain = 0.0
        for (i in 1 until pts.size) {
            val delta = pts[i].altitude - pts[i - 1].altitude
            if (delta > 0) gain += delta
        }
        return gain
    }

    /** 平均配速（跑/越野），格式 6'05"/km；距离或时长不足时返回 "--" */
    fun avgPaceString(points: List<TrackPointEntity>): String {
        if (points.size < 2) return "--"
        val distKm = distanceKm(points)
        val durSec = (points.last().timestamp - points.first().timestamp) / 1000.0
        if (distKm < 0.01 || durSec < 1) return "--"
        val paceSecPerKm = durSec / distKm
        val min = (paceSecPerKm / 60).toInt()
        val sec = (paceSecPerKm % 60).toInt()
        return String.format("%d'%02d\"/km", min, sec)
    }

    /**
     * 平均配速（跑/越野），排除暂停时间。
     * @param totalPausedMs 累计暂停时长（毫秒），从 SessionEntity.totalPausedMs 获取
     */
    fun avgPaceString(points: List<TrackPointEntity>, totalPausedMs: Long): String {
        if (points.size < 2) return "--"
        val distKm = distanceKm(points)
        val wallDurMs = points.last().timestamp - points.first().timestamp
        val netDurSec = (wallDurMs - totalPausedMs).coerceAtLeast(0) / 1000.0
        if (distKm < 0.01 || netDurSec < 1) return "--"
        val paceSecPerKm = netDurSec / distKm
        val min = (paceSecPerKm / 60).toInt()
        val sec = (paceSecPerKm % 60).toInt()
        return String.format("%d'%02d\"/km", min, sec)
    }

    /** 平均速度（km/h），骑行用；时长不足返回 0 */
    fun avgSpeedKmh(points: List<TrackPointEntity>): Double {
        if (points.size < 2) return 0.0
        val durHours = (points.last().timestamp - points.first().timestamp) / 3_600_000.0
        if (durHours <= 0) return 0.0
        return distanceKm(points) / durHours
    }

    /**
     * 平均速度（km/h），骑行用，排除暂停时间。
     * @param totalPausedMs 累计暂停时长（毫秒），从 SessionEntity.totalPausedMs 获取
     */
    fun avgSpeedKmh(points: List<TrackPointEntity>, totalPausedMs: Long): Double {
        if (points.size < 2) return 0.0
        val wallDurMs = points.last().timestamp - points.first().timestamp
        val netDurHours = (wallDurMs - totalPausedMs).coerceAtLeast(0) / 3_600_000.0
        if (netDurHours <= 0) return 0.0
        return distanceKm(points) / netDurHours
    }

    /** 时长格式化 HH:MM:SS */
    fun formatDuration(startMs: Long, endMs: Long): String {
        val totalSec = ((endMs - startMs) / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    /**
     * 净运动时长格式化 HH:MM:SS，排除暂停时间。
     * @param totalPausedMs 累计暂停时长（毫秒），从 SessionEntity.totalPausedMs 获取
     */
    fun formatNetDuration(startMs: Long, endMs: Long, totalPausedMs: Long): String {
        val netMs = (endMs - startMs - totalPausedMs).coerceAtLeast(0)
        val totalSec = netMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    /**
     * 平均步频（SPM）：从轨迹点的 cadence 字段计算平均值。
     * 只统计 cadence > 0 的有效点。
     */
    fun avgCadence(points: List<TrackPointEntity>): Int {
        val validCadences = points.mapNotNull { it.cadence }.filter { it > 0 }
        if (validCadences.isEmpty()) return 0
        return validCadences.average().toInt()
    }

    /**
     * 平均步幅（米/步）：科学计算方式 = 总距离 / 总步数。
     * 总步数 = 平均步频(SPM) × 净运动时长(分钟)。
     * 无数据返回 0。
     */
    fun avgStrideLengthM(points: List<TrackPointEntity>, totalPausedMs: Long): Double {
        val cadence = avgCadence(points)
        if (cadence <= 0) return 0.0
        
        val distM = distanceKm(points) * 1000.0
        if (distM < 1) return 0.0
        
        val wallDurMs = points.last().timestamp - points.first().timestamp
        val netDurMin = (wallDurMs - totalPausedMs).coerceAtLeast(0) / 60_000.0
        if (netDurMin < 0.1) return 0.0
        
        val totalSteps = cadence * netDurMin
        return distM / totalSteps
    }
}
