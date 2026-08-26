package com.runpulse.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.runpulse.app.R
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.stats.TrackStats
import com.runpulse.app.tracking.SportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

/**
 * 分享海报渲染器：离屏 Canvas 合成 1080×1350（4:5，朋友圈/IG 竖版黄金比例）。
 *
 * 三个模板（用户分享时可选）：
 * - CLASSIC：深色渐变底 + 能量橙发光轨迹 + Strava 式超大距离数字 + 速度曲线
 * - MAP：Mapbox Snapshotter 离屏浅色路网快照（轨迹已在快照阶段叠加）+ 数据区
 * - PHOTO：用户照片全幅背景 + 深色渐变遮罩 + 半透明数据贴纸
 *
 * 字体：Barlow Condensed（运动窄体，拉丁数字）+ 系统字体回退（中文）。
 * 数据口径：与详情页一致，TrackStats 消费侧过滤（accuracy ≤25m、段速 ≤30m/s）。
 */
object SharePosterRenderer {

    const val W = 1080
    const val H = 1350
    private const val MARGIN = 80f

    /** MAP 模板地图卡片尺寸（快照按此尺寸离屏渲染，保证轨迹投影一致） */
    const val MAP_CARD_W = (W - 2 * 80)
    const val MAP_CARD_H = 620

    /** PHOTO 模板：轨迹贴纸尺寸与默认中心（poster 坐标系），编辑器/渲染器共用 */
    const val TRACK_STICKER_W = 540
    const val TRACK_STICKER_H = 400
    const val TRACK_DEFAULT_CX = 540f
    const val TRACK_DEFAULT_CY = 520f

    /** PHOTO 模板：数据贴纸卡矩形 / 统计行基线（文本热区命中测试共用） */
    val STICKER_RECT = RectF(MARGIN, 790f, W - MARGIN, 1070f)
    const val STATS_BASE_Y = 1170f

    private val COLOR_ORANGE = Color.parseColor("#F97316")
    private val COLOR_GREEN = Color.parseColor("#22C55E")
    private val COLOR_TEXT = Color.parseColor("#F8FAFC")
    private val COLOR_MUTED = Color.parseColor("#94A3B8")

    private var fontCondensedBold: Typeface? = null
    private var fontCondensedSemi: Typeface? = null
    private var fontMedium: Typeface? = null

    /** 字体懒加载（applicationContext，无泄漏） */
    private fun ensureFonts(context: Context) {
        if (fontCondensedBold != null) return
        val app = context.applicationContext
        fontCondensedBold = ResourcesCompat.getFont(app, R.font.barlow_condensed_bold)
        fontCondensedSemi = ResourcesCompat.getFont(app, R.font.barlow_condensed_semibold)
        fontMedium = ResourcesCompat.getFont(app, R.font.barlow_medium)
    }

    /**
     * @param mapSnapshot MAP 模板专用：已叠加轨迹的地图快照位图（由 MapSnapshotHelper 生成）
     * @param photo      PHOTO 模板专用：用户照片位图（任意尺寸，内部中心裁剪）
     */
    fun render(
        context: Context,
        template: PosterTemplate,
        points: List<TrackPointEntity>,
        session: SessionEntity?,
        mapSnapshot: Bitmap? = null,
        photo: Bitmap? = null,
        photoEdits: PhotoPosterEdits? = null,
        trackSticker: Bitmap? = null
    ): Bitmap {
        ensureFonts(context)
        return when (template) {
            PosterTemplate.CLASSIC -> renderClassic(points, session)
            PosterTemplate.MAP -> renderMap(points, session, mapSnapshot)
            PosterTemplate.PHOTO -> renderPhoto(points, session, photo, photoEdits, trackSticker)
        }
    }

    // ============================================================
    // CLASSIC 模板
    // ============================================================

    private fun renderClassic(points: List<TrackPointEntity>, session: SessionEntity?): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawDarkBackground(canvas)
        drawHeader(canvas, session, COLOR_MUTED)

        // 轨迹区 y ∈ [220, 640]
        val filtered = TrackStats.filteredPoints(points)
        drawTrack(canvas, filtered, fitProjection(filtered, RectF(MARGIN, 230f, W - MARGIN, 660f)))

        drawHeroDistance(canvas, points, 930f, COLOR_TEXT, COLOR_ORANGE)
        drawStatsRow(canvas, points, session, 1110f, COLOR_TEXT, COLOR_MUTED)
        drawSpeedCurve(canvas, filtered, RectF(MARGIN, 1195f, W - MARGIN, 1255f))
        drawFooter(canvas, COLOR_MUTED)
        return bitmap
    }

    // ============================================================
    // MAP 模板
    // ============================================================

    private fun renderMap(
        points: List<TrackPointEntity>,
        session: SessionEntity?,
        mapSnapshot: Bitmap?
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawDarkBackground(canvas)
        drawHeader(canvas, session, COLOR_MUTED)

        // 地图卡片：圆角裁剪快照，失败时退化为纯轨迹
        val card = RectF(MARGIN, 200f, MARGIN + MAP_CARD_W, 200f + MAP_CARD_H)
        val filtered = TrackStats.filteredPoints(points)
        canvas.save()
        val clip = Path().apply { addRoundRect(card, 32f, 32f, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (mapSnapshot != null) {
            canvas.drawBitmap(mapSnapshot, null, card, null)
        } else {
            canvas.drawColor(Color.parseColor("#151D2B"))
            drawTrack(canvas, filtered, fitProjection(filtered, card))
        }
        canvas.restore()
        // 卡片描边
        canvas.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })

        drawHeroDistance(canvas, points, 1000f, COLOR_TEXT, COLOR_ORANGE)
        drawStatsRow(canvas, points, session, 1130f, COLOR_TEXT, COLOR_MUTED)
        drawSpeedCurve(canvas, filtered, RectF(MARGIN, 1200f, W - MARGIN, 1255f))
        drawFooter(canvas, COLOR_MUTED)
        return bitmap
    }

    // ============================================================
    // PHOTO 模板
    // ============================================================

    private fun renderPhoto(
        points: List<TrackPointEntity>,
        session: SessionEntity?,
        photo: Bitmap?,
        edits: PhotoPosterEdits? = null,
        trackSticker: Bitmap? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawPhotoBackground(canvas, photo)
        drawHeader(canvas, session, if (photo != null) Color.parseColor("#E6FFFFFF") else COLOR_MUTED)

        // 轨迹贴纸：可拖动/删除（edits 控制），默认居中上部
        val stickerBmp = trackSticker ?: makeTrackSticker(TrackStats.filteredPoints(points))
        val trackVisible = edits?.trackVisible ?: true
        if (stickerBmp != null && trackVisible) {
            drawTrackStickerAt(
                canvas, stickerBmp,
                edits?.trackCenterX ?: TRACK_DEFAULT_CX,
                edits?.trackCenterY ?: TRACK_DEFAULT_CY
            )
        }

        // 数据层（贴纸卡 + hero 距离 + 三格统计）
        drawPhotoDataLayer(canvas, points, session, edits)
        drawFooter(canvas, Color.parseColor("#CCFFFFFF"))
        return bitmap
    }

    /** PHOTO 模板数据层：半透明贴纸卡 + hero 距离 + 三格统计（编辑器预览与最终渲染共用） */
    fun drawPhotoDataLayer(
        canvas: Canvas,
        points: List<TrackPointEntity>,
        session: SessionEntity?,
        edits: PhotoPosterEdits?
    ) {
        canvas.drawRoundRect(STICKER_RECT, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B3141824")
        })
        drawHeroDistance(canvas, points, 1015f, COLOR_TEXT, COLOR_ORANGE, edits?.distanceText)
        drawStatsRow(
            canvas, points, session, STATS_BASE_Y, COLOR_TEXT, Color.parseColor("#CCFFFFFF"),
            durationOverride = edits?.durationText,
            paceOverride = edits?.paceText,
            elevationOverride = edits?.elevationText
        )
    }

    /** 照片背景：全幅 centerCrop + 顶部/底部渐变遮罩；无照片时深色底兜底 */
    fun drawPhotoBackground(canvas: Canvas, photo: Bitmap?) {
        if (photo != null) {
            canvas.drawBitmap(
                photo, centerCropSrc(photo),
                RectF(0f, 0f, W.toFloat(), H.toFloat()), null
            )
            // 顶部/底部渐变遮罩，保证文字可读
            canvas.drawRect(0f, 0f, W.toFloat(), 320f, Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, 320f,
                    Color.parseColor("#99000000"), Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
            })
            canvas.drawRect(0f, 620f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 620f, 0f, H.toFloat(),
                    Color.TRANSPARENT, Color.parseColor("#CC000000"), Shader.TileMode.CLAMP
                )
            })
        } else {
            drawDarkBackground(canvas)
        }
    }

    /** 轨迹贴纸：透明底位图（发光轨迹 + 起终点），编辑器预览与最终渲染共用 */
    fun makeTrackSticker(
        points: List<TrackPointEntity>,
        w: Int = TRACK_STICKER_W,
        h: Int = TRACK_STICKER_H
    ): Bitmap? {
        if (points.size < 2) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val box = RectF(30f, 30f, w - 30f, h - 30f)
        drawTrack(canvas, points, fitProjection(points, box))
        return bitmap
    }

    /** 按中心点绘制轨迹贴纸 */
    fun drawTrackStickerAt(canvas: Canvas, sticker: Bitmap, cx: Float, cy: Float) {
        val left = cx - sticker.width / 2f
        val top = cy - sticker.height / 2f
        canvas.drawBitmap(sticker, left, top, null)
    }

    /** 照片源矩形：中心裁剪成 4:5 */
    private fun centerCropSrc(photo: Bitmap): android.graphics.Rect {
        val targetRatio = W.toFloat() / H
        val srcRatio = photo.width.toFloat() / photo.height
        return if (srcRatio > targetRatio) {
            val w = (photo.height * targetRatio).toInt()
            val x = (photo.width - w) / 2
            android.graphics.Rect(x, 0, x + w, photo.height)
        } else {
            val h = (photo.width / targetRatio).toInt()
            val y = (photo.height - h) / 2
            android.graphics.Rect(0, y, photo.width, y + h)
        }
    }

    // ============================================================
    // 共享绘制块
    // ============================================================

    private fun drawDarkBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                Color.parseColor("#0B1220"), Color.parseColor("#1F2937"),
                Shader.TileMode.CLAMP
            )
        })
        canvas.drawRect(MARGIN, 72f, MARGIN + 96f, 80f, Paint().apply { color = COLOR_ORANGE })
    }

    fun drawHeader(canvas: Canvas, session: SessionEntity?, textColor: Int) {
        val sportLabel = when (SportType.fromValue(session?.sportType)) {
            SportType.RUN -> "跑步"
            SportType.TRAIL -> "越野跑"
            SportType.BIKE -> "骑行"
        }
        val dateLabel = session?.startTime?.let {
            SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(Date(it))
        } ?: ""
        canvas.drawText("$dateLabel · $sportLabel", MARGIN, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 34f
            letterSpacing = 0.08f
            typeface = fontMedium ?: Typeface.DEFAULT
        })
    }

    /** 轨迹归一化投影：把过滤后点集适配进目标矩形，经度乘 cos(纬度) 修正东西向拉伸 */
    private fun fitProjection(
        points: List<TrackPointEntity>,
        box: RectF
    ): (TrackPointEntity) -> PointF {
        if (points.isEmpty()) return { PointF(box.centerX(), box.centerY()) }
        val refLat = points.map { it.lat }.average()
        val cosLat = cos(Math.toRadians(refLat))
        val xs = points.map { it.lng * cosLat }
        val ys = points.map { it.lat }
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val scaleX = if (maxX - minX > 1e-9) box.width() / (maxX - minX).toFloat() else 1f
        val scaleY = if (maxY - minY > 1e-9) box.height() / (maxY - minY).toFloat() else 1f
        val scale = minOf(scaleX, scaleY)
        val offsetX = box.centerX() - ((minX + maxX) / 2 * scale).toFloat()
        val offsetY = box.centerY() + ((minY + maxY) / 2 * scale).toFloat()
        return { p ->
            PointF((p.lng * cosLat * scale).toFloat() + offsetX, -(p.lat * scale).toFloat() + offsetY)
        }
    }

    /** 通用轨迹绘制：发光底层 + 主线 + 起/终点标记。project 为坐标→像素投影函数 */
    fun drawTrack(
        canvas: Canvas,
        points: List<TrackPointEntity>,
        project: (TrackPointEntity) -> PointF
    ) {
        if (points.size < 2) return
        val path = Path()
        val first = project(points[0])
        path.moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            val p = project(points[i])
            path.lineTo(p.x, p.y)
        }

        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ORANGE
            alpha = 60
            style = Paint.Style.STROKE
            strokeWidth = 28f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ORANGE
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val start = project(points[0])
        val end = project(points[points.size - 1])
        dotPaint.color = COLOR_GREEN
        canvas.drawCircle(start.x, start.y, 18f, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(start.x, start.y, 8f, dotPaint)
        dotPaint.color = COLOR_ORANGE
        canvas.drawCircle(end.x, end.y, 18f, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(end.x, end.y, 8f, dotPaint)
    }

    /** Hero 数字：Barlow Condensed 超大距离 + 橙色单位；textOverride 非空时用编辑后的文本 */
    fun drawHeroDistance(
        canvas: Canvas,
        points: List<TrackPointEntity>,
        baseline: Float,
        numberColor: Int,
        unitColor: Int,
        textOverride: String? = null
    ) {
        val dist = TrackStats.distanceKm(points)
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = numberColor
            textSize = 230f
            typeface = fontCondensedBold ?: Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val number = textOverride ?: String.format(Locale.CHINA, "%.2f", dist)
        // 编辑后文本可能更长：超出可用宽度时等比缩字号
        val maxWidth = W - MARGIN * 2 - 110f
        val measured = numberPaint.measureText(number)
        if (measured > maxWidth) {
            numberPaint.textSize = 230f * maxWidth / measured
        }
        val numberWidth = numberPaint.measureText(number)
        canvas.drawText(number, MARGIN, baseline, numberPaint)
        canvas.drawText("km", MARGIN + numberWidth + 20f, baseline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = unitColor
            textSize = 64f
            typeface = fontCondensedSemi ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
    }

    /** 三格统计：时长 / 配速或均速 / 爬升，细分隔线；各列可用 override 覆盖显示文本 */
    fun drawStatsRow(
        canvas: Canvas,
        points: List<TrackPointEntity>,
        session: SessionEntity?,
        baseY: Float,
        valueColor: Int,
        labelColor: Int,
        durationOverride: String? = null,
        paceOverride: String? = null,
        elevationOverride: String? = null
    ) {
        val startMs = session?.startTime ?: points.firstOrNull()?.timestamp ?: 0L
        val endMs = session?.endTime ?: points.lastOrNull()?.timestamp ?: startMs
        val isBike = session?.sportType == SportType.BIKE.value
        val paceValue = if (isBike) {
            String.format(Locale.CHINA, "%.1f", TrackStats.avgSpeedKmh(points))
        } else {
            TrackStats.avgPaceString(points)
        }
        val stats = listOf(
            (durationOverride ?: TrackStats.formatDuration(startMs, endMs)) to "时长",
            (paceOverride ?: paceValue) to if (isBike) "平均速度 km/h" else "平均配速 /km",
            (elevationOverride ?: String.format(Locale.CHINA, "+%.0f", TrackStats.elevationGainM(points))) to "爬升 m"
        )

        val colW = (W - MARGIN * 2) / 3f
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueColor
            textSize = 72f
            typeface = fontCondensedSemi ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 28f
            typeface = fontMedium ?: Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        val dividerPaint = Paint().apply { color = Color.parseColor("#33FFFFFF") }

        stats.forEachIndexed { i, (value, label) ->
            val cx = MARGIN + colW * i + colW / 2
            // 编辑后文本可能更长：超出列宽时等比缩字号
            val maxW = colW - 24f
            val measured = valuePaint.measureText(value)
            valuePaint.textSize = if (measured > maxW) 72f * maxW / measured else 72f
            canvas.drawText(value, cx, baseY, valuePaint)
            canvas.drawText(label, cx, baseY + 52f, labelPaint)
            if (i > 0) {
                val dx = MARGIN + colW * i
                canvas.drawRect(dx, baseY - 55f, dx + 2f, baseY + 40f, dividerPaint)
            }
        }
    }

    /** PHOTO 模板默认文本（编辑对话框预填 / 恢复默认用），与 drawHeroDistance/drawStatsRow 口径一致 */
    fun defaultPhotoTexts(points: List<TrackPointEntity>, session: SessionEntity?): PhotoPosterDefaults {
        val startMs = session?.startTime ?: points.firstOrNull()?.timestamp ?: 0L
        val endMs = session?.endTime ?: points.lastOrNull()?.timestamp ?: startMs
        val isBike = session?.sportType == SportType.BIKE.value
        return PhotoPosterDefaults(
            distance = String.format(Locale.CHINA, "%.2f", TrackStats.distanceKm(points)),
            duration = TrackStats.formatDuration(startMs, endMs),
            pace = if (isBike) String.format(Locale.CHINA, "%.1f", TrackStats.avgSpeedKmh(points))
                   else TrackStats.avgPaceString(points),
            elevation = String.format(Locale.CHINA, "+%.0f", TrackStats.elevationGainM(points)),
            paceLabel = if (isBike) "平均速度 km/h" else "平均配速 /km"
        )
    }

    /**
     * 速度曲线：GPS speed 字段（m/s → km/h）做滑动平均，画渐变填充面积图 + 主线。
     * 点不足时静默跳过。
     */
    private fun drawSpeedCurve(canvas: Canvas, points: List<TrackPointEntity>, band: RectF) {
        if (points.size < 10) return
        val speeds = points.map { it.speed * 3.6f }
        // 滑动平均（窗口 9）抑制 GPS 抖动
        val win = 9
        val smooth = speeds.mapIndexed { i, _ ->
            val from = (i - win / 2).coerceAtLeast(0)
            val to = (i + win / 2).coerceAtMost(speeds.size - 1)
            speeds.subList(from, to + 1).average().toFloat()
        }
        val maxV = smooth.max().coerceAtLeast(1f)
        val minV = smooth.min()
        val range = (maxV - minV).coerceAtLeast(0.5f)

        val stepX = band.width() / (smooth.size - 1)
        fun cx(i: Int) = band.left + i * stepX
        fun cy(v: Float) = band.bottom - (v - minV) / range * band.height()

        val line = Path().apply {
            moveTo(cx(0), cy(smooth[0]))
            for (i in 1 until smooth.size) lineTo(cx(i), cy(smooth[i]))
        }
        val area = Path(line).apply {
            lineTo(band.right, band.bottom)
            lineTo(band.left, band.bottom)
            close()
        }

        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, band.top, 0f, band.bottom,
                Color.parseColor("#59F97316"), Color.parseColor("#00F97316"),
                Shader.TileMode.CLAMP
            )
        })
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ORANGE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        })

        // 峰值/谷值标注：画在 band 内部左上，避免与上方统计标签碰撞
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
            textSize = 24f
            typeface = fontCondensedSemi
        }
        canvas.drawText(
            String.format(Locale.CHINA, "速度 %.0f~%.0f km/h", minV, maxV),
            band.left, band.top + 26f, notePaint
        )
    }

    fun drawFooter(canvas: Canvas, textColor: Int) {
        canvas.drawText("RUNPULSE", W - MARGIN, H - 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 32f
            letterSpacing = 0.15f
            textAlign = Paint.Align.RIGHT
            typeface = fontCondensedSemi ?: Typeface.DEFAULT
        })
        canvas.drawRect(MARGIN, H - 80f, MARGIN + 64f, H - 72f, Paint().apply { color = COLOR_ORANGE })
    }
}
