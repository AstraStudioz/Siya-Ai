package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.chat.ChatSessionEntity
import com.jarvis.assistant.databinding.BottomSheetChatHistoryBinding
import kotlinx.coroutines.launch

class ChatHistoryBottomSheet(
    private val onSessionSelected: (ChatSessionEntity) -> Unit,
    private val onNewSessionRequested: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChatHistoryBinding? = null
    private val binding get() = _binding!!
    private val chatRepository get() = JarvisApp.instance.chatRepository

    private lateinit var sessionAdapter: ChatSessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetChatHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionAdapter = ChatSessionAdapter(
            onSessionClick = { session ->
                onSessionSelected(session)
                dismiss()
            },
            onOptionsClick = { session, anchorView ->
                showSessionOptionsMenu(session, anchorView)
            }
        )

        binding.rvChatSessions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChatSessions.adapter = sessionAdapter

        binding.btnHistoryNewChat.setOnClickListener {
            onNewSessionRequested()
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatRepository.getAllSessionsFlow().collect { sessions ->
                    sessionAdapter.submitList(sessions)
                    binding.tvEmptyHistory.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvChatSessions.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun showSessionOptionsMenu(session: ChatSessionEntity, anchorView: View) {
        val popup = PopupMenu(requireContext(), anchorView)
        val pinTitle = if (session.isPinned) "Unpin Chat" else "Pin Chat"
        popup.menu.add(0, 1, 0, pinTitle)
        popup.menu.add(0, 2, 1, "Rename")
        popup.menu.add(0, 3, 2, "Delete")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    chatRepository.setSessionPinned(session.id, !session.isPinned)
                    true
                }
                2 -> {
                    showRenameDialog(session)
                    true
                }
                3 -> {
                    showDeleteConfirmDialog(session)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(session: ChatSessionEntity) {
        val input = EditText(requireContext()).apply {
            setText(session.title)
            setSelection(session.title.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Rename Chat")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    chatRepository.renameSession(session.id, newTitle)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmDialog(session: ChatSessionEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Conversation?")
            .setMessage("Are you sure you want to delete '${session.title}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                chatRepository.deleteSession(session.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
