package com.runpulse.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import kotlin.math.abs

/**
 * 照片海报交互编辑器（WYSIWYG）：
 * - 复用 SharePosterRenderer 的绘制函数，视觉与最终全尺寸渲染完全一致
 * - 轨迹贴纸：单指拖动改位置；点选后出现虚线框 + 右上角 ✕ 删除按钮
 * - 文本热区：点 hero 距离 / 三格统计 → onEditField 回调（由宿主弹编辑框）
 *
 * 所有元素坐标保存在 poster 1080×1350 坐标系（PhotoPosterEdits），
 * View 按显示比例缩放绘制，最终渲染直接复用同一份坐标，零歧义。
 */
class PhotoPosterEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var edits = PhotoPosterEdits()
        set(value) {
            field = value
            invalidate()
        }

    /** 点中文本热区时回调（宿主弹编辑对话框） */
    var onEditField: ((PhotoEditField) -> Unit)? = null

    /** 轨迹可见性变化回调（宿主切换「＋添加轨迹」chip） */
    var onTrackVisibilityChanged: ((Boolean) -> Unit)? = null

    private var photo: Bitmap? = null
    private var trackSticker: Bitmap? = null
    private var points: List<TrackPointEntity> = emptyList()
    private var session: SessionEntity? = null

    private var trackSelected = false
    private var dragging = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var grabDX = 0f
    private var grabDY = 0f

    /** 选中态 chrome（poster 坐标系尺寸） */
    private val badgeRadius = 42f
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC141824")
        style = Paint.Style.FILL
    }
    private val badgeFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    fun bind(
        photo: Bitmap?,
        trackSticker: Bitmap?,
        points: List<TrackPointEntity>,
        session: SessionEntity?
    ) {
        this.photo = photo
        this.trackSticker = trackSticker
        this.points = points
        this.session = session
        invalidate()
    }

    /** 恢复显示轨迹（宿主「＋添加轨迹」chip 调用） */
    fun showTrack() {
        edits.trackVisible = true
        trackSelected = true
        onTrackVisibilityChanged?.invoke(true)
        invalidate()
    }

    /** poster 坐标系 → View 坐标系的缩放比 */
    private fun posterScale(): Float = if (width > 0) width.toFloat() / SharePosterRenderer.W else 1f

    /** 轨迹贴纸包围盒（poster 坐标系） */
    private fun trackBounds(): RectF? {
        val s = trackSticker ?: return null
        return RectF(
            edits.trackCenterX - s.width / 2f,
            edits.trackCenterY - s.height / 2f,
            edits.trackCenterX + s.width / 2f,
            edits.trackCenterY + s.height / 2f
        )
    }

    private fun badgeCenter(): android.graphics.PointF? {
        val b = trackBounds() ?: return null
        return android.graphics.PointF(b.right + 8f, b.top - 8f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = posterScale()
        canvas.save()
        canvas.scale(s, s)

        // 与 renderPhoto 完全一致的绘制分层
        SharePosterRenderer.drawPhotoBackground(canvas, photo)
        SharePosterRenderer.drawHeader(
            canvas, session,
            if (photo != null) Color.parseColor("#E6FFFFFF") else Color.parseColor("#94A3B8")
        )
        val sticker = trackSticker
        if (sticker != null && edits.trackVisible) {
            SharePosterRenderer.drawTrackStickerAt(
                canvas, sticker, edits.trackCenterX, edits.trackCenterY
            )
        }
        SharePosterRenderer.drawPhotoDataLayer(canvas, points, session, edits)
        SharePosterRenderer.drawFooter(canvas, Color.parseColor("#CCFFFFFF"))

        // 编辑态 chrome：选中轨迹时画虚线框 + ✕ 删除钮（不进最终渲染）
        if (sticker != null && edits.trackVisible && trackSelected) {
            val b = trackBounds()!!
            canvas.drawRoundRect(b, 20f, 20f, dashPaint)
            val bc = badgeCenter()!!
            canvas.drawCircle(bc.x, bc.y, badgeRadius, badgeBgPaint)
            canvas.drawCircle(bc.x, bc.y, badgeRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
            })
            val r = badgeRadius * 0.4f
            canvas.drawLine(bc.x - r, bc.y - r, bc.x + r, bc.y + r, badgeFgPaint)
            canvas.drawLine(bc.x - r, bc.y + r, bc.x + r, bc.y - r, badgeFgPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = posterScale()
        val px = event.x / s
        val py = event.y / s
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = px; downY = py
                moved = false
                // 1) ✕ 删除钮
                if (trackSelected && edits.trackVisible) {
                    val bc = badgeCenter()
                    if (bc != null && abs(px - bc.x) < badgeRadius + 14 && abs(py - bc.y) < badgeRadius + 14) {
                        edits.trackVisible = false
                        trackSelected = false
                        onTrackVisibilityChanged?.invoke(false)
                        invalidate()
                        return true
                    }
                }
                // 2) 轨迹包围盒 → 选中 + 开始拖动
                val b = if (edits.trackVisible) trackBounds() else null
                if (b != null && b.contains(px, py)) {
                    trackSelected = true
                    dragging = true
                    grabDX = px - edits.trackCenterX
                    grabDY = py - edits.trackCenterY
                    // 拖动贴纸期间禁止父容器（NestedScrollView / BottomSheet）拦截竖滑
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    if (abs(px - downX) > 8 || abs(py - downY) > 8) moved = true
                    // 限制贴纸中心不超出海报边界过多
                    edits.trackCenterX = (px - grabDX).coerceIn(120f, SharePosterRenderer.W - 120f)
                    edits.trackCenterY = (py - grabDY).coerceIn(160f, SharePosterRenderer.H - 160f)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    dragging = false
                    return true
                }
                if (dragging) {
                    dragging = false
                    return true
                }
                if (moved) return true
                // 3) 文本热区（点击而非拖动）
                val field = hitTextField(px, py)
                if (field != null) {
                    trackSelected = false
                    onEditField?.invoke(field)
                    invalidate()
                } else if (trackSelected) {
                    // 4) 空白处 → 取消选中
                    trackSelected = false
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 文本热区命中测试（poster 坐标系） */
    private fun hitTextField(px: Float, py: Float): PhotoEditField? {
        // hero 距离 = 贴纸卡区域
        if (SharePosterRenderer.STICKER_RECT.contains(px, py)) return PhotoEditField.DISTANCE
        // 三格统计：y ∈ [STATS_BASE_Y-90, STATS_BASE_Y+90]，x 三等分
        val top = SharePosterRenderer.STATS_BASE_Y - 90f
        val bottom = SharePosterRenderer.STATS_BASE_Y + 90f
        if (py in top..bottom && px in 80f..(SharePosterRenderer.W - 80f)) {
            val colW = (SharePosterRenderer.W - 160f) / 3f
            return when (((px - 80f) / colW).toInt().coerceIn(0, 2)) {
                0 -> PhotoEditField.DURATION
                1 -> PhotoEditField.PACE
                else -> PhotoEditField.ELEVATION
            }
        }
        return null
    }
}
