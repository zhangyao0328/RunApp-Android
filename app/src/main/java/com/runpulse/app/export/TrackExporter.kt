package com.runpulse.app.export

import android.content.Context
import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.stats.TrackStats
import com.runpulse.app.tracking.SportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/**
 * 轨迹文件导出器：把某次运动的全部轨迹点编码为 GPX / FIT 文件，写 cacheDir/exports，
 * 由详情页经 FileProvider 分享出去。
 *
 * - GPX 1.1：纯手写 XML，心率/步频写在 gpxtpx:TrackPointExtension 扩展里；
 * - FIT：Garmin 官方 FIT Java SDK（com.garmin:fit），编码 FileId/Session/Lap/Record/Activity 消息。
 *
 * 所有函数在 Dispatchers.IO 上执行（Room 查询 + 文件写入）。
 */
object TrackExporter {

    private data class SessionData(
        val session: SessionEntity?,
        val points: List<TrackPointEntity>
    )

    /** 导出 GPX 1.1，文件名 runpulse-<sessionId前8位>.gpx */
    suspend fun exportGpx(context: Context, sessionId: String): File =
        withContext(Dispatchers.IO) {
            val (session, points) = loadSession(context, sessionId)
            val file = outFile(context, sessionId, "gpx")
            file.bufferedWriter().use { writeGpx(it, session, points) }
            file
        }

    /** 导出 FIT 活动文件，文件名 runpulse-<sessionId前8位>.fit */
    suspend fun exportFit(context: Context, sessionId: String): File =
        withContext(Dispatchers.IO) {
            val (session, points) = loadSession(context, sessionId)
            val file = outFile(context, sessionId, "fit")
            val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
            try {
                writeFit(encoder, session, points)
            } finally {
                encoder.close()
            }
            file
        }

    // ---------- 数据加载 ----------

    private suspend fun loadSession(context: Context, sessionId: String): SessionData {
        val dao = AppDatabase.get(context).trackingDao()
        val session = dao.getSessionById(sessionId)
        // DAO 已有按 sessionId 查全量点的查询（详情页统计/地图同一条），无需新增
        val points = dao.getSessionPoints(sessionId)
        require(points.isNotEmpty()) { "本次运动没有可导出的轨迹点" }
        return SessionData(session, points)
    }

    private fun outFile(context: Context, sessionId: String, ext: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "runpulse-${sessionId.take(8)}.$ext")
    }

    private fun isoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    // ---------- GPX ----------

    private fun writeGpx(
        w: java.io.Writer,
        session: SessionEntity?,
        points: List<TrackPointEntity>
    ) {
        val startIso = isoUtc(points.first().timestamp)
        w.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        w.appendLine(
            """<gpx version="1.1" creator="RunPulse" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">"""
        )
        w.appendLine("  <metadata><time>$startIso</time></metadata>")
        w.appendLine("  <trk>")
        val sport = SportType.fromValue(session?.sportType)
        w.appendLine("    <name>RunPulse ${sport.value} ${startIso.substring(0, 10)}</name>")
        w.appendLine("    <trkseg>")
        for (p in points) {
            w.appendLine("      <trkpt lat=\"${p.lat}\" lon=\"${p.lng}\">")
            w.appendLine("        <ele>${p.altitude}</ele>")
            w.appendLine("        <time>${isoUtc(p.timestamp)}</time>")
            if (p.heartRate != null || p.cadence != null) {
                w.appendLine("        <extensions>")
                w.appendLine("          <gpxtpx:TrackPointExtension>")
                p.heartRate?.let { w.appendLine("            <gpxtpx:hr>$it</gpxtpx:hr>") }
                p.cadence?.let { w.appendLine("            <gpxtpx:cad>$it</gpxtpx:cad>") }
                w.appendLine("          </gpxtpx:TrackPointExtension>")
                w.appendLine("        </extensions>")
            }
            w.appendLine("      </trkpt>")
        }
        w.appendLine("    </trkseg>")
        w.appendLine("  </trk>")
        w.appendLine("</gpx>")
    }

    // ---------- FIT ----------

    // 注意：DateTime(long) 构造参数语义非秒级 epoch，必须用 Instant 往返（Garmin SDK 21.x 实测）
    private fun fitTime(epochMs: Long): DateTime =
        DateTime.from(java.time.Instant.ofEpochMilli(epochMs))

    /** 度 → semicircles（度 × 2^31 / 180） */
    private fun semicircles(deg: Double): Int =
        (deg / 180.0 * (1L shl 31)).roundToInt()

    private fun writeFit(
        encoder: FileEncoder,
        session: SessionEntity?,
        points: List<TrackPointEntity>
    ) {
        val startMs = session?.startTime ?: points.first().timestamp
        val endMs = session?.endTime ?: points.last().timestamp
        val elapsedSec = ((endMs - startMs) / 1000f).coerceAtLeast(0f)
        val totalDistanceM = (TrackStats.distanceKm(points) * 1000f).toFloat()
        val fitSport =
            if (SportType.fromValue(session?.sportType) == SportType.BIKE) Sport.CYCLING
            else Sport.RUNNING

        val fileIdMesg = FileIdMesg().apply {
            type = com.garmin.fit.File.ACTIVITY
            manufacturer = Manufacturer.DEVELOPMENT
            timeCreated = fitTime(startMs)
        }
        encoder.write(fileIdMesg)

        val sessionMesg = SessionMesg().apply {
            timestamp = fitTime(endMs)
            startTime = fitTime(startMs)
            totalElapsedTime = elapsedSec
            totalDistance = totalDistanceM
            sport = fitSport
        }
        encoder.write(sessionMesg)

        // 整段运动编码为 1 圈
        val lapMesg = LapMesg().apply {
            timestamp = fitTime(endMs)
            startTime = fitTime(startMs)
            totalElapsedTime = elapsedSec
            totalDistance = totalDistanceM
            sport = fitSport
        }
        encoder.write(lapMesg)

        for (p in points) {
            val recordMesg = RecordMesg().apply {
                timestamp = fitTime(p.timestamp)
                positionLat = semicircles(p.lat)
                positionLong = semicircles(p.lng)
                altitude = p.altitude.toFloat()
                speed = p.speed // 实体落库即为 m/s，无需换算
                p.heartRate?.let { heartRate = it.toShort() }
                p.cadence?.let { cadence = it.toShort() }
            }
            encoder.write(recordMesg)
        }

        val activityMesg = ActivityMesg().apply {
            timestamp = fitTime(endMs)
            numSessions = 1
            type = Activity.MANUAL
        }
        encoder.write(activityMesg)
    }
}
