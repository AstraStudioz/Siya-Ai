package com.jarvis.assistant.ui.chat

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.chat.ChatSessionEntity
import com.jarvis.assistant.databinding.ItemChatSessionBinding

class ChatSessionAdapter(
    private val onSessionClick: (ChatSessionEntity) -> Unit,
    private val onOptionsClick: (ChatSessionEntity, View) -> Unit
) : ListAdapter<ChatSessionEntity, ChatSessionAdapter.SessionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemChatSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SessionViewHolder(
        private val binding: ItemChatSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: ChatSessionEntity) {
            binding.tvSessionTitle.text = session.title
            binding.ivPinBadge.visibility = if (session.isPinned) View.VISIBLE else View.GONE

            val relativeTime = DateUtils.getRelativeTimeSpanString(
                session.updatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
            binding.tvSessionTime.text = relativeTime

            binding.root.setOnClickListener {
                onSessionClick(session)
            }

            binding.root.setOnLongClickListener {
                onOptionsClick(session, it)
                true
            }

            binding.btnSessionOptions.setOnClickListener {
                onOptionsClick(session, it)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ChatSessionEntity>() {
        override fun areItemsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean {
            return oldItem == newItem
        }
    }
}
