package com.runpulse.app.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 上传任务调度器。双保险策略：
 * 1. 每 15 分钟周期任务兜底（WorkManager 最小周期）；
 * 2. TrackingService 内“攒够 50 点或距上次触发满 3 分钟”时触发一次性任务。
 * 两条路径都指向同一个 [UploadWorker]，Worker 内部按数据库状态幂等上传，重复触发无副作用。
 */
object UploadScheduler {

    private const val PERIODIC_WORK_NAME = "upload_periodic"
    private const val ONE_TIME_WORK_NAME = "upload_one_time"

    private val networkConstraint: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /** 在 Application.onCreate 注册一次即可，KEEP 策略保证不重复入队 */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Service 攒批触发。KEEP：若已有排队中的一次性任务就不重复排队 */
    fun triggerNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
