package com.runpulse.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lxj.xpopup.XPopup
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.databinding.ActivityMainBinding
import com.runpulse.app.debug.GpxImporter
import com.runpulse.app.stats.TrackStats
import com.runpulse.app.tracking.SportType
import com.runpulse.app.tracking.TrackingService
import com.runpulse.app.ui.base.ActivityStack
import com.runpulse.app.ui.base.BaseActivity
import com.runpulse.app.ui.history.HistoryActivity
import com.runpulse.app.ui.map.TrackMapFactory
import com.runpulse.app.ui.map.TrackMapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 极简首页：三个运动类型开始按钮 + 结束按钮 + 状态/距离/时长展示 + 防杀引导入口。
 * UI 刻意从简，1.0 重点是采集链路的可靠性而非界面。
 */
class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private val viewModel: MainViewModel by viewModels()

    /** 实时轨迹预览：由工厂按 token 配置决定 Mapbox 或 Canvas 实现 */
    private lateinit var trackPreview: TrackMapView

    // 时长秒表：仅在有活跃会话时每秒刷新一次
    private val tickerHandler = Handler(Looper.getMainLooper())
    private var sessionStartTime: Long = 0L
    private val ticker = object : Runnable {
        override fun run() {
            updateDurationText()
            tickerHandler.postDelayed(this, 1000L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!fineGranted) {
                Toast.makeText(this, "缺少定位权限，无法记录轨迹", Toast.LENGTH_LONG).show()
            } else {
                // 权限刚授予，补一次当前位置上图
                showMyLocationOnPreview()
            }
        }

    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fitSystemBars(top = binding.topStatusCard, bottom = binding.bottomPanel)
        setupTrackPreview()
        setupButtons()
        setupBackPressed()
        requestRuntimePermissions()
        showMyLocationOnPreview()
        observeState()
    }

    /** 地图背景页：状态栏/导航栏用白色图标 */
    override fun statusBarDarkFont(): Boolean = false

    // ---------- 返回键：双击退出，运动中先弹确认 ----------

    private var lastBackPressTime = 0L

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.activeSession.value != null) {
                    // 运动中：弹窗确认，避免误触（退出不中断前台 Service 记录）
                    showExitWhileTrackingDialog()
                } else if (System.currentTimeMillis() - lastBackPressTime < DOUBLE_BACK_INTERVAL_MS) {
                    ActivityStack.finishAll()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun showExitWhileTrackingDialog() {
        XPopup.Builder(this).asConfirm(
            "运动进行中",
            "退出 App 不会中断轨迹记录，可在前台通知随时回来查看。",
            "继续运动", "退出 App",
            { ActivityStack.finishAll() }, null, false
        ).show()
    }

    private fun setupTrackPreview() {
        trackPreview = TrackMapFactory.create(this)
        binding.trackPreviewContainer.addView(
            trackPreview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setupButtons() {
        binding.btnRun.setOnClickListener { startTracking(SportType.RUN) }
        binding.btnTrail.setOnClickListener { startTracking(SportType.TRAIL) }
        binding.btnBike.setOnClickListener { startTracking(SportType.BIKE) }
        binding.btnStop.setOnClickListener { stopTracking() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        // 调试入口：仅 DEBUG 构建可见，导入 assets 中的 GPX 测试数据
        binding.btnImport.isVisible = BuildConfig.DEBUG
        binding.btnImport.setOnClickListener { importTestData() }
        binding.btnBattery.setOnClickListener { showBatteryOptimizationGuide() }
        // 定位悬浮按钮：平滑回中到当前位置（运动中也可随时点，手动拖过地图后一键回跟）
        binding.btnLocate.setOnClickListener { showMyLocationOnPreview(forceCenter = true) }
    }

    // ---------- 当前位置 ----------

    /**
     * 在预览地图上展示当前位置。
     * 优先用系统缓存的最后位置（秒出），没有再发一次单次定位请求。
     * 坐标为 WGS84 原值，Mapbox 标准底图是未偏移 WGS84 路网，实现内部直接上图（见 TrackMapView.showMyLocation）。
     * @param forceCenter 开始运动时传 true，强制把相机对准起点
     */
    @SuppressLint("MissingPermission") // 调用前已检查权限
    private fun showMyLocationOnPreview(forceCenter: Boolean = false) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return

        val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (last != null) {
            trackPreview.showMyLocation(last.latitude, last.longitude, forceCenter)
            return
        }

        // 无缓存位置（冷启动常见），发一次性定位请求
        val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        try {
            // getCurrentLocation 有两个仅参数包名不同的重载，需显式转型消除歧义
            LocationManagerCompat.getCurrentLocation(
                locationManager, provider,
                null as androidx.core.os.CancellationSignal?,
                ContextCompat.getMainExecutor(this),
                androidx.core.util.Consumer<android.location.Location?> { location ->
                    location?.let { trackPreview.showMyLocation(it.latitude, it.longitude, forceCenter) }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "单次定位请求失败", e)
        }
    }

    // ---------- 权限 ----------

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        // Android 13+ 前台服务通知需要运行时权限
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // ---------- 采集控制 ----------

    private fun startTracking(type: SportType) {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_SPORT_TYPE, type.value)
        }
        ContextCompat.startForegroundService(this, intent)
        // 开始运动时把地图视图定位到起点位置
        showMyLocationOnPreview(forceCenter = true)
    }

    private fun stopTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
    }

    // ---------- 状态观察 ----------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.activeSession.collect { session ->
                        if (session != null) {
                            sessionStartTime = session.startTime
                            binding.tvStatus.text = "运动中（${session.sportType}）"
                            setButtonsEnabled(tracking = true)
                            startTicker()
                        } else {
                            binding.tvStatus.text = "未开始"
                            setButtonsEnabled(tracking = false)
                            stopTicker()
                            binding.tvDuration.text = "时长：00:00:00"
                        }
                    }
                }
                launch {
                    viewModel.activePoints.collect { points ->
                        // 实时轨迹预览 + 距离刷新（统计口径与详情页/服务端一致）
                        trackPreview.setTrack(points)
                        val km = TrackStats.distanceKm(points)
                        binding.tvDistance.text = String.format(Locale.CHINA, "距离：%.2f km", km)
                    }
                }
            }
        }
    }

    private fun setButtonsEnabled(tracking: Boolean) {
        binding.btnRun.isEnabled = !tracking
        binding.btnTrail.isEnabled = !tracking
        binding.btnBike.isEnabled = !tracking
        binding.btnStop.isEnabled = tracking
    }

    private fun startTicker() {
        tickerHandler.removeCallbacks(ticker)
        tickerHandler.post(ticker)
    }

    private fun stopTicker() {
        tickerHandler.removeCallbacks(ticker)
    }

    private fun updateDurationText() {
        val elapsedSec = (System.currentTimeMillis() - sessionStartTime) / 1000
        val h = elapsedSec / 3600
        val m = (elapsedSec % 3600) / 60
        val s = elapsedSec % 60
        binding.tvDuration.text = String.format(Locale.CHINA, "时长：%02d:%02d:%02d", h, m, s)
    }

    // ---------- 调试导入 ----------

    private fun importTestData() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GpxImporter.importAssets(
                    applicationContext,
                    AppDatabase.get(applicationContext).trackingDao()
                )
            }
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 防杀引导 ----------

    /**
     * 先弹说明对话框（合规要求：让用户知情），再跳系统电池优化白名单授权页。
     * TODO(防杀增强): 后续补充各厂商“自启动管理/后台运行”引导页
     *  （小米/华为/OPPO/vivo 等需要跳各自的设置界面，无统一 Intent）。
     */
    private fun showBatteryOptimizationGuide() {
        XPopup.Builder(this).asConfirm(
            "保持运动记录不中断",
            "为避免运动过程中系统冻结应用导致轨迹断点，建议将本应用加入电池优化白名单。\n\n" +
                "下一步将跳转系统设置页，请选择「允许/不优化」。",
            "暂不", "去设置",
            { requestIgnoreBatteryOptimizations() }, null, false
        ).show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置页，请手动在系统设置中配置", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 地图生命周期转发（Canvas 实现为空方法，转发无开销） ----------

    override fun onStart() {
        super.onStart()
        trackPreview.onStart()
    }

    override fun onStop() {
        trackPreview.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        stopTicker()
        trackPreview.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        trackPreview.onLowMemory()
    }

    companion object {
        private const val TAG = "MainActivity"
        /** 双击退出的时间窗口 */
        private const val DOUBLE_BACK_INTERVAL_MS = 2000L
    }
}
