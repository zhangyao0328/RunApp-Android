package com.runpulse.app.ui.map

import android.content.Context
import com.runpulse.app.BuildConfig

/**
 * 轨迹地图工厂：配置了有效 Mapbox token 用 Mapbox 实现，否则回退 Canvas 自绘。
 * token 在 local.properties 中配置 mapbox.token=pk.xxx，见 README。
 */
object TrackMapFactory {

    private const val TOKEN_PLACEHOLDER = "MAPBOX_TOKEN_PLACEHOLDER"

    fun create(context: Context): TrackMapView {
        val token = BuildConfig.MAPBOX_TOKEN
        return if (token.isNotEmpty() && token != TOKEN_PLACEHOLDER) {
            MapboxTrackView(context)
        } else {
            CanvasTrackView(context)
        }
    }
}
