package com.runpulse.app.ui.history

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.runpulse.app.databinding.ActivityHistoryBinding
import com.runpulse.app.ui.base.BaseActivity
import com.runpulse.app.ui.detail.SessionDetailActivity
import kotlinx.coroutines.launch

/**
 * 历史记录页：已结束会话列表（距离/时长/配速或速度），点击进入详情。
 */
class HistoryActivity : BaseActivity<ActivityHistoryBinding>(ActivityHistoryBinding::inflate) {

    private val viewModel: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 列表避开状态栏（上边距）与虚拟键（下内边距）
        fitSystemBars(top = binding.recyclerHistory, bottom = binding.recyclerHistory)

        val adapter = HistoryAdapter { summary ->
            startActivity(
                Intent(this, SessionDetailActivity::class.java)
                    .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, summary.session.sessionId)
            )
        }
        binding.recyclerHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            this.adapter = adapter
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.summaries.collect { adapter.submitList(it) }
            }
        }
    }
}
