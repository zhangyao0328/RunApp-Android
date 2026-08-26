package com.runpulse.app.api

import com.runpulse.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 单例。
 * - baseUrl / API Key 来自 BuildConfig（由 local.properties 注入，见 app/build.gradle.kts）；
 * - X-API-Key 用拦截器统一加头，业务调用处无需关心。
 */
object ApiClient {

    val api: RunPulseApi by lazy { buildRetrofit().create(RunPulseApi::class.java) }

    private fun buildRetrofit(): Retrofit {
        val authInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("X-API-Key", BuildConfig.API_KEY)
                .build()
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            // 注意：Release 版本发布前请降级为 NONE，避免泄露轨迹数据
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
