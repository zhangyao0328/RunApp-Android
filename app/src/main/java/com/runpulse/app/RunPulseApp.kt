package com.runpulse.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Process
import com.runpulse.app.auth.AuthManager
import com.runpulse.app.auth.DeviceIdManager
import com.runpulse.app.auth.PhoneAuthManager
import com.runpulse.app.auth.WeChatAuthManager
import com.runpulse.app.diag.DiagLogger
import com.runpulse.app.upload.UploadScheduler

/**
 * Application 入口：
 * 1. 提前创建前台服务通知渠道（Android 8+ 必须）；
 * 2. 注册每 15 分钟一次的周期性兜底上传任务。
 */
class RunPulseApp : Application() {

    companion object {
        const val CHANNEL_TRACKING = "tracking"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 初始化认证管理器（SharedPreferences 读取登录态）
        AuthManager.init(this)
        // 初始化设备 ID（运动记录用户归属）
        DeviceIdManager.init(this)
        // 初始化微信 SDK
        WeChatAuthManager.init(this)
        // 初始化一键登录 SDK
        PhoneAuthManager.init(this)
        // 进程启动打点：运动中若出现非预期的 process_start，说明进程被杀后重启过
        DiagLogger.init(this)
        DiagLogger.log(this, null, "app_process_start", "pid=${Process.myPid()}")
        // 周期任务用 KEEP 策略，重复启动 App 不会产生重复任务
        UploadScheduler.schedulePeriodic(this)
    }

    private fun createNotificationChannel() {
        // minSdk 26，无需做 API 版本判断
        val channel = NotificationChannel(
            CHANNEL_TRACKING,
            getString(R.string.tracking_channel_name),
            // 运动采集中频繁刷新通知，LOW 避免声音/震动打扰
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
