package com.runpulse.app.debug

import android.content.Context
import android.util.Log
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.data.TrackingDao
import com.runpulse.app.tracking.SportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant

/**
 * 调试用 GPX 导入器（仅 DEBUG 构建入口可见）。
 *
 * - 解析 assets 下的 GPX（trkpt 的 lat/lon/ele/time，ISO8601 转 epoch 毫秒）；
 * - 每个文件生成一个固定 sessionId 的已结束会话 + 全量轨迹点；
 * - uploaded=1：测试数据不参与上传，避免污染云端；
 * - 幂等：导入前按固定 sessionId 查 sessions 表，已存在则跳过。
 */
object GpxImporter {

    private const val TAG = "GpxImporter"

    private val GPX_FILES = listOf(
        "suuntoapp-Running-2026-08-03T12-10-22Z-track.gpx",
        "suuntoapp-Cycling-2026-08-18T23-17-24Z-track.gpx"
    )

    private data class GpxPoint(val lat: Double, val lng: Double, val ele: Double, val timeMs: Long)

    /** 返回导入结果文案（Toast 展示用） */
    suspend fun importAssets(context: Context, dao: TrackingDao): String =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<String>()
            for (file in GPX_FILES) {
                val sportType = when {
                    file.contains("Running") -> SportType.RUN
                    file.contains("Cycling") -> SportType.BIKE
                    else -> SportType.RUN
                }
                val sessionId = "debug-${sportType.value}-gpx"

                // 幂等检查：固定 sessionId 已存在则跳过
                if (dao.getSessionById(sessionId) != null) {
                    results.add("${sportType.value} 已导入过，跳过")
                    continue
                }

                val points = try {
                    context.assets.open(file).use { parseGpx(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "解析 $file 失败", e)
                    results.add("$file 解析失败")
                    continue
                }
                if (points.isEmpty()) {
                    results.add("$file 无轨迹点")
                    continue
                }

                dao.insertSession(
                    SessionEntity(
                        sessionId = sessionId,
                        sportType = sportType.value,
                        startTime = points.first().timeMs,
                        endTime = points.last().timeMs,
                        isActive = false
                    )
                )
                for (p in points) {
                    dao.insertPoint(
                        TrackPointEntity(
                            sessionId = sessionId,
                            lat = p.lat,
                            lng = p.lng,
                            altitude = p.ele,
                            speed = 0f,
                            accuracy = 0f,
                            heartRate = null,
                            timestamp = p.timeMs,
                            uploaded = true // 测试数据不上传
                        )
                    )
                }
                results.add("${sportType.value} 导入 ${points.size} 点")
            }
            results.joinToString("；")
        }

    /** 解析 GPX 的 trkseg/trkpt 节点 */
    private fun parseGpx(input: InputStream): List<GpxPoint> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)

        val points = mutableListOf<GpxPoint>()
        var inTrkpt = false
        var lat = 0.0
        var lng = 0.0
        var ele = 0.0
        var timeMs: Long? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt" -> {
                        inTrkpt = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lng = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        ele = 0.0
                        timeMs = null
                    }
                    "ele" -> if (inTrkpt) {
                        ele = parser.nextText().trim().toDoubleOrNull() ?: 0.0
                    }
                    "time" -> if (inTrkpt) {
                        timeMs = runCatching {
                            Instant.parse(parser.nextText().trim()).toEpochMilli()
                        }.getOrNull()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "trkpt" && inTrkpt) {
                    timeMs?.let { points.add(GpxPoint(lat, lng, ele, it)) }
                    inTrkpt = false
                }
            }
            event = parser.next()
        }
        return points
    }
}
