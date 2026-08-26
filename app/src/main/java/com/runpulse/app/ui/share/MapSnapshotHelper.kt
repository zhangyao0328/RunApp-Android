package com.runpulse.app.ui.share

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapSnapshotOptions
import com.mapbox.maps.Size
import com.mapbox.maps.SnapshotOverlayCallback
import com.mapbox.maps.SnapshotResultCallback
import com.mapbox.maps.Snapshotter
import com.mapbox.maps.Style
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.stats.TrackStats
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * 地图海报快照：Mapbox Snapshotter 离屏渲染浅色路网，轨迹通过 SnapshotOverlay
 * 直接画在快照画布上（overlay.screenCoordinate 自带精确投影，无需手算坐标系）。
 *
 * 相机中心/缩放用 Web Mercator 数学自行拟合轨迹包围盒（与 Mapbox 投影严格一致）。
 * 必须在主线程调用（内部会切到主线程执行）。
 */
object MapSnapshotHelper {

    private const val TILE_SIZE = 512.0
    private const val PADDING_PX = 60.0
    private const val MAX_ZOOM = 16.5
    private const val MIN_ZOOM = 3.0

    /** Web Mercator：经纬度 → zoom0 世界像素坐标 */
    private fun mercator(lat: Double, lng: Double): Pair<Double, Double> {
        val x = (lng + 180.0) / 360.0 * TILE_SIZE
        val y = (1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * TILE_SIZE
        return x to y
    }

    /** 由轨迹包围盒计算相机中心与缩放 */
    private fun cameraFor(
        points: List<TrackPointEntity>,
        widthPx: Int,
        heightPx: Int
    ): Pair<Point, Double> {
        val coords = points.map { mercator(it.lat, it.lng) }
        val minX = coords.minOf { it.first }; val maxX = coords.maxOf { it.first }
        val minY = coords.minOf { it.second }; val maxY = coords.maxOf { it.second }
        val spanX = max(maxX - minX, 1e-6)
        val spanY = max(maxY - minY, 1e-6)
        val zoom = min(
            ln((widthPx - PADDING_PX * 2) / spanX) / ln(2.0),
            ln((heightPx - PADDING_PX * 2) / spanY) / ln(2.0)
        ).coerceIn(MIN_ZOOM, MAX_ZOOM)

        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val centerLng = cx / TILE_SIZE * 360.0 - 180.0
        val centerLat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * cy / TILE_SIZE))))
        return Point.fromLngLat(centerLng, centerLat) to zoom
    }

    /**
     * 生成叠加轨迹的地图快照。轨迹点过稀/生成失败时返回 null（调用方退化为纯轨迹绘制）。
     */
    suspend fun snapshot(
        context: Context,
        points: List<TrackPointEntity>,
        widthPx: Int,
        heightPx: Int
    ): android.graphics.Bitmap? {
        val filtered = TrackStats.filteredPoints(points)
        if (filtered.size < 2) return null
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val (center, zoom) = cameraFor(filtered, widthPx, heightPx)
                val options = MapSnapshotOptions.Builder()
                    .size(Size(widthPx.toFloat(), heightPx.toFloat()))
                    .pixelRatio(1f)
                    .build()
                val snapshotter = Snapshotter(context, options)
                snapshotter.setStyleUri(Style.LIGHT)
                snapshotter.setCamera(
                    CameraOptions.Builder().center(center).zoom(zoom).build()
                )
                snapshotter.start(
                    object : SnapshotOverlayCallback {
                        override fun onSnapshotOverlay(overlay: com.mapbox.maps.SnapshotOverlay) {
                            // 快照画布上直接画轨迹（投影由 SDK 提供）
                            SharePosterRenderer.drawTrack(overlay.canvas, filtered) { p ->
                                val sc = overlay.screenCoordinate(
                                    Point.fromLngLat(p.lng, p.lat)
                                )
                                PointF(sc.x.toFloat(), sc.y.toFloat())
                            }
                        }
                    },
                    object : SnapshotResultCallback {
                        override fun onSnapshotResult(bitmap: android.graphics.Bitmap?, error: String?) {
                            snapshotter.destroy()
                            cont.resume(bitmap)
                        }
                    }
                )
                cont.invokeOnCancellation { snapshotter.cancel(); snapshotter.destroy() }
            }
        }
    }
}
