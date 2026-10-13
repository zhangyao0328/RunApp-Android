package com.runpulse.app.auth

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 设备 ID 管理（单例）：App 首次启动生成 UUID 并持久化，卸载即销毁。
 * 所有上报请求由 AuthInterceptor 自动带 X-Device-Id 头，
 * 服务端据此为运动记录归属运动用户（rp_app_users）。
 */
object DeviceIdManager {

    private const val PREFS_NAME = "device_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }
}
