package com.runpulse.app.ui.map

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotation
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.scalebar.scalebar
import com.runpulse.app.BuildConfig
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.stats.TrackStats

/**
 * Mapbox Maps SDK v11 轨迹实现（配置 mapbox.token 后由 [TrackMapFactory] 启用）。
 *
 * 要点：
 * - token 通过 [MapboxOptions.accessToken] 在创建 MapView 前注入（v11 方式）；
 * - 轨迹画在 PolylineAnnotationManager 上，直接用 WGS84 原始点上图；
 *   注意：api.mapbox.com 标准底图中国区是未偏移 WGS84 路网（与高德 GCJ02 瓦片叠加实测验证），
 *   不能转 GCJ02，否则整体偏移约 500m（曾踩坑：误以为 Mapbox 中国底图是 GCJ02）；
 * - 起终点用 CircleAnnotation（无需图片资源）；
 * - 注解图层依赖 style 加载完成，因此 setTrack 过早调用时先缓存，待 style ready 后渲染；
 * - 生命周期方法由宿主 Activity 转发（见 SessionDetailActivity）。
 */
class MapboxTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TrackMapView(context, attrs, defStyleAttr) {

    private val mapView: MapView
    private var polylineManager: PolylineAnnotationManager? = null
    private var circleManager: CircleAnnotationManager? = null

    /** style 未 ready 时暂存的待渲染轨迹 */
    private var pendingTrack: List<TrackPointEntity>? = null

    /** style 未 ready 时暂存的当前位置（WGS84） */
    private var pendingMyLocation: Triple<Double, Double, Boolean>? = null

    /** 当前位置蓝点（自绘 CircleAnnotation，直接用 WGS84，与未偏移底图对齐） */
    private var myLocationCircle: CircleAnnotation? = null

    /** 起终点标注，单独记录以便清理时不误删当前位置蓝点 */
    private val trackCircles = mutableListOf<CircleAnnotation>()

    /** 是否已渲染过有效轨迹（有轨迹时以轨迹包围盒为准，不再定位到当前位置） */
    private var hasTrack = false

    /** 是否已完成首次“定位到当前位置”的相机移动 */
    private var myLocationCentered = false

    /** 最近一次渲染的轨迹（WGS84 已上图点），供 fitTrack 重新框选 */
    private var lastTrackPoints: List<Point>? = null

    /**
     * 用户是否已手动操作过地图（平移/缩放）。
     * 一旦手动操作，轨迹刷新不再强行复位相机，避免与手势打架（参考 Strava 行为）；
     * 点击定位/框选按钮或开始运动（forceCenter）时重新允许自动定位。
     */
    private var userInteracted = false

    init {
        MapboxOptions.accessToken = BuildConfig.MAPBOX_TOKEN
        Log.d(TAG, "MapboxTrackView 初始化，token=${BuildConfig.MAPBOX_TOKEN.take(12)}...")
        mapView = MapView(context)
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 界面净化：隐藏左下角 logo；罗盘/比例尺不需要；
        // 保留 attribution（Mapbox 服务条款要求），移到左下角弱化存在感
        mapView.logo.enabled = false
        mapView.compass.enabled = false
        mapView.scalebar.enabled = false
        mapView.attribution.updateSettings {
            position = Gravity.BOTTOM or Gravity.START
            marginLeft = 8f
            marginBottom = 8f
        }

        // 嵌在 ScrollView（详情页）时：地图触摸期间禁止父容器拦截，
        // 否则竖向拖动会被外层滚动抢走导致双指缩放/平移极难用
        mapView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) userInteracted = true
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false // 不消费事件，交还给 Mapbox 手势系统
        }

        mapView.mapboxMap.loadStyle(Style.STANDARD) { _ ->
            Log.d(TAG, "样式加载完成，初始化注解图层")
            val annotationApi = mapView.annotations
            polylineManager = annotationApi.createPolylineAnnotationManager()
            circleManager = annotationApi.createCircleAnnotationManager()
            pendingTrack?.let { renderTrack(it) }
            pendingTrack = null
            pendingMyLocation?.let { (lat, lng, forceCenter) -> renderMyLocation(lat, lng, forceCenter) }
            pendingMyLocation = null
        }
    }

    override fun showMyLocation(lat: Double, lng: Double, forceCenter: Boolean) {
        if (circleManager == null) {
            pendingMyLocation = Triple(lat, lng, forceCenter) // style 尚未就绪，稍后渲染
            return
        }
        renderMyLocation(lat, lng, forceCenter)
    }

    private fun renderMyLocation(lat: Double, lng: Double, forceCenter: Boolean) {
        val circles = circleManager ?: return
        // api.mapbox.com 标准底图中国区是未偏移 WGS84 路网（已用高德 GCJ02 瓦片叠加验证），
        // 直接用原始 GPS 坐标上图，切勿转 GCJ02（转了反而偏出约 500m）
        val point = Point.fromLngLat(lng, lat)

        val existing = myLocationCircle
        if (existing == null) {
            myLocationCircle = circles.create(
                CircleAnnotationOptions()
                    .withPoint(point)
                    .withCircleRadius(7.0)
                    .withCircleColor(Color.parseColor("#1E88E5"))
                    .withCircleStrokeWidth(2.0)
                    .withCircleStrokeColor(Color.WHITE)
            )
        } else {
            existing.point = point
            circles.update(existing)
        }

        // 首次进入（尚无轨迹）时把相机对准当前位置，街景级缩放；开始运动时强制对准起点
        if (forceCenter || (!hasTrack && !myLocationCentered)) {
            myLocationCentered = true
            if (forceCenter) userInteracted = false // 用户点了定位/开始运动，恢复自动定位
            mapView.mapboxMap.easeTo(
                CameraOptions.Builder()
                    .center(point)
                    .zoom(MY_LOCATION_ZOOM)
                    .build(),
                MapAnimationOptions.mapAnimationOptions { duration(CAMERA_ANIM_MS) }
            )
        }
    }

    override fun setTrack(points: List<TrackPointEntity>) {
        if (polylineManager == null || circleManager == null) {
            pendingTrack = points // style 尚未就绪，稍后渲染
            return
        }
        renderTrack(points)
    }

    private fun renderTrack(points: List<TrackPointEntity>) {
        val polyline = polylineManager ?: return
        val circles = circleManager ?: return
        // 与统计口径一致：先过滤低精度漂移点再渲染
        val clean = TrackStats.filteredPoints(points)
        Log.d(TAG, "渲染轨迹：原始 ${points.size} 点，过滤后 ${clean.size} 点")
        polyline.deleteAll()
        // 只清起终点标注，保留当前位置蓝点
        if (trackCircles.isNotEmpty()) {
            circles.delete(trackCircles.toList())
            trackCircles.clear()
        }
        if (clean.size < 2) {
            hasTrack = false
            return
        }
        hasTrack = true

        // 直接用 WGS84 原始点上图（底图未偏移，见 renderMyLocation 注释）
        val mapPoints = clean.map { p -> Point.fromLngLat(p.lng, p.lat) }
        lastTrackPoints = mapPoints

        polyline.create(
            PolylineAnnotationOptions()
                .withPoints(mapPoints)
                .withLineColor(Color.parseColor("#4CAF50"))
                .withLineWidth(4.0)
        )

        trackCircles += circles.create(
            CircleAnnotationOptions()
                .withPoint(mapPoints.first())
                .withCircleRadius(8.0)
                .withCircleColor(Color.parseColor("#4CAF50"))
        )
        trackCircles += circles.create(
            CircleAnnotationOptions()
                .withPoint(mapPoints.last())
                .withCircleRadius(8.0)
                .withCircleColor(Color.parseColor("#F44336"))
        )

        // 相机移动到轨迹包围盒；用户已手动缩放/平移过则不打扰（Strava 行为）
        if (!userInteracted) {
            fitCameraTo(mapPoints)
        }
    }

    // ---------- 地图控制（缩放/复位，供悬浮按钮调用） ----------

    override val supportsMapControls: Boolean = true

    override fun zoomIn() = easeZoom(1.0)

    override fun zoomOut() = easeZoom(-1.0)

    private fun easeZoom(delta: Double) {
        userInteracted = true // 缩放是用户主动行为，之后不再自动复位相机
        mapView.mapboxMap.easeTo(
            CameraOptions.Builder()
                .zoom((mapView.mapboxMap.cameraState.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM))
                .build(),
            MapAnimationOptions.mapAnimationOptions { duration(ZOOM_ANIM_MS) }
        )
    }

    override fun fitTrack() {
        val points = lastTrackPoints ?: return
        userInteracted = false // 复位后恢复自动框选
        fitCameraTo(points)
    }

    private fun fitCameraTo(points: List<Point>) {
        val camera = mapView.mapboxMap.cameraForCoordinates(
            points,
            EdgeInsets(60.0, 60.0, 60.0, 60.0),
            null,
            null
        )
        mapView.mapboxMap.easeTo(
            camera,
            MapAnimationOptions.mapAnimationOptions { duration(CAMERA_ANIM_MS) }
        )
    }

    override fun enableFlatGestures() {
        mapView.gestures.updateSettings {
            rotateEnabled = false
            pitchEnabled = false
        }
    }

    companion object {
        private const val TAG = "MapboxTrackView"

        /** 首次进入定位到当前位置时的缩放级别（街景级，可按需调整） */
        private const val MY_LOCATION_ZOOM = 15.5

        /** 缩放按钮允许的缩放范围 */
        private const val MIN_ZOOM = 3.0
        private const val MAX_ZOOM = 19.0

        private const val CAMERA_ANIM_MS = 600L
        private const val ZOOM_ANIM_MS = 300L
    }

    // ---------- 生命周期转发（宿主 Activity 调用） ----------

    override fun onStart() = mapView.onStart()
    override fun onStop() = mapView.onStop()
    override fun onDestroy() = mapView.onDestroy()
    override fun onLowMemory() = mapView.onLowMemory()
}
