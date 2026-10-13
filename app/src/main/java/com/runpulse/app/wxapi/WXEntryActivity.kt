package com.runpulse.app.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.runpulse.app.auth.WeChatAuthManager

/**
 * 微信登录/分享回调 Activity。
 * 注意：必须放在应用包名.wxapi 包下（com.runpulse.app.wxapi），否则无法收到回调。
 */
class WXEntryActivity : Activity(), IWXAPIEventHandler {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 将 intent 交给微信 SDK 处理
        WeChatAuthManager.wxApi?.handleIntent(intent, this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { WeChatAuthManager.wxApi?.handleIntent(it, this) }
    }

    override fun onReq(req: BaseReq?) {
        // 微信发送请求到 App 时的回调（一般不用处理）
    }

    override fun onResp(resp: BaseResp?) {
        when (resp?.errCode) {
            BaseResp.ErrCode.ERR_OK -> {
                // 用户同意授权，获取 code
                val code = (resp as? SendAuth.Resp)?.code
                if (code != null) {
                    WeChatAuthManager.onAuthSuccess(code)
                } else {
                    WeChatAuthManager.onAuthError("获取授权码失败")
                }
            }
            BaseResp.ErrCode.ERR_USER_CANCEL -> {
                WeChatAuthManager.onAuthCancel()
            }
            BaseResp.ErrCode.ERR_AUTH_DENIED -> {
                WeChatAuthManager.onAuthDenied()
            }
            else -> {
                WeChatAuthManager.onAuthError("未知错误: ${resp?.errCode}")
            }
        }
        finish()
    }
}
