package com.runpulse.app.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.king.app.dialog.AppDialog
import com.king.app.dialog.AppDialogConfig
import com.king.app.updater.AppUpdater
import com.king.app.updater.listener.DownloadListener
import com.runpulse.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新管理器。
 * 启动时检查服务器版本，发现新版本时弹窗提示用户更新。
 */
object AppUpdateManager {

    /** 版本检查结果 */
    data class VersionInfo(
        val hasUpdate: Boolean,
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkSize: Long,
        val updateLog: String,
        val forceUpdate: Boolean
    )

    /**
     * 检查更新（在 Activity onCreate 中调用）。
     * @param activity 当前 Activity
     * @param showToast 无更新时是否显示 Toast
     */
    suspend fun checkUpdate(activity: Activity, showToast: Boolean = false) {
        try {
            val info = fetchLatestVersion()
            if (info == null) {
                if (showToast) {
                    Toast.makeText(activity, "检查更新失败", Toast.LENGTH_SHORT).show()
                }
                return
            }

            if (info.hasUpdate) {
                showUpdateDialog(activity, info)
            } else if (showToast) {
                Toast.makeText(activity, "已是最新版本", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (showToast) {
                Toast.makeText(activity, "检查更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 从服务器获取最新版本信息 */
    private suspend fun fetchLatestVersion(): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("${BuildConfig.BASE_URL}/api/v1/app/version?currentVersionCode=${BuildConfig.VERSION_CODE}")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                val obj = JSONObject(json)
                VersionInfo(
                    hasUpdate = obj.optBoolean("hasUpdate", false),
                    versionCode = obj.optInt("versionCode", 0),
                    versionName = obj.optString("versionName", ""),
                    apkUrl = obj.optString("apkUrl", ""),
                    apkSize = obj.optLong("apkSize", 0),
                    updateLog = obj.optString("updateLog", ""),
                    forceUpdate = obj.optBoolean("forceUpdate", false)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 显示更新对话框 */
    private fun showUpdateDialog(activity: Activity, info: VersionInfo) {
        val sizeText = if (info.apkSize > 0) {
            " (${info.apkSize / 1024 / 1024} MB)"
        } else ""

        val content = buildString {
            append("新版本：v${info.versionName}$sizeText\n\n")
            append(info.updateLog)
        }

        val config = AppDialogConfig(activity)
            .setTitle("发现新版本")
            .setConfirm("立即更新")
            .setContent(content)
            .setOnClickConfirm {
                startDownload(activity, info)
                AppDialog.dismissDialog()
            }

        // 非强制更新时显示取消按钮
        if (!info.forceUpdate) {
            config.setCancel("稍后再说")
                .setOnClickCancel {
                    AppDialog.dismissDialog()
                }
        }

        AppDialog.showDialog(config)
    }

    /** 开始下载 APK */
    private fun startDownload(activity: Activity, info: VersionInfo) {
        // 检查安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "请允许安装未知来源应用", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        val appUpdater = AppUpdater.Builder(activity)
            .setUrl(info.apkUrl)
            .setFilename("runpulse-v${info.versionName}.apk")
            .setVersionCode(info.versionCode)
            .setShowNotification(true)
            .setShowPercentage(true)
            .setInstallApk(true)
            .setDownloadListener(object : DownloadListener {
                override fun onStart(url: String) {
                    // 开始下载
                }

                override fun onProgress(progress: Long, total: Long) {
                    // 下载进度
                }

                override fun onSuccess(file: File) {
                    Toast.makeText(activity, "下载完成，准备安装", Toast.LENGTH_SHORT).show()
                }

                override fun onError(cause: Throwable) {
                    Toast.makeText(activity, "下载失败: ${cause.message}", Toast.LENGTH_LONG).show()
                }

                override fun onCancel() {
                    // 取消下载
                }
            })
            .build()

        appUpdater.start()
    }
}
