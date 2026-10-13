package com.runpulse.app.auth

import android.content.Context
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 微信登录管理器。
 *
 * 流程：
 * 1. 初始化 IWXAPI 并注册 AppID
 * 2. 发起微信授权登录请求
 * 3. 在 WXEntryActivity 中接收回调获取 code
 * 4. 用 code 调用后端接口换取 openid + access_token
 * 5. 用 openid 调用后端登录接口完成登录
 *
 * 安全提示：AppSecret 不应放在客户端，应通过后端中转换取 openid。
 */
object WeChatAuthManager {

    /** 微信开放平台 AppID（需在微信开放平台申请） */
    private const val WX_APP_ID = "YOUR_WECHAT_APP_ID" // TODO: 替换为实际 AppID

    /** 微信登录 scope */
    private const val WX_SCOPE = "snsapi_userinfo"

    /** 用于防止 CSRF 攻击的随机 state */
    private var currentState: String? = null

    /** 微信 API 实例 */
    var wxApi: IWXAPI? = null
        private set

    /** 登录结果回调 */
    private var loginCallback: ((Result<UserInfo>) -> Unit)? = null

    /**
     * 初始化微信 SDK。
     * 在 Application 或登录页 onCreate 中调用。
     */
    fun init(context: Context) {
        wxApi = WXAPIFactory.createWXAPI(context.applicationContext, WX_APP_ID, true)
        wxApi?.registerApp(WX_APP_ID)
    }

    /**
     * 检查微信是否已安装。
     */
    fun isWeChatInstalled(): Boolean = wxApi?.isWXAppInstalled == true

    /**
     * 发起微信登录。
     * @param callback 登录结果回调
     */
    fun login(callback: (Result<UserInfo>) -> Unit) {
        if (!isWeChatInstalled()) {
            callback(Result.failure(AuthException("请先安装微信客户端")))
            return
        }

        loginCallback = callback
        currentState = UUID.randomUUID().toString()

        val req = SendAuth.Req().apply {
            scope = WX_SCOPE
            state = currentState
        }
        wxApi?.sendReq(req)
    }

    /**
     * 微信授权成功回调（由 WXEntryActivity 调用）。
     */
    internal fun onAuthSuccess(code: String) {
        // 验证 state 防止 CSRF（实际项目中应在后端验证）
        // 用 code 换取 openid + access_token
        // 安全提示：AppSecret 不应放在客户端，应通过后端中转
        simulateBackendLogin(code)
    }

    /**
     * 模拟后端登录流程。
     * 实际项目中应替换为真实 API 调用：
     * POST /api/auth/wechat
     * Body: { "code": "xxx" }
     * Response: { "userId": "xxx", "token": "xxx", "isNewUser": true/false }
     */
    private fun simulateBackendLogin(code: String) {
        // 模拟：生成一个基于 code 的本地用户
        val username = "wx_${code.take(8)}"
        val displayName = "微信用户${code.take(4)}"

        // 在协程中执行登录/注册
        CoroutineScope(Dispatchers.Main).launch {
            // 检查是否已存在该微信用户
            val existingUser = AuthManager.findUserByUsername(username)
            val result = if (existingUser != null) {
                // 已存在，直接登录
                AuthManager.loginWithWeChat(username, displayName)
            } else {
                // 新用户，自动注册
                AuthManager.registerWithWeChat(username, displayName)
            }
            notifyLoginResult(result)
        }
    }

    internal fun onAuthCancel() {
        loginCallback?.invoke(Result.failure(AuthException("用户取消登录")))
        loginCallback = null
    }

    internal fun onAuthDenied() {
        loginCallback?.invoke(Result.failure(AuthException("用户拒绝授权")))
        loginCallback = null
    }

    internal fun onAuthError(message: String) {
        loginCallback?.invoke(Result.failure(AuthException("微信登录失败: $message")))
        loginCallback = null
    }

    /**
     * 通知登录结果（由 AuthManager 调用）。
     */
    internal fun notifyLoginResult(result: Result<UserInfo>) {
        loginCallback?.invoke(result)
        loginCallback = null
    }
}
