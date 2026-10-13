package com.runpulse.app.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.runpulse.app.R
import com.runpulse.app.data.CoachMessageEntity
import com.runpulse.app.databinding.ActivitySessionDetailBinding
import com.runpulse.app.databinding.ItemCoachMessageBinding
import com.runpulse.app.export.TrackExporter
import com.runpulse.app.stats.TrackStats
import com.runpulse.app.tracking.SportType
import com.runpulse.app.ui.base.BaseActivity
import com.runpulse.app.ui.map.TrackMapFactory
import com.runpulse.app.ui.map.TrackMapView
import com.runpulse.app.ui.share.SharePosterDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话详情页：轨迹地图（Canvas/Mapbox 由工厂决定）+ 统计行 + AI 教练点评列表。
 * 若底层是 MapboxTrackView，需把 Activity 生命周期转发给它。
 */
class SessionDetailActivity :
    BaseActivity<ActivitySessionDetailBinding>(ActivitySessionDetailBinding::inflate) {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    private val viewModel: SessionDetailViewModel by viewModels()
    private var trackMapView: TrackMapView? = null
    private val coachAdapter = CoachMessageAdapter()
    private var shareDialog: SharePosterDialogFragment? = null

    /** 系统 Photo Picker（免存储权限），结果回传给分享弹窗 */
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { shareDialog?.onPhotoPicked(it) }
    }

    /** 保存到手机（SAF CreateDocument，免存储权限，默认下载目录），由系统文件选择器返回目标 uri */
    private val saveExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val src = pendingExportFile
        pendingExportFile = null
        if (uri == null || src == null) {
            binding.btnExport.isEnabled = true
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } != null
                }
            } catch (e: Exception) {
                false
            }
            binding.btnExport.isEnabled = true
            Toast.makeText(
                this@SessionDetailActivity,
                if (ok) R.string.export_track_saved else R.string.export_track_save_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 待写入系统选择器返回 uri 的已生成导出文件 */
    private var pendingExportFile: java.io.File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容避开状态栏（上边距）与虚拟键（下内边距）
        fitSystemBars(top = binding.root, bottom = binding.root)

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: run {
            finish()
            return
        }

        // 轨迹地图：由工厂按 token 配置决定 Canvas 或 Mapbox 实现；
        // 插入 index 0 保证悬浮控制按钮在地图上层
        val mapView = TrackMapFactory.create(this)
        trackMapView = mapView
        binding.mapContainer.addView(
            mapView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 详情页交互（参考 Strava/Keep）：
        // 1. 禁用旋转/倾斜，双指只缩放、单指只平移，避免视角误转歪；
        // 2. 右下悬浮按钮：放大 / 缩小 / 一键框住全程；
        // 3. 地图触摸期间禁止外层 ScrollView 抢手势（MapboxTrackView 内部处理）
        mapView.enableFlatGestures()
        binding.mapControls.isVisible = mapView.supportsMapControls
        binding.btnZoomIn.setOnClickListener { trackMapView?.zoomIn() }
        binding.btnZoomOut.setOnClickListener { trackMapView?.zoomOut() }
        binding.btnFitTrack.setOnClickListener { trackMapView?.fitTrack() }
        binding.btnShare.setOnClickListener { showSharePosterDialog() }
        binding.btnExport.setOnClickListener { showExportTrackDialog(sessionId) }

        binding.recyclerCoach.apply {
            layoutManager = LinearLayoutManager(this@SessionDetailActivity)
            adapter = coachAdapter
        }

        observeState()
        viewModel.load(sessionId)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.track.collect { data ->
                        data ?: return@collect
                        trackMapView?.setTrack(data.points)
                        bindStats(data)
                    }
                }
                launch {
                    viewModel.coachMessages.collect { messages ->
                        coachAdapter.submitList(messages)
                        binding.tvCoachEmpty.isVisible = messages.isEmpty()
                    }
                }
            }
        }
    }

    private fun bindStats(data: SessionDetailViewModel.TrackData) {
        val points = data.points
        val session = data.session
        val sportName = when (session?.sportType) {
            SportType.RUN.value -> getString(R.string.sport_run)
            SportType.TRAIL.value -> getString(R.string.sport_trail)
            SportType.BIKE.value -> getString(R.string.sport_bike)
            else -> getString(R.string.sport_default)
        }
        val headerTime = session?.startTime ?: points.firstOrNull()?.timestamp ?: 0L
        binding.tvDetailHeader.text = "$sportName · ${SimpleDateFormat("yyyy年M月d日  EEEE", Locale.CHINA).format(Date(headerTime))}"
        binding.tvDetailDistance.text =
            String.format(Locale.CHINA, "%.2f km", TrackStats.distanceKm(points))
        val startMs = session?.startTime ?: points.firstOrNull()?.timestamp ?: 0L
        val endMs = session?.endTime ?: points.lastOrNull()?.timestamp ?: startMs
        val totalPausedMs = session?.totalPausedMs ?: 0L
        binding.tvDetailDuration.text = TrackStats.formatNetDuration(startMs, endMs, totalPausedMs)

        // 骑行显示平均速度 km/h，跑步/越野显示配速 min/km
        if (session?.sportType == SportType.BIKE.value) {
            binding.tvDetailPaceLabel.text = getString(R.string.stats_avg_speed)
            binding.tvDetailPace.text =
                String.format(Locale.CHINA, "%.1f km/h", TrackStats.avgSpeedKmh(points, totalPausedMs))
        } else {
            binding.tvDetailPaceLabel.text = getString(R.string.stats_avg_pace)
            binding.tvDetailPace.text = TrackStats.avgPaceString(points, totalPausedMs)
        }

        binding.tvDetailElevation.text =
            getString(R.string.elevation_fmt, TrackStats.elevationGainM(points))

        // 平均步频和步幅（仅跑步时显示）
        if (session?.sportType == SportType.RUN.value) {
            binding.layoutCadence.isVisible = true
            
            // 步频
            val avgCadence = TrackStats.avgCadence(points)
            binding.tvDetailCadence.text = if (avgCadence > 0) {
                getString(R.string.cadence_fmt, avgCadence)
            } else {
                getString(R.string.cadence_idle)
            }
            
            // 步幅
            val avgStride = TrackStats.avgStrideLengthM(points, totalPausedMs)
            binding.tvDetailStride.text = if (avgStride > 0) {
                getString(R.string.stride_fmt, avgStride)
            } else {
                getString(R.string.stride_idle)
            }
        } else {
            binding.layoutCadence.isVisible = false
        }
    }

    /** 导出轨迹：先选格式（GPX / FIT），再选方式（分享 / 保存到手机） */
    private fun showExportTrackDialog(sessionId: String) {
        val formats = arrayOf(
            getString(R.string.export_format_gpx),
            getString(R.string.export_format_fit)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_track_title)
            .setItems(formats) { _, which ->
                val asGpx = which == 0
                showExportDestinationDialog(sessionId, asGpx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 第二步：保留原有系统分享，新增直接保存到手机 */
    private fun showExportDestinationDialog(sessionId: String, asGpx: Boolean) {
        val dests = arrayOf(
            getString(R.string.export_dest_share),
            getString(R.string.export_dest_save)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_dest_title)
            .setItems(dests) { _, which ->
                if (which == 0) exportTrack(sessionId, asGpx) else saveTrack(sessionId, asGpx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 生成文件（IO 线程）→ 弹系统保存对话框（默认下载目录），结果回 saveExportLauncher */
    private fun saveTrack(sessionId: String, asGpx: Boolean) {
        binding.btnExport.isEnabled = false
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    if (asGpx) TrackExporter.exportGpx(this@SessionDetailActivity, sessionId)
                    else TrackExporter.exportFit(this@SessionDetailActivity, sessionId)
                }
            } catch (e: Exception) {
                binding.btnExport.isEnabled = true
                Toast.makeText(
                    this@SessionDetailActivity, R.string.export_track_failed, Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            pendingExportFile = file
            saveExportLauncher.launch(file.name)
        }
    }

    /** 生成文件（IO 线程）→ FileProvider uri → ACTION_SEND 系统分享 */
    private fun exportTrack(sessionId: String, asGpx: Boolean) {
        binding.btnExport.isEnabled = false
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    if (asGpx) TrackExporter.exportGpx(this@SessionDetailActivity, sessionId)
                    else TrackExporter.exportFit(this@SessionDetailActivity, sessionId)
                }
            } catch (e: Exception) {
                binding.btnExport.isEnabled = true
                Toast.makeText(
                    this@SessionDetailActivity, R.string.export_track_failed, Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                this@SessionDetailActivity, "$packageName.fileprovider", file
            )
            binding.btnExport.isEnabled = true
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (asGpx) "application/gpx+xml" else "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_track_title)))
        }
    }

    /** 打开分享海报弹窗：经典/地图/照片三模板实时预览切换 */
    private fun showSharePosterDialog() {
        val data = viewModel.track.value ?: return
        SharePosterDialogFragment.photoPickLauncher = {
            pickPhotoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        shareDialog = SharePosterDialogFragment.newInstance(data.points, data.session)
        shareDialog?.show(supportFragmentManager, "share_poster")
    }

    // ---------- Mapbox 生命周期转发（Canvas 实现为空方法，转发无开销） ----------

    override fun onStart() {
        super.onStart()
        trackMapView?.onStart()
    }

    override fun onStop() {
        trackMapView?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        trackMapView?.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        trackMapView?.onLowMemory()
    }

    /** 教练点评列表 Adapter（ListAdapter + DiffUtil） */
    private class CoachMessageAdapter :
        androidx.recyclerview.widget.ListAdapter<
            CoachMessageEntity, CoachMessageAdapter.MessageViewHolder>(DIFF) {

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

        companion object {
            private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<CoachMessageEntity>() {
                override fun areItemsTheSame(old: CoachMessageEntity, new: CoachMessageEntity) =
                    old.timestamp == new.timestamp && old.text == new.text

                override fun areContentsTheSame(old: CoachMessageEntity, new: CoachMessageEntity) =
                    old == new
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val binding = ItemCoachMessageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return MessageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val msg = getItem(position)
            holder.binding.tvMsgTime.text = timeFormat.format(Date(msg.timestamp))
            holder.binding.tvMsgText.text = msg.text
        }

        class MessageViewHolder(
            val binding: ItemCoachMessageBinding
        ) : RecyclerView.ViewHolder(binding.root)
    }
}
