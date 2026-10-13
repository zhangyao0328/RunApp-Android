package com.runpulse.app.importer

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.Sport
import com.runpulse.app.tracking.SportType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant

/**
 * 轨迹文件导入解析器：把 GPX / FIT 文件解析为统一的 ParsedTrack。
 *
 * - GPX：XmlPullParser 解析 trkpt（lat/lon/ele/time），可选解析 gpxtpx:hr 心率扩展
 *   （namespace 无关，匹配局部名 "hr"）；sportType 固定 run；
 * - FIT：Garmin SDK 解码 RecordMesg 收点 + SessionMesg 判断运动类型；
 *   经纬度 semicircles → 度（值 × 180 / 2³¹）；FIT epoch offset = 631065600 秒。
 */
object TrackImporter {

    private const val SEMICIRCLE_SCALE = 180.0 / (1L shl 31)
    private const val FIT_EPOCH_OFFSET_MS = 631065600000L

    data class ParsedPoint(
        val lat: Double,
        val lng: Double,
        val altitude: Double?,
        val speed: Float?,
        val heartRate: Int?,
        val timestamp: Long
    )

    data class ParsedTrack(
        val sportType: String,
        val points: List<ParsedPoint>
    )

    /** 解析 GPX 的 trkpt 节点，sportType 固定 run */
    fun parseGpx(input: InputStream): ParsedTrack {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)

        val points = mutableListOf<ParsedPoint>()
        var inTrkpt = false
        var lat = 0.0
        var lng = 0.0
        var ele: Double? = null
        var hr: Int? = null
        var timeMs: Long? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt" -> {
                        inTrkpt = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lng = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        ele = null
                        hr = null
                        timeMs = null
                    }
                    "ele" -> if (inTrkpt) {
                        ele = parser.nextText().trim().toDoubleOrNull()
                    }
                    "hr" -> if (inTrkpt) {
                        hr = parser.nextText().trim().toIntOrNull()
                    }
                    "time" -> if (inTrkpt) {
                        timeMs = runCatching {
                            Instant.parse(parser.nextText().trim()).toEpochMilli()
                        }.getOrNull()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "trkpt" && inTrkpt) {
                    timeMs?.let { points.add(ParsedPoint(lat, lng, ele, null, hr, it)) }
                    inTrkpt = false
                }
            }
            event = parser.next()
        }
        if (points.isEmpty()) throw IllegalArgumentException("文件中没有轨迹点")
        return ParsedTrack(SportType.RUN.value, points)
    }

    /** 解析 Garmin FIT：RecordMesg 收轨迹点，SessionMesg 判断运动类型 */
    fun parseFit(input: InputStream): ParsedTrack {
        val points = mutableListOf<ParsedPoint>()
        var sport: Sport? = null

        val decode = Decode()
        val broadcaster = MesgBroadcaster(decode)
        broadcaster.addListener(RecordMesgListener { mesg ->
            collectPoint(mesg)?.let { points.add(it) }
        })
        broadcaster.addListener(SessionMesgListener { mesg ->
            sport = mesg.sport
        })
        decode.read(input, broadcaster)

        if (points.isEmpty()) throw IllegalArgumentException("文件中没有轨迹点")
        return ParsedTrack(mapSport(sport), points)
    }

    private fun collectPoint(mesg: RecordMesg): ParsedPoint? {
        val latRaw = mesg.positionLat ?: return null
        val lngRaw = mesg.positionLong ?: return null
        if (latRaw == 0x7FFFFFFF || lngRaw == 0x7FFFFFFF) return null // FIT invalid value

        val timestamp = mesg.timestamp?.timestamp?.let { it * 1000L + FIT_EPOCH_OFFSET_MS }
            ?: return null
        return ParsedPoint(
            lat = latRaw * SEMICIRCLE_SCALE,
            lng = lngRaw * SEMICIRCLE_SCALE,
            altitude = mesg.altitude?.toDouble(),
            speed = mesg.speed,
            heartRate = mesg.heartRate?.toInt(),
            timestamp = timestamp
        )
    }

    private fun mapSport(sport: Sport?): String = when (sport) {
        Sport.CYCLING -> SportType.BIKE.value
        Sport.HIKING, Sport.WALKING -> SportType.TRAIL.value
        else -> SportType.RUN.value
    }
}
