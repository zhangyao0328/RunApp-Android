package com.runpulse.app.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 云端 API 接口（Go 服务，契约已定稿）。
 * X-API-Key 由 OkHttp 拦截器统一注入，见 [ApiClient]。
 */
interface RunPulseApi {

    @POST("/api/v1/sessions/start")
    suspend fun startSession(@Body body: StartSessionRequest): Response<Unit>

    @POST("/api/v1/points/batch")
    suspend fun uploadPoints(@Body body: BatchPointsRequest): Response<BatchPointsResponse>

    @POST("/api/v1/sessions/end")
    suspend fun endSession(@Body body: EndSessionRequest): Response<Unit>

    @GET("/api/v1/sessions/{sessionId}/analysis")
    suspend fun getAnalysis(
        @Path("sessionId") sessionId: String,
        @Query("since_id") sinceId: Long
    ): Response<AnalysisResponse>

    /** 诊断事件上报，best-effort */
    @POST("/api/v1/events")
    suspend fun postEvents(@Body body: DiagEventsRequest): Response<Unit>
}
