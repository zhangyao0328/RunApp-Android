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
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.runpulse.app.R
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.databinding.DialogSharePosterBinding
import com.runpulse.app.stats.TrackStats
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoFilter
import ja.burhanrashid52.photoeditor.SaveSettings
import ja.burhanrashid52.photoeditor.TextStyleBuilder
import ja.burhanrashid52.photoeditor.shape.ShapeBuilder
import ja.burhanrashid52.photoeditor.shape.ShapeType
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
 * - PHOTO 模板集成 PhotoEditor 库：画笔涂鸦、文字、贴纸/表情、滤镜、撤销重做
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

    // ---- PhotoEditor ----
    private var photoEditor: PhotoEditor? = null
    private var isBrushMode = false
    private var currentBrushColor = 0xFFF97316.toInt() // 能量橙（默认画笔色）
    private var currentBrushSize = 20f

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
            binding.photoEditorView.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }

        // 初始化 PhotoEditor
        setupPhotoEditor()

        binding.btnClose.setOnClickListener { dismiss() }
        setupTab(binding.tabClassic, PosterTemplate.CLASSIC)
        setupTab(binding.tabMap, PosterTemplate.MAP)
        setupTab(binding.tabPhoto, PosterTemplate.PHOTO)

        binding.btnPickPhoto.setOnClickListener { photoPickLauncher?.invoke() }
        binding.btnShare.setOnClickListener { shareCurrent() }

        // 编辑工具栏
        setupEditToolbar()

        selectTemplate(PosterTemplate.CLASSIC)
    }

    // ---------- PhotoEditor 初始化 ----------

    private fun setupPhotoEditor() {
        val textTypeface = ResourcesCompat.getFont(requireContext(), R.font.barlow_medium)
        photoEditor = PhotoEditor.Builder(requireContext(), binding.photoEditorView)
            .setPinchTextScalable(true)
            .setClipSourceImage(true)
            .setDefaultTextTypeface(textTypeface)
            .build()

        // 文本编辑回调：长按已添加的文字时触发
        photoEditor?.setOnPhotoEditorListener(object : ja.burhanrashid52.photoeditor.OnPhotoEditorListener {
            override fun onEditTextChangeListener(rootView: View, text: String, colorCode: Int) {
                showTextEditDialog(rootView, text, colorCode)
            }

            override fun onAddViewListener(viewType: ja.burhanrashid52.photoeditor.ViewType, numberOfAddedViews: Int) {}
            override fun onRemoveViewListener(viewType: ja.burhanrashid52.photoeditor.ViewType, numberOfAddedViews: Int) {}
            override fun onStartViewChangeListener(viewType: ja.burhanrashid52.photoeditor.ViewType) {}
            override fun onStopViewChangeListener(viewType: ja.burhanrashid52.photoeditor.ViewType) {}
            override fun onTouchSourceImage(event: android.view.MotionEvent) {}
        })
    }

    // ---------- 编辑工具栏 ----------

    private fun setupEditToolbar() {
        binding.btnUndo.setOnClickListener {
            photoEditor?.undo()
        }
        binding.btnRedo.setOnClickListener {
            photoEditor?.redo()
        }
        binding.btnBrush.setOnClickListener {
            toggleBrushMode()
        }
        binding.btnEraser.setOnClickListener {
            photoEditor?.brushEraser()
            isBrushMode = true
            updateBrushButtonState()
        }
        binding.btnAddText.setOnClickListener {
            showAddTextDialog()
        }
        binding.btnAddEmoji.setOnClickListener {
            showEmojiPicker()
        }
        binding.btnAddSticker.setOnClickListener {
            addTrackSticker()
        }
        binding.btnFilter.setOnClickListener {
            showFilterPicker()
        }
    }

    private fun toggleBrushMode() {
        isBrushMode = !isBrushMode
        if (isBrushMode) {
            val shapeBuilder = ShapeBuilder()
                .withShapeType(ShapeType.Brush)
                .withShapeSize(currentBrushSize)
                .withShapeColor(currentBrushColor)
                .withShapeOpacity(255)
            photoEditor?.setShape(shapeBuilder)
            photoEditor?.setBrushDrawingMode(true)
        } else {
            photoEditor?.setBrushDrawingMode(false)
        }
        updateBrushButtonState()
    }

    private fun updateBrushButtonState() {
        binding.btnBrush.setBackgroundResource(
            if (isBrushMode) R.drawable.bg_poster_tab_selected else R.drawable.bg_poster_tab_normal
        )
        binding.btnBrush.setTextColor(
            if (isBrushMode) requireContext().getColor(R.color.bg_sheet) else requireContext().getColor(R.color.text_primary)
        )
    }

    // ---------- 文字编辑 ----------

    private fun showAddTextDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.share_edit_add_text_hint)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_edit_text)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val styleBuilder = TextStyleBuilder()
                    styleBuilder.withTextColor(requireContext().getColor(R.color.text_primary))
                    ResourcesCompat.getFont(requireContext(), R.font.barlow_medium)?.let {
                        styleBuilder.withTextFont(it)
                    }
                    photoEditor?.addText(text, styleBuilder)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTextEditDialog(rootView: View, currentText: String, currentColor: Int) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(currentText)
            setSelectAllOnFocus(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_edit_text)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    photoEditor?.editText(rootView, text, currentColor)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 表情 ----------

    private fun showEmojiPicker() {
        val emojis = arrayOf(
            "🏃", "🚴", "🏊", "⛰️", "🔥", "💪", "🎯", "⭐",
            "❤️", "🎉", "👟", "🏅", "⚡", "🌟", "💯", "🚀"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_edit_emoji)
            .setItems(emojis) { _, which ->
                photoEditor?.addEmoji(emojis[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 轨迹贴纸 ----------

    private fun addTrackSticker() {
        val sticker = trackSticker
        if (sticker != null) {
            photoEditor?.addImage(sticker)
            return
        }
        val points = pendingPoints ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val generated = withContext(Dispatchers.Default) {
                SharePosterRenderer.makeTrackSticker(TrackStats.filteredPoints(points))
            }
            if (!isAdded) return@launch
            trackSticker = generated
            generated?.let { photoEditor?.addImage(it) }
        }
    }

    // ---------- 滤镜 ----------

    private fun showFilterPicker() {
        val filters = arrayOf(
            getString(R.string.share_edit_filter_none),
            "BRIGHTNESS", "CONTRAST", "CROSS_PROCESS", "DOCUMENTARY",
            "DUE_TONE", "FILL_LIGHT", "FISH_EYE", "FLIP_VERTICAL",
            "GRAIN", "GRAY_SCALE", "LOMISH", "NEGATIVE", "POSTERIZE",
            "SATURATE", "SEPIA", "SHARPEN", "TEMPERATURE", "TINT", "VIGNETTE"
        )
        val filterEnums = arrayOf(
            null,
            PhotoFilter.BRIGHTNESS, PhotoFilter.CONTRAST, PhotoFilter.CROSS_PROCESS,
            PhotoFilter.DOCUMENTARY, PhotoFilter.DUE_TONE, PhotoFilter.FILL_LIGHT,
            PhotoFilter.FISH_EYE, PhotoFilter.FLIP_VERTICAL, PhotoFilter.GRAIN,
            PhotoFilter.GRAY_SCALE, PhotoFilter.LOMISH, PhotoFilter.NEGATIVE,
            PhotoFilter.POSTERIZE, PhotoFilter.SATURATE, PhotoFilter.SEPIA,
            PhotoFilter.SHARPEN, PhotoFilter.TEMPERATURE, PhotoFilter.TINT, PhotoFilter.VIGNETTE
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_edit_filter)
            .setItems(filters) { _, which ->
                val filter = filterEnums[which]
                if (filter != null) {
                    photoEditor?.setFilterEffect(filter)
                } else {
                    photoEditor?.setFilterEffect(PhotoFilter.NONE)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            // 照片模板：PhotoEditor 交互编辑
            binding.ivPreview.isVisible = false
            binding.photoEditorView.isVisible = true
            binding.editToolbar.isVisible = true
            binding.btnAddTrack.isVisible = false
            setupPhotoEditorBase()
            return
        }
        binding.ivPreview.isVisible = true
        binding.photoEditorView.isVisible = false
        binding.editToolbar.isVisible = false
        binding.btnAddTrack.isVisible = false

        val cached = renderCache[template]
        if (cached != null) {
            binding.ivPreview.setImageBitmap(cached)
            return
        }
        renderTemplate(template)
    }

    /** 渲染基础海报并设为 PhotoEditorView 的背景 */
    private fun setupPhotoEditorBase() {
        val points = pendingPoints ?: return
        val session = pendingSession
        photoDefaults = SharePosterRenderer.defaultPhotoTexts(points, session)

        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            // 确保轨迹贴纸已生成
            val sticker = trackSticker ?: withContext(Dispatchers.Default) {
                SharePosterRenderer.makeTrackSticker(TrackStats.filteredPoints(points))
            }.also { trackSticker = it }

            // 渲染基础海报（照片背景 + 数据层 + 轨迹贴纸）
            val baseBitmap = withContext(Dispatchers.Default) {
                SharePosterRenderer.render(
                    requireContext(), PosterTemplate.PHOTO, points, session,
                    photo = photoBitmap,
                    photoEdits = photoEdits,
                    trackSticker = sticker
                )
            }
            if (!isAdded) return@launch
            binding.photoEditorView.source.setImageBitmap(baseBitmap)
            binding.progress.isVisible = false
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
            view.setTextColor(
                if (selected) requireContext().getColor(R.color.bg_sheet) else requireContext().getColor(R.color.text_primary)
            )
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

    // ---------- 照片选择 ----------

    /** 系统选图回调：采样解码（最长边 2160 防 OOM）后刷新编辑器 */
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
            val bitmap = if (currentTemplate == PosterTemplate.PHOTO) {
                // PHOTO 模板：使用 PhotoEditor 保存编辑后的完整图片
                val saveSettings = SaveSettings.Builder()
                    .setTransparencyEnabled(true)
                    .setClearViewsEnabled(true)
                    .setCompressFormat(Bitmap.CompressFormat.PNG)
                    .setCompressQuality(100)
                    .build()
                try {
                    photoEditor?.saveAsBitmap(saveSettings)
                } catch (e: Exception) {
                    null
                }
            } else {
                withContext(Dispatchers.Default) {
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
