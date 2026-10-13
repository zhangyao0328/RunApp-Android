package com.runpulse.app.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import com.runpulse.app.R
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.stats.TrackStats
import kotlin.math.cos
import kotlin.math.min

/**
 * 默认轨迹绘制实现：纯 Canvas 自绘，零第三方依赖，作为无 Mapbox token 时的兜底。
 *
 * 绘制规则：
 * - 轨迹按经纬度边界等比缩放居中，四周留 padding；经度方向乘 cos(中位纬度) 修正，避免高纬度横向拉伸；
 * - 线宽 6dp 圆头，线段颜色按相邻点瞬时速度渐变：快=绿 #4CAF50，慢=红 #F44336，中间线性插值；
 * - 起点绿色实心圆标注「起」，终点红色实心圆标注「终」；
 * - 不足 2 个点时显示「暂无轨迹」；背景透明。
 */
class CanvasTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TrackMapView(context, attrs, defStyleAttr) {

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
    }

    private val colorFast = androidx.core.content.ContextCompat.getColor(context, R.color.track_fast)
    private val colorSlow = androidx.core.content.ContextCompat.getColor(context, R.color.track_slow)

    private var points: List<TrackPointEntity> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(6f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(context, R.color.text_muted)
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }

    override fun setTrack(points: List<TrackPointEntity>) {
        // 统一走 TrackStats 精度过滤，低精度漂移点不参与渲染
        this.points = TrackStats.filteredPoints(points)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas) // 背景透明，不填充任何底色
        if (points.size < 2) {
            canvas.drawText(
                "暂无轨迹",
                width / 2f,
                height / 2f - (emptyTextPaint.descent() + emptyTextPaint.ascent()) / 2,
                emptyTextPaint
            )
            return
        }

        // 1. 经纬度边界
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }

        // 2. 等比缩放居中（经度按 cos(中位纬度) 修正）
        val pad = dp(24f)
        val availW = (width - pad * 2).coerceAtLeast(1f)
        val availH = (height - pad * 2).coerceAtLeast(1f)
        val cosMidLat = cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(1e-6)
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-9)
        val lngSpan = ((maxLng - minLng).coerceAtLeast(1e-9)) * cosMidLat
        val scale = min(availW / lngSpan, availH / latSpan)
        val offsetX = pad + (availW - lngSpan * scale) / 2
        val offsetY = pad + (availH - latSpan * scale) / 2

        fun xOf(lng: Double): Float = (offsetX + (lng - minLng) * cosMidLat * scale).toFloat()
        fun yOf(lat: Double): Float = (offsetY + (maxLat - lat) * scale).toFloat()

        // 3. 逐段绘制，颜色按相邻点瞬时速度在 红(慢)→绿(快) 间插值
        val speeds = computeSegmentSpeeds(points)
        val minSpeed = speeds.minOrNull() ?: 0.0
        val maxSpeed = speeds.maxOrNull() ?: 0.0
        for (i in 1 until points.size) {
            val t = if (maxSpeed - minSpeed < 1e-6) {
                1.0 // 全程匀速时按最快色（绿）绘制
            } else {
                ((speeds[i - 1] - minSpeed) / (maxSpeed - minSpeed)).coerceIn(0.0, 1.0)
            }
            linePaint.color = lerpColor(colorSlow, colorFast, t)
            canvas.drawLine(
                xOf(points[i - 1].lng), yOf(points[i - 1].lat),
                xOf(points[i].lng), yOf(points[i].lat),
                linePaint
            )
        }

        // 4. 起点 / 终点标记
        drawMarker(canvas, xOf(points.first().lng), yOf(points.first().lat), colorFast, "起")
        drawMarker(canvas, xOf(points.last().lng), yOf(points.last().lat), colorSlow, "终")
    }

    /** 相邻点瞬时速度（m/s）；漂移段（>30m/s）与无效段用其余有效段的均值兜底，避免色标被异常值拉垮 */
    private fun computeSegmentSpeeds(points: List<TrackPointEntity>): DoubleArray {
        val raw = DoubleArray(points.size - 1) { -1.0 }
        var sum = 0.0
        var count = 0
        for (i in 1 until points.size) {
            val dtSec = (points[i].timestamp - points[i - 1].timestamp) / 1000.0
            if (dtSec <= 0) continue
            val d = haversineM(points[i - 1], points[i])
            val v = d / dtSec
            if (v > TrackStats.DRIFT_SPEED_LIMIT_MS) continue
            raw[i - 1] = v
            sum += v
            count++
        }
        val fallback = if (count > 0) sum / count else 0.0
        return DoubleArray(raw.size) { if (raw[it] >= 0) raw[it] else fallback }
    }

    private fun haversineM(a: TrackPointEntity, b: TrackPointEntity): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            kotlin.math.sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    private fun lerpColor(from: Int, to: Int, t: Double): Int {
        fun lerp(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.rgb(
            lerp(Color.red(from), Color.red(to)),
            lerp(Color.green(from), Color.green(to)),
            lerp(Color.blue(from), Color.blue(to))
        )
    }

    private fun drawMarker(canvas: Canvas, cx: Float, cy: Float, color: Int, label: String) {
        val radius = dp(12f)
        markerPaint.color = color
        canvas.drawCircle(cx, cy, radius, markerPaint)
        // 文字垂直居中：基线 = 圆心 - (ascent+descent)/2
        val baseline = cy - (markerTextPaint.descent() + markerTextPaint.ascent()) / 2
        canvas.drawText(label, cx, baseline, markerTextPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
