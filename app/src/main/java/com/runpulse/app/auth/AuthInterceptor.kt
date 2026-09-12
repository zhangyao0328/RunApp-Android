package com.runpulse.app.auth

import com.runpulse.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp 认证拦截器：
 * - 保留现有 X-API-Key 静态头（服务端 API 级认证）
 * - 登录后自动附加 Authorization: Bearer <token>（用户级认证）
 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("X-API-Key", BuildConfig.API_KEY)

        AuthManager.getToken()?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(builder.build())
    }
}
