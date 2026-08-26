package com.runpulse.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 首页 ViewModel：从 Room Flow 观察当前活跃会话及其轨迹点，
 * 距离等统计统一走 TrackStats（精度过滤 + 漂移过滤，与服务端口径一致）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).trackingDao()

    val activeSession: StateFlow<SessionEntity?> =
        dao.observeActiveSession()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activePoints: StateFlow<List<TrackPointEntity>> =
        dao.observeActiveSessionPoints()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
