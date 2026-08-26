package com.runpulse.app.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.CoachMessageEntity
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 会话详情页 ViewModel：轨迹点/会话信息一次性加载，教练点评持续观察。
 * 统计计算（TrackStats）仍在 UI 层按需调用，保持纯计算函数无状态。
 */
class SessionDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).trackingDao()

    data class TrackData(
        val session: SessionEntity?,
        val points: List<TrackPointEntity>
    )

    private val _track = MutableStateFlow<TrackData?>(null)
    val track: StateFlow<TrackData?> = _track.asStateFlow()

    private val _coachMessages = MutableStateFlow<List<CoachMessageEntity>>(emptyList())
    val coachMessages: StateFlow<List<CoachMessageEntity>> = _coachMessages.asStateFlow()

    private var loadedSessionId: String? = null

    /** 加载指定会话；重复调用同一会话时忽略（页面旋转重建不重复查库） */
    fun load(sessionId: String) {
        if (loadedSessionId == sessionId) return
        loadedSessionId = sessionId
        viewModelScope.launch(Dispatchers.IO) {
            val session = dao.getSessionById(sessionId)
            val points = dao.getSessionPoints(sessionId)
            _track.value = TrackData(session, points)
        }
        viewModelScope.launch {
            dao.observeCoachMessages(sessionId).collect { _coachMessages.value = it }
        }
    }
}
