package com.runpulse.app.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runpulse.app.databinding.ItemSessionBinding
import com.runpulse.app.tracking.SportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史会话列表 Adapter。
 * 运动类型用 emoji 文字（🏃⛰️🚴）代替图标资源，保持工程无二进制资源。
 */
class HistoryAdapter(
    private val onItemClick: (SessionSummary) -> Unit
) : ListAdapter<SessionSummary, HistoryAdapter.SessionViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SessionSummary>() {
            override fun areItemsTheSame(old: SessionSummary, new: SessionSummary) =
                old.session.sessionId == new.session.sessionId

            override fun areContentsTheSame(old: SessionSummary, new: SessionSummary) = old == new
        }

        private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SessionViewHolder(
        private val binding: ItemSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(summary: SessionSummary) {
            binding.tvEmoji.text = when (summary.session.sportType) {
                SportType.RUN.value -> "🏃"
                SportType.TRAIL.value -> "⛰️"
                SportType.BIKE.value -> "🚴"
                else -> "🏃"
            }
            binding.tvDate.text = DATE_FORMAT.format(Date(summary.session.startTime))
            binding.tvStats.text = String.format(
                Locale.CHINA,
                "%.2f km · %s · %s",
                summary.distanceKm, summary.durationText, summary.paceOrSpeedText
            )
            binding.root.setOnClickListener { onItemClick(summary) }
        }
    }
}
