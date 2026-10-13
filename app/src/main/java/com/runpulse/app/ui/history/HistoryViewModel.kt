package com.runpulse.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.stats.TrackStats
import com.runpulse.app.tracking.SportType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

/**
 * 单条历史记录的展示模型：统计值在 ViewModel 里预计算好，Adapter 只做绑定。
 */
data class SessionSummary(
    val session: SessionEntity,
    val distanceKm: Double,
    val durationText: String,
    /** 骑行显示 km/h，跑步/越野显示 min/km 配速 */
    val paceOrSpeedText: String
)

/**
 * 历史列表 ViewModel：观察已结束会话，并逐条预取轨迹点计算统计（距离/时长/配速或速度）。
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).trackingDao()

    val summaries: StateFlow<List<SessionSummary>> = dao.observeFinishedSessions()
        .map { sessions -> sessions.map { toSummary(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun toSummary(session: SessionEntity): SessionSummary {
        val points = dao.getSessionPoints(session.sessionId)
        val distanceKm = TrackStats.distanceKm(points)
        val endMs = session.endTime ?: points.lastOrNull()?.timestamp ?: session.startTime
        val paceOrSpeed = if (session.sportType == SportType.BIKE.value) {
            String.format(Locale.CHINA, "%.1f km/h", TrackStats.avgSpeedKmh(points, session.totalPausedMs))
        } else {
            TrackStats.avgPaceString(points, session.totalPausedMs)
        }
        return SessionSummary(
            session = session,
            distanceKm = distanceKm,
            durationText = TrackStats.formatNetDuration(session.startTime, endMs, session.totalPausedMs),
            paceOrSpeedText = paceOrSpeed
        )
    }
}
