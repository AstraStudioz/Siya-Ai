package com.jarvis.assistant.ui.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.chat.IntentRouter
import com.jarvis.assistant.data.model.ChatAttachment
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.databinding.ItemAttachmentPreviewBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import com.jarvis.assistant.util.AttachmentProcessor
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val chatRepository get() = JarvisApp.instance.chatRepository

    private val stagedAttachments = mutableListOf<ChatAttachment>()
    private var cameraTempFile: File? = null
    private var cameraTempUri: Uri? = null

    private var isManuallySelectedMode = false
    private var previousAutoSwitchedMode: String? = null
    private var bannerDismissRunnable: Runnable? = null

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        },
        onRetry = { turn ->
            viewModel.sendChatMessage(
                text = turn.userTranscript,
                attachments = turn.attachments ?: emptyList(),
                onStartLiveSession = {
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                }
            )
        },
        onContinue = {
            viewModel.sendChatMessage(
                text = "continue",
                attachments = emptyList(),
                onStartLiveSession = {
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                }
            )
        }
    )

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraTempUri != null) {
            val processed = AttachmentProcessor.processImageUri(requireContext(), cameraTempUri!!)
            if (processed != null) {
                addAttachmentIfValid(processed)
            }
        }
        try { cameraTempFile?.delete() } catch (_: Exception) {}
        cameraTempFile = null
        cameraTempUri = null
    }

    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                val processed = AttachmentProcessor.processImageUri(requireContext(), uri)
                if (processed != null) {
                    if (!addAttachmentIfValid(processed)) {
                        break
                    }
                }
            }
        }
    }

    private val openMultipleDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                val (name, size) = AttachmentProcessor.getFileNameAndSize(requireContext(), uri)
                val mime = requireContext().contentResolver.getType(uri)
                val valResult = AttachmentProcessor.validateAttachment(name, mime, size)
                if (valResult is AttachmentProcessor.ValidationResult.Error) {
                    Toast.makeText(requireContext(), valResult.message, Toast.LENGTH_SHORT).show()
                    continue
                }

                val processed = AttachmentProcessor.processFileUri(requireContext(), uri)
                if (processed != null) {
                    if (!addAttachmentIfValid(processed)) {
                        break
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        // Setup Chat List
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        // Initial Model Button Setup
        val initialMode = preferences.chatMode
        updateModelButton(initialMode)

        // Model Picker Bottom Sheet Click
        binding.btnModelPicker.setOnClickListener {
            val bottomSheet = ModelPickerBottomSheet(preferences.chatMode) { selectedMode, _ ->
                isManuallySelectedMode = true
                dismissAutoSwitchBanner()
                updateModelButton(selectedMode)
            }
            bottomSheet.show(childFragmentManager, "ModelPickerBottomSheet")
        }

        // Auto-switch undo banner click
        binding.tvAutoSwitchBanner.setOnClickListener {
            val target = previousAutoSwitchedMode ?: "siya"
            isManuallySelectedMode = true
            dismissAutoSwitchBanner()
            updateModelButton(target)
        }

        // Auto-switch intent detection as user types
        binding.etMessageInput.doAfterTextChanged { s ->
            val text = s?.toString().orEmpty()
            if (text.isBlank() || isManuallySelectedMode || !preferences.isAutoSwitchChatModeEnabled) {
                return@doAfterTextChanged
            }
            val hasImage = stagedAttachments.any { it.type == "image" || it.mime.startsWith("image/") }
            val route = IntentRouter.routeLocal(text, preferences.chatMode, hasImage)
            if (route.confidence >= 0.70f && route.suggestedMode != preferences.chatMode) {
                val oldMode = preferences.chatMode
                previousAutoSwitchedMode = oldMode
                updateModelButton(route.suggestedMode)
                showAutoSwitchBanner(oldMode, route.suggestedMode)
            }
        }

        // Attach Button
        binding.btnAttach.setOnClickListener {
            showAttachmentBottomSheet()
        }

        // Send Message
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }

        // Voice Mic Button in Chat
        binding.btnChatMic.setOnClickListener {
            if (!JarvisConversationService.isSessionOn.value) {
                JarvisConversationService.startSession(requireContext())
                if (preferences.isMicMuted) {
                    preferences.isMicMuted = false
                    JarvisConversationService.setMicMuted(false)
                }
                Toast.makeText(requireContext(), "Voice session activated", Toast.LENGTH_SHORT).show()
            } else {
                val newMuted = !preferences.isMicMuted
                preferences.isMicMuted = newMuted
                JarvisConversationService.setMicMuted(newMuted)
                val msg = if (newMuted) "Microphone muted" else "Microphone unmuted (Listening)"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            updateChatMicUI()
        }

        // New Chat "+" Action: Creates new session without destroying old ones
        binding.btnChatNew.setOnClickListener {
            chatRepository.createNewSession("New Chat")
            Toast.makeText(requireContext(), "New conversation started", Toast.LENGTH_SHORT).show()
        }

        // Terminal Button (btnChatTerminal): Opens TermuxActivity
        binding.btnChatTerminal.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.terminal.TermuxActivity::class.java))
        }

        // History Button (btnChatMenu): Opens ChatHistoryBottomSheet
        binding.btnChatMenu.setOnClickListener {
            val historySheet = ChatHistoryBottomSheet(
                onSessionSelected = { session ->
                    chatRepository.selectSession(session.id)
                },
                onNewSessionRequested = {
                    chatRepository.createNewSession("New Chat")
                }
            )
            historySheet.show(childFragmentManager, "ChatHistoryBottomSheet")
        }

        observeChatTurns()
        observeActiveSessionTitle()
        observeRestRunningState()
        observeMicState()
    }

    private fun observeMicState() {
        viewLifecycleOwner.lifecycleScope.launch {
            JarvisConversationService.isSessionOn.collect {
                updateChatMicUI()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isMicMuted.collect {
                updateChatMicUI()
            }
        }
    }

    private fun updateChatMicUI() {
        if (!isAdded) return
        val isOn = JarvisConversationService.isSessionOn.value
        val isMuted = preferences.isMicMuted
        if (!isOn) {
            binding.btnChatMic.setImageResource(R.drawable.ic_mic_jarvis)
            binding.btnChatMic.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
        } else if (isMuted) {
            binding.btnChatMic.setImageResource(R.drawable.ic_mic_mute_jarvis)
            binding.btnChatMic.imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#EF4444")
            )
        } else {
            binding.btnChatMic.setImageResource(R.drawable.ic_mic_jarvis)
            binding.btnChatMic.imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#10B981")
            )
        }
    }

    private fun showAutoSwitchBanner(fromMode: String, toMode: String) {
        val fromLabel = modeDisplayName(fromMode)
        val toLabel = modeDisplayName(toMode)
        binding.tvAutoSwitchBanner.text = "Switched to $toLabel • tap $fromLabel to undo"
        binding.tvAutoSwitchBanner.visibility = View.VISIBLE

        bannerDismissRunnable?.let { binding.tvAutoSwitchBanner.removeCallbacks(it) }
        val runnable = Runnable {
            binding.tvAutoSwitchBanner.visibility = View.GONE
        }
        bannerDismissRunnable = runnable
        binding.tvAutoSwitchBanner.postDelayed(runnable, 2500L)
    }

    private fun dismissAutoSwitchBanner() {
        bannerDismissRunnable?.let { binding.tvAutoSwitchBanner.removeCallbacks(it) }
        bannerDismissRunnable = null
        binding.tvAutoSwitchBanner.visibility = View.GONE
    }

    private fun modeDisplayName(mode: String): String = when (mode) {
        "image" -> "Image"
        "code" -> "Code"
        "gemini25" -> "Gemini 2.5"
        else -> "Siya"
    }

    private fun updateModelButton(mode: String) {
        val resolvedMode = when (mode) {
            "image" -> "image"
            "code" -> "code"
            "gemini25" -> "gemini25"
            else -> "siya"
        }
        preferences.chatMode = resolvedMode

        when (resolvedMode) {
            "image" -> {
                binding.tvCurrentModelName.text = "Image"
                binding.ivCurrentModelIcon.setImageResource(R.drawable.ic_attach_gallery)
                binding.etMessageInput.hint = "Describe the image you want..."
            }
            "code" -> {
                binding.tvCurrentModelName.text = "Code"
                binding.ivCurrentModelIcon.setImageResource(R.drawable.ic_code_chip)
                binding.etMessageInput.hint = "Describe the code you need..."
            }
            "gemini25" -> {
                binding.tvCurrentModelName.text = "Gemini 2.5"
                binding.ivCurrentModelIcon.setImageResource(R.drawable.ic_sparkle)
                binding.etMessageInput.hint = "Ask Gemini 2.5..."
            }
            else -> {
                binding.tvCurrentModelName.text = "Siya"
                binding.ivCurrentModelIcon.setImageResource(R.drawable.ic_nav_chat)
                binding.etMessageInput.hint = "Message Siya"
            }
        }
    }

    private fun showAttachmentBottomSheet() {
        val sheet = AttachmentBottomSheet(
            onCameraClick = { launchCamera() },
            onGalleryClick = { launchGallery() },
            onFilesClick = { launchFiles() }
        )
        sheet.show(childFragmentManager, "AttachmentBottomSheet")
    }

    private fun launchCamera() {
        val mediaDir = AttachmentProcessor.getMediaDir(requireContext())
        val temp = File(mediaDir, "cam_temp_${UUID.randomUUID()}.jpg")
        cameraTempFile = temp
        try {
            val authority = "${requireContext().packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(requireContext(), authority, temp)
            cameraTempUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGallery() {
        pickVisualMediaLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchFiles() {
        openMultipleDocumentsLauncher.launch(
            arrayOf(
                "application/pdf",
                "text/*",
                "application/json",
                "application/xml",
                "application/javascript",
                "*/*"
            )
        )
    }

    private fun addAttachmentIfValid(att: ChatAttachment): Boolean {
        val file = File(att.path)
        val fileSize = if (file.exists()) file.length() else 0L

        val currentTotalBytes = stagedAttachments.sumOf { File(it.path).length() }
        val countValidation = AttachmentProcessor.validateTotalStaged(stagedAttachments.size, currentTotalBytes, fileSize)
        if (countValidation is AttachmentProcessor.ValidationResult.Error) {
            Toast.makeText(requireContext(), countValidation.message, Toast.LENGTH_SHORT).show()
            return false
        }

        stagedAttachments.add(att)
        renderAttachmentPreviews()
        return true
    }

    private fun removeAttachment(att: ChatAttachment) {
        stagedAttachments.remove(att)
        renderAttachmentPreviews()
    }

    private fun renderAttachmentPreviews() {
        binding.layoutAttachmentContainer.removeAllViews()
        if (stagedAttachments.isEmpty()) {
            binding.layoutAttachmentPreview.visibility = View.GONE
            return
        }

        binding.layoutAttachmentPreview.visibility = View.VISIBLE
        for (att in stagedAttachments) {
            val itemBinding = ItemAttachmentPreviewBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.layoutAttachmentContainer,
                false
            )

            itemBinding.tvAttachmentName.text = att.name

            if (att.type == "image" || att.mime.startsWith("image/")) {
                try {
                    val bm = BitmapFactory.decodeFile(att.path)
                    if (bm != null) {
                        itemBinding.ivAttachmentThumb.setImageBitmap(bm)
                    } else {
                        itemBinding.ivAttachmentThumb.setImageResource(R.drawable.ic_attach_gallery)
                    }
                } catch (_: Exception) {
                    itemBinding.ivAttachmentThumb.setImageResource(R.drawable.ic_attach_gallery)
                }
            } else {
                itemBinding.ivAttachmentThumb.setImageResource(R.drawable.ic_attach_file)
            }

            itemBinding.btnRemoveAttachment.setOnClickListener {
                removeAttachment(att)
            }

            binding.layoutAttachmentContainer.addView(itemBinding.root)
        }
    }

    private fun sendMessage() {
        val rawText = binding.etMessageInput.text.toString().trim()
        if (rawText.isEmpty() && stagedAttachments.isEmpty()) return

        val currentMode = preferences.chatMode
        val attachmentsCopy = stagedAttachments.toList()

        stagedAttachments.clear()
        renderAttachmentPreviews()
        binding.etMessageInput.text.clear()
        isManuallySelectedMode = false

        if (currentMode == "image") {
            viewModel.generateImageMessage(
                prompt = rawText,
                aspectRatio = "1:1",
                stylePreset = "None",
                negativePrompt = ""
            )
        } else {
            viewModel.sendChatMessage(
                text = rawText,
                attachments = attachmentsCopy,
                onStartLiveSession = {
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                }
            )
        }
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) {
                            binding.rvChatMessages.post {
                                binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeActiveSessionTitle() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatRepository.activeSessionTitle.collect { title ->
                    binding.tvChatTitle.text = title.ifEmpty { "Hi" }
                }
            }
        }
    }

    private fun observeRestRunningState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isRestRunning.collect { isRunning ->
                    binding.btnSendMessage.isEnabled = !isRunning
                    binding.btnSendMessage.alpha = if (isRunning) 0.5f else 1.0f
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bannerDismissRunnable?.let { binding.tvAutoSwitchBanner.removeCallbacks(it) }
        _binding = null
    }
}
