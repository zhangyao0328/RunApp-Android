package com.runpulse.app.ui.map

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.runpulse.app.data.TrackPointEntity

/**
 * 轨迹地图视图抽象层。
 * 业务页面只面向本抽象编程，底层可切换 Canvas 自绘（零依赖兜底）或 Mapbox SDK。
 * 生命周期方法（onStart/onStop/onDestroy/onLowMemory）默认空实现，
 * 仅 Mapbox 实现需要宿主 Activity 转发。
 */
abstract class TrackMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 设置要展示的轨迹点（WGS84 原始坐标，坐标系转换由各实现自行处理） */
    abstract fun setTrack(points: List<TrackPointEntity>)

    /**
     * 展示当前位置（WGS84 原始坐标）。
     * api.mapbox.com 标准底图中国区是未偏移 WGS84 路网（实测验证，见 MapboxTrackView），
     * 实现方直接用原始坐标绘制即可，不要转 GCJ02；若未来换高德/腾讯等 GCJ02 底图才需要转换。
     * @param forceCenter true 时无论相机此前是否已定位过都强制居中（用于开始运动对准起点）
     */
    open fun showMyLocation(lat: Double, lng: Double, forceCenter: Boolean = false) {}

    /** 是否支持缩放/视野控制按钮（不支持时宿主应隐藏控制按钮，如 Canvas 兜底实现） */
    open val supportsMapControls: Boolean = false

    /** 放大一级（平滑动画） */
    open fun zoomIn() {}

    /** 缩小一级（平滑动画） */
    open fun zoomOut() {}

    /** 重新框住整条轨迹（用户在详情页手动缩放/平移后一键复位） */
    open fun fitTrack() {}

    /**
     * 详情页交互模式：禁用旋转/倾斜手势，双指只做缩放、单指只做平移，
     * 避免误触旋转导致视角歪斜（参考 Strava/Keep 详情页交互）。
     */
    open fun enableFlatGestures() {}

    open fun onStart() {}
    open fun onStop() {}
    open fun onDestroy() {}
    open fun onLowMemory() {}
}
