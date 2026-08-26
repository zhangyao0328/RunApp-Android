package com.runpulse.app.tracking

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.runpulse.app.MainActivity
import com.runpulse.app.R
import com.runpulse.app.RunPulseApp
import com.runpulse.app.api.ApiClient
import com.runpulse.app.api.EndSessionRequest
import com.runpulse.app.api.StartSessionRequest
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.diag.DiagLogger
import com.runpulse.app.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Process
import java.util.UUID

/**
 * 轨迹采集前台 Service —— 本 App 的存活核心。
 *
 * 设计要点：
 * 1. START_STICKY：进程被系统回收后，一旦资源允许会被拉起重启；
 *    重启时 intent 为 null，此时从 Room 中恢复未结束的会话继续采集；
 * 2. 定位用 FusedLocationProviderClient，1s 间隔 + setMaxUpdateDelayMillis(10s) 批量回传，
 *    允许芯片组攒批上报，显著省电；每个回调点【立即写 Room】，不在内存中攒；
 * 3. 攒批触发上传：攒够 50 点或距上次触发满 3 分钟，交给 WorkManager 一次性任务；
 * 4. 会话 start/end 上报云端为 best-effort（失败只记日志），
 *    轨迹点本身由 UploadWorker 兜底保证送达。
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_START = "com.runpulse.app.action.START"
        const val ACTION_STOP = "com.runpulse.app.action.STOP"
        const val EXTRA_SPORT_TYPE = "extra_sport_type"

        private const val NOTIFICATION_ID = 1001

        /** 攒批触发阈值 */
        private const val UPLOAD_TRIGGER_POINTS = 50
        private const val UPLOAD_TRIGGER_INTERVAL_MS = 3 * 60 * 1000L

        /** 相邻两个定位点时间差超过该值视为断流（正常 1s 间隔 + 10s 芯片攒批） */
        private const val LOCATION_GAP_MS = 15_000L

        /** 每收到多少个点记一次进度事件 */
        private const val POINTS_PROGRESS_STEP = 100
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).trackingDao() }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var currentSessionId: String? = null

    // 攒批计数器
    private var pointsSinceTrigger = 0
    private var lastTriggerAt = 0L

    // 诊断：断流检测与进度统计
    private var lastPointTime = 0L
    private var pointCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLogger.init(this)
        DiagLogger.log(this, currentSessionId, "service_create", "pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagLogger.log(this, currentSessionId, "service_cmd", "action=${intent?.action ?: "null(sticky_restart)"}")
        when (intent?.action) {
            ACTION_START -> {
                val sportType = SportType.fromValue(intent.getStringExtra(EXTRA_SPORT_TYPE))
                startSession(sportType)
            }
            ACTION_STOP -> stopSession()
            // START_STICKY 被系统重启时 intent == null：尝试恢复未结束的会话
            null -> resumeActiveSessionIfAny()
        }
        return START_STICKY
    }

    // ---------- 会话生命周期 ----------

    private fun startSession(sportType: SportType) {
        if (currentSessionId != null) {
            Log.w(TAG, "已有进行中的会话，忽略重复开始")
            return
        }
        if (!hasLocationPermission()) {
            Log.e(TAG, "缺少定位权限，无法开始采集")
            stopSelf()
            return
        }

        val session = SessionEntity(
            sessionId = UUID.randomUUID().toString(),
            sportType = sportType.value,
            startTime = System.currentTimeMillis()
        )
        currentSessionId = session.sessionId
        pointsSinceTrigger = 0
        lastTriggerAt = System.currentTimeMillis()

        serviceScope.launch {
            dao.insertSession(session)
            // best-effort 上报云端会话开始；失败不影响本地采集
            try {
                ApiClient.api.startSession(
                    StartSessionRequest(session.sessionId, session.sportType, session.startTime)
                )
            } catch (e: Exception) {
                Log.w(TAG, "上报会话开始失败（不影响本地采集）", e)
            }
        }

        startForegroundWithNotification()
        requestLocationUpdates()
        DiagLogger.log(this, session.sessionId, "session_start", "sport=${session.sportType} pid=${Process.myPid()}")
        Log.d(TAG, "会话开始：${session.sessionId} (${session.sportType})")
    }

    private fun stopSession() {
        val endTime = System.currentTimeMillis()
        val sessionId = currentSessionId
        if (sessionId == null) {
            // 兜底：进程被系统杀掉/覆盖安装后，内存中的会话 id 丢失，
            // 但 Room 里会话还是 isActive=1 —— 此时从本地库恢复再结束，
            // 否则 UI 会一直显示"运动中"且结束按钮怎么点都没反应（实测踩过）
            serviceScope.launch {
                val active = dao.getActiveSession()
                if (active != null) {
                    Log.w(TAG, "内存中无会话，从本地库恢复并结束：${active.sessionId}")
                    DiagLogger.log(this@TrackingService, active.sessionId, "session_stop_recovered")
                    finishSession(active.sessionId, endTime)
                } else {
                    Log.w(TAG, "没有进行中的会话，忽略结束指令")
                    stopSelf()
                }
            }
            return
        }

        fusedClient.removeLocationUpdates(locationCallback)
        currentSessionId = null
        finishSession(sessionId, endTime)
    }

    /** 结束会话的公共收尾：落库 → 上报云端 → 触发尾巴上传 → 停前台服务 */
    private fun finishSession(sessionId: String, endTime: Long) {
        serviceScope.launch {
            dao.endSession(sessionId, endTime)
            // stopSelf() 会立即 cancel serviceScope，收尾上报必须放进 NonCancellable，
            // 否则会出现 "Job was cancelled" 导致云端收不到 end（实测踩过）
            withContext(NonCancellable) {
                try {
                    ApiClient.api.endSession(EndSessionRequest(sessionId, endTime))
                } catch (e: Exception) {
                    Log.w(TAG, "上报会话结束失败", e)
                }
                // 结束前再触发一次上传，尽量把尾巴数据传上去
                UploadScheduler.triggerNow(this@TrackingService)
            }
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        DiagLogger.log(this, sessionId, "session_end", "points=$pointCount")
        Log.d(TAG, "会话结束：$sessionId")
    }

    /** START_STICKY 恢复路径：本地还有未结束会话则继续采集 */
    private fun resumeActiveSessionIfAny() {
        serviceScope.launch {
            val active = dao.getActiveSession()
            if (active != null && hasLocationPermission()) {
                currentSessionId = active.sessionId
                startForegroundWithNotification()
                requestLocationUpdates()
                // 关键事件：出现 resume 说明进程曾被系统杀死（或被覆盖安装），
                // 是“运动中被杀”检测的核心证据
                DiagLogger.log(this@TrackingService, active.sessionId, "session_resume", "pid=${Process.myPid()}")
                Log.d(TAG, "进程重启后恢复会话：${active.sessionId}")
            } else {
                stopSelf()
            }
        }
    }

    // ---------- 定位采集 ----------

    private fun buildLocationRequest(): LocationRequest {
        // TODO(动态采样率): 后续按当前速度/活动状态调整 interval，例如骑行 2s、静止降频到 10s，
        //  通过 removeLocationUpdates + 新的 LocationRequest 切换档位。
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            // 允许定位芯片攒批，最长 10s 一次性回传多个点，省电
            .setMaxUpdateDelayMillis(10_000L)
            .build()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val sessionId = currentSessionId ?: return
            // 批量回传：一次回调可能包含多个点，逐个立即落盘
            for (location in result.locations) {
                // 断流检测：相邻点时间差超阈值记事件（锁屏/被杀/GPS 丢星都会体现为断流）
                if (lastPointTime > 0 && location.time - lastPointTime > LOCATION_GAP_MS) {
                    DiagLogger.log(
                        this@TrackingService, sessionId, "location_gap",
                        "gapMs=${location.time - lastPointTime} acc=${location.accuracy}"
                    )
                }
                lastPointTime = location.time
                pointCount++
                if (pointCount % POINTS_PROGRESS_STEP == 0) {
                    DiagLogger.log(
                        this@TrackingService, sessionId, "points_progress",
                        "count=$pointCount acc=${location.accuracy} speed=${location.speed}"
                    )
                }
                serviceScope.launch {
                    dao.insertPoint(
                        TrackPointEntity(
                            sessionId = sessionId,
                            lat = location.latitude,
                            lng = location.longitude,
                            altitude = location.altitude,
                            speed = location.speed,
                            accuracy = location.accuracy,
                            heartRate = null, // TODO(BLE 心率): 接入心率带后填充
                            timestamp = location.time
                        )
                    )
                    maybeTriggerUpload()
                }
            }
        }
    }

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            fusedClient.requestLocationUpdates(
                buildLocationRequest(),
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "请求定位更新被拒绝", e)
        }
    }

    /** 攒够 50 点或距上次触发满 3 分钟，触发一次一次性上传任务 */
    private fun maybeTriggerUpload() {
        pointsSinceTrigger++
        val now = System.currentTimeMillis()
        if (pointsSinceTrigger >= UPLOAD_TRIGGER_POINTS ||
            now - lastTriggerAt >= UPLOAD_TRIGGER_INTERVAL_MS
        ) {
            pointsSinceTrigger = 0
            lastTriggerAt = now
            UploadScheduler.triggerNow(this)
        }
    }

    // ---------- 前台通知 ----------

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RunPulseApp.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.tracking_notification_title))
            // TODO(UI): 距离/配速占位，后续由 Room Flow 统计后定时刷新通知文本
            .setContentText("距离 -- km · 配速 --'--\"")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    private fun startForegroundWithNotification() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限等场景兜底，避免崩溃
            Log.e(TAG, "startForeground 失败", e)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        DiagLogger.log(this, currentSessionId, "service_destroy", "pid=${Process.myPid()}")
        fusedClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }
}
