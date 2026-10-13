package com.runpulse.app.auth

import android.content.Context
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 阿里云号码认证一键登录管理器（占位实现）。
 *
 * 注意：阿里云 SDK 需要从控制台下载 AAR 文件放入 app/libs/ 目录后，
 * 取消注释 build.gradle.kts 中的依赖，并替换此文件为完整实现。
 *
 * 完整实现请参考：https://help.aliyun.com/zh/pnvs/developer-reference/the-android-client-access
 */
object PhoneAuthManager {

    /** 阿里云号码认证方案密钥（需在阿里云控制台申请） */
    private const val AUTH_SECRET = "YOUR_ALIYUN_AUTH_SECRET" // TODO: 替换为实际密钥

    private var loginCallback: ((Result<UserInfo>) -> Unit)? = null

    /**
     * 初始化一键登录 SDK。
     */
    fun init(context: Context) {
        // TODO: 集成阿里云 SDK 后实现
        // authHelper = PhoneNumberAuthHelper.getInstance(context, tokenListener)
        // authHelper?.setAuthSDKInfo(AUTH_SECRET)
        // configAuthUI()
    }

    /**
     * 检查设备是否支持一键登录。
     */
    fun checkEnvAvailable(callback: (Boolean) -> Unit) {
        // TODO: 集成阿里云 SDK 后实现
        // authHelper?.checkEnvAvailable(PhoneNumberAuthHelper.SERVICE_TYPE_LOGIN)
        callback(false) // 未集成 SDK，返回不支持
    }

    /**
     * 发起一键登录。
     * @param context 上下文（建议使用 Activity）
     * @param callback 登录结果回调
     */
    fun login(context: Context, callback: (Result<UserInfo>) -> Unit) {
        // TODO: 集成阿里云 SDK 后实现
        // loginCallback = callback
        // authHelper?.accelerateLoginPage(5000, preLoginListener)
        // authHelper?.getLoginToken(context, 5000)

        // 当前未集成 SDK，直接返回错误
        callback(Result.failure(AuthException("一键登录功能未启用，请先从阿里云控制台下载 SDK")))
    }

    /**
     * 用 token 换取手机号（调用后端接口）。
     * 实际项目中应替换为真实 API 调用：
     * POST /api/auth/phone
     * Body: { "token": "xxx" }
     * Response: { "phone": "138****1234", "userId": "xxx", "token": "xxx", "isNewUser": true/false }
     */
    private fun exchangeTokenForPhone(token: String) {
        // 模拟：生成一个基于 token 的本地用户
        val phone = "138****${token.takeLast(4)}"
        val username = "phone_${token.take(8)}"
        val displayName = "手机用户${token.take(4)}"

        // 在协程中执行登录/注册
        CoroutineScope(Dispatchers.Main).launch {
            // 检查是否已存在该手机用户
            val existingUser = AuthManager.findUserByUsername(username)
            val result = if (existingUser != null) {
                AuthManager.loginWithPhone(username, displayName, phone)
            } else {
                AuthManager.registerWithPhone(username, displayName, phone)
            }
            notifyLoginResult(result)
        }
    }

    /**
     * 退出授权页。
     */
    fun quitLoginPage() {
        // TODO: 集成阿里云 SDK 后实现
        // authHelper?.quitLoginPage()
    }

    /**
     * 通知登录结果（由 AuthManager 调用）。
     */
    internal fun notifyLoginResult(result: Result<UserInfo>) {
        loginCallback?.invoke(result)
        loginCallback = null
    }
}
