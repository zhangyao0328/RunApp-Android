package com.runpulse.app.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.runpulse.app.stats.TrackStats
import com.runpulse.app.tracking.SportType
import com.runpulse.app.ui.base.BaseActivity
import com.runpulse.app.ui.map.TrackMapFactory
import com.runpulse.app.ui.map.TrackMapView
import com.runpulse.app.ui.share.SharePosterDialogFragment
import kotlinx.coroutines.launch
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
                        coachAdapter.submit(messages)
                        binding.tvCoachEmpty.isVisible = messages.isEmpty()
                    }
                }
            }
        }
    }

    private fun bindStats(data: SessionDetailViewModel.TrackData) {
        val points = data.points
        val session = data.session
        binding.tvDetailDistance.text =
            String.format(Locale.CHINA, "%.2f km", TrackStats.distanceKm(points))
        val startMs = session?.startTime ?: points.firstOrNull()?.timestamp ?: 0L
        val endMs = session?.endTime ?: points.lastOrNull()?.timestamp ?: startMs
        binding.tvDetailDuration.text = TrackStats.formatDuration(startMs, endMs)

        // 骑行显示平均速度 km/h，跑步/越野显示配速 min/km
        if (session?.sportType == SportType.BIKE.value) {
            binding.tvDetailPaceLabel.text = getString(R.string.stats_avg_speed)
            binding.tvDetailPace.text =
                String.format(Locale.CHINA, "%.1f km/h", TrackStats.avgSpeedKmh(points))
        } else {
            binding.tvDetailPaceLabel.text = getString(R.string.stats_avg_pace)
            binding.tvDetailPace.text = TrackStats.avgPaceString(points)
        }

        binding.tvDetailElevation.text =
            String.format(Locale.CHINA, "+%.0f m", TrackStats.elevationGainM(points))
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

    /** 教练点评列表 Adapter（数据量小，简单 notify 实现即可） */
    private class CoachMessageAdapter :
        RecyclerView.Adapter<CoachMessageAdapter.MessageViewHolder>() {

        private val items = mutableListOf<CoachMessageEntity>()
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

        fun submit(messages: List<CoachMessageEntity>) {
            items.clear()
            items.addAll(messages)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val binding = ItemCoachMessageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return MessageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val msg = items[position]
            holder.binding.tvMsgTime.text = timeFormat.format(Date(msg.timestamp))
            holder.binding.tvMsgText.text = msg.text
        }

        override fun getItemCount(): Int = items.size

        class MessageViewHolder(
            val binding: ItemCoachMessageBinding
        ) : RecyclerView.ViewHolder(binding.root)
    }
}
