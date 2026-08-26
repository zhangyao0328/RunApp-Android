package com.runpulse.app.ui.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.runpulse.app.R
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.databinding.DialogSharePosterBinding
import com.runpulse.app.stats.TrackStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.os.Environment
import android.content.Intent

/**
 * 分享海报弹窗（底部抽屉）：
 * - 实时预览当前模板渲染结果（模板切换带缓存，不重复渲染）
 * - 经典 / 地图 / 照片 三模板；照片模板通过系统 Photo Picker 选图（免存储权限）
 * - 分享：保存 PNG → FileProvider → 系统分享面板
 *
 * 数据通过 companion 传入（轨迹点列表较大，不走 Bundle/Parcelable）。
 */
class SharePosterDialogFragment : BottomSheetDialogFragment() {

    companion object {
        private var pendingPoints: List<TrackPointEntity>? = null
        private var pendingSession: SessionEntity? = null

        /** Activity 注入：启动系统选图（结果经 onPhotoPicked 回传） */
        var photoPickLauncher: (() -> Unit)? = null

        fun newInstance(
            points: List<TrackPointEntity>,
            session: SessionEntity?
        ): SharePosterDialogFragment {
            pendingPoints = points
            pendingSession = session
            return SharePosterDialogFragment()
        }
    }

    private var _binding: DialogSharePosterBinding? = null
    private val binding get() = _binding!!

    private val renderCache = mutableMapOf<PosterTemplate, Bitmap>()
    private var currentTemplate = PosterTemplate.CLASSIC
    private var photoBitmap: Bitmap? = null

    // ---- 照片模板编辑态 ----
    private val photoEdits = PhotoPosterEdits()
    private var trackSticker: Bitmap? = null
    private var photoDefaults: PhotoPosterDefaults? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSharePosterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 预览区保持 4:5 不畸变：高度超屏 52% 时等比收窄宽度并居中
        binding.ivPreview.post {
            val maxW = binding.ivPreview.width
            val maxH = (resources.displayMetrics.heightPixels * 0.52f).toInt()
            val h = minOf(maxW * SharePosterRenderer.H / SharePosterRenderer.W, maxH)
            val w = h * SharePosterRenderer.W / SharePosterRenderer.H
            binding.ivPreview.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            binding.photoEditor.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }

        // 照片编辑器：文本热区 → 编辑对话框；轨迹删除 → 显示「＋添加轨迹」
        binding.photoEditor.edits = photoEdits
        binding.photoEditor.onEditField = { field -> showEditDialog(field) }
        binding.photoEditor.onTrackVisibilityChanged = { visible ->
            binding.btnAddTrack.isVisible = !visible && currentTemplate == PosterTemplate.PHOTO
        }
        binding.btnAddTrack.setOnClickListener {
            binding.photoEditor.showTrack()
        }

        binding.btnClose.setOnClickListener { dismiss() }
        setupTab(binding.tabClassic, PosterTemplate.CLASSIC)
        setupTab(binding.tabMap, PosterTemplate.MAP)
        setupTab(binding.tabPhoto, PosterTemplate.PHOTO)

        binding.btnPickPhoto.setOnClickListener { photoPickLauncher?.invoke() }
        binding.btnShare.setOnClickListener { shareCurrent() }

        selectTemplate(PosterTemplate.CLASSIC)
    }

    // ---------- 模板切换 ----------

    private fun setupTab(tab: TextView, template: PosterTemplate) {
        tab.setOnClickListener {
            if (template == PosterTemplate.PHOTO && photoBitmap == null) {
                // 首次进照片模板：先弹系统选图
                photoPickLauncher?.invoke()
            }
            selectTemplate(template)
        }
    }

    private fun selectTemplate(template: PosterTemplate) {
        currentTemplate = template
        updateTabStyles()
        binding.btnPickPhoto.isVisible = template == PosterTemplate.PHOTO

        if (template == PosterTemplate.PHOTO) {
            // 照片模板：交互编辑器（实时绘制，不走渲染缓存）
            binding.ivPreview.isVisible = false
            binding.photoEditor.isVisible = true
            binding.btnAddTrack.isVisible = !photoEdits.trackVisible
            bindPhotoEditor()
            return
        }
        binding.ivPreview.isVisible = true
        binding.photoEditor.isVisible = false
        binding.btnAddTrack.isVisible = false

        val cached = renderCache[template]
        if (cached != null) {
            binding.ivPreview.setImageBitmap(cached)
            return
        }
        renderTemplate(template)
    }

    /** 绑定照片编辑器数据；轨迹贴纸首次在后台生成 */
    private fun bindPhotoEditor() {
        val points = pendingPoints ?: return
        val session = pendingSession
        photoDefaults = SharePosterRenderer.defaultPhotoTexts(points, session)
        val sticker = trackSticker
        if (sticker != null) {
            binding.photoEditor.bind(photoBitmap, sticker, points, session)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val generated = withContext(Dispatchers.Default) {
                SharePosterRenderer.makeTrackSticker(TrackStats.filteredPoints(points))
            }
            if (!isAdded) return@launch
            trackSticker = generated
            if (currentTemplate == PosterTemplate.PHOTO) {
                binding.photoEditor.bind(photoBitmap, generated, points, session)
            }
        }
    }

    private fun updateTabStyles() {
        val tabs = mapOf(
            PosterTemplate.CLASSIC to binding.tabClassic,
            PosterTemplate.MAP to binding.tabMap,
            PosterTemplate.PHOTO to binding.tabPhoto
        )
        tabs.forEach { (t, view) ->
            val selected = t == currentTemplate
            view.setBackgroundResource(
                if (selected) R.drawable.bg_poster_tab_selected else R.drawable.bg_poster_tab_normal
            )
            view.setTextColor(if (selected) 0xFF141824.toInt() else 0xFFF8FAFC.toInt())
        }
    }

    // ---------- 渲染 ----------

    private fun renderTemplate(template: PosterTemplate) {
        val points = pendingPoints ?: return
        val session = pendingSession
        binding.progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = when (template) {
                PosterTemplate.CLASSIC -> withContext(Dispatchers.Default) {
                    SharePosterRenderer.render(requireContext(), template, points, session)
                }

                else -> {
                    // MAP：Snapshotter 必须主线程，helper 内部已处理线程切换
                    val snapshot = MapSnapshotHelper.snapshot(
                        requireContext(), points,
                        SharePosterRenderer.MAP_CARD_W, SharePosterRenderer.MAP_CARD_H
                    )
                    withContext(Dispatchers.Default) {
                        SharePosterRenderer.render(
                            requireContext(), template, points, session, mapSnapshot = snapshot
                        )
                    }
                }
            }
            if (!isAdded) return@launch
            renderCache[template] = bitmap
            if (currentTemplate == template) {
                binding.ivPreview.setImageBitmap(bitmap)
            }
            binding.progress.isVisible = false
        }
    }

    // ---------- 照片模板编辑 ----------

    /** 文本编辑对话框：预填当前值，支持恢复默认 */
    private fun showEditDialog(field: PhotoEditField) {
        val defaults = photoDefaults ?: return
        val (title, current, defaultValue) = when (field) {
            PhotoEditField.DISTANCE -> Triple(
                getString(R.string.share_edit_distance),
                photoEdits.distanceText, defaults.distance
            )
            PhotoEditField.DURATION -> Triple(
                getString(R.string.share_edit_duration),
                photoEdits.durationText, defaults.duration
            )
            PhotoEditField.PACE -> Triple(
                getString(R.string.share_edit_pace) + "（${defaults.paceLabel}）",
                photoEdits.paceText, defaults.pace
            )
            PhotoEditField.ELEVATION -> Triple(
                getString(R.string.share_edit_elevation),
                photoEdits.elevationText, defaults.elevation
            )
        }
        val input = android.widget.EditText(requireContext()).apply {
            setText(current ?: defaultValue)
            setSelectAllOnFocus(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyTextEdit(field, input.text.toString().trim().ifEmpty { null })
            }
            .setNeutralButton(R.string.share_edit_reset) { _, _ ->
                applyTextEdit(field, null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyTextEdit(field: PhotoEditField, value: String?) {
        when (field) {
            PhotoEditField.DISTANCE -> photoEdits.distanceText = value
            PhotoEditField.DURATION -> photoEdits.durationText = value
            PhotoEditField.PACE -> photoEdits.paceText = value
            PhotoEditField.ELEVATION -> photoEdits.elevationText = value
        }
        binding.photoEditor.invalidate()
    }

    /** 系统选图回调：采样解码（最长边 2160 防 OOM）后绑定编辑器 */
    fun onPhotoPicked(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(uri, 2160) }
            if (!isAdded || bitmap == null) {
                binding.progress.isVisible = false
                return@launch
            }
            photoBitmap = bitmap
            binding.progress.isVisible = false
            selectTemplate(PosterTemplate.PHOTO)
        }
    }

    private fun decodeSampled(uri: Uri, maxEdge: Int): Bitmap? {
        val resolver = requireContext().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    // ---------- 分享 ----------

    private fun shareCurrent() {
        binding.btnShare.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // 照片模板按当前编辑态全尺寸现渲；其余模板用预览缓存
            val bitmap = withContext(Dispatchers.Default) {
                if (currentTemplate == PosterTemplate.PHOTO) {
                    SharePosterRenderer.render(
                        requireContext(), PosterTemplate.PHOTO,
                        pendingPoints ?: return@withContext null, pendingSession,
                        photo = photoBitmap,
                        photoEdits = photoEdits,
                        trackSticker = trackSticker
                    )
                } else {
                    renderCache[currentTemplate]
                }
            } ?: run {
                if (isAdded) binding.btnShare.isEnabled = true
                return@launch
            }
            val uri = withContext(Dispatchers.IO) {
                val dir = File(
                    requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "posters"
                )
                dir.mkdirs()
                val file = File(dir, "runpulse_poster_${System.currentTimeMillis()}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
            }
            if (!isAdded) return@launch
            binding.btnShare.isEnabled = true
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_poster_title)))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理 companion 引用，避免静态持有数据
        if (!requireActivity().isChangingConfigurations) {
            pendingPoints = null
            pendingSession = null
            photoPickLauncher = null
        }
    }
}
