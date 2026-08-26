package com.runpulse.app.ui.share

/** 分享海报模板。用户在分享弹窗中切换，实时预览。 */
enum class PosterTemplate(val label: String) {
    /** 经典：深色渐变底 + 发光轨迹 + 超大数字 + 速度曲线 */
    CLASSIC("经典"),

    /** 地图：Mapbox 离屏快照浅色路网 + 轨迹 + 数据 */
    MAP("地图"),

    /** 照片：用户照片全幅背景 + 半透明数据贴纸 */
    PHOTO("照片")
}
