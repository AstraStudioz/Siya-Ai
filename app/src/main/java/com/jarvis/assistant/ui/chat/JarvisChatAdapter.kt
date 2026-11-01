package com.jarvis.assistant.ui.chat

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.ChatAttachment
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.DialogCodeExpandBinding
import com.jarvis.assistant.databinding.ItemNairaChatMessageBinding
import com.jarvis.assistant.databinding.ViewCodeCardBinding
import com.jarvis.assistant.util.CodeZipBuilder
import com.jarvis.assistant.util.DownloadSaver
import com.jarvis.assistant.util.MarkdownCodeParser
import com.jarvis.assistant.util.MessageSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class JarvisChatAdapter(
    private val onEditUserMessage: ((String) -> Unit)? = null,
    private val onRetry: ((ChatTurn) -> Unit)? = null,
    private val onContinue: (() -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    private val savedZipsByTurnId = mutableMapOf<String, DownloadSaver.Result.Success>()

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemNairaChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(turn: ChatTurn) {
            val context = binding.root.context

            // User Bubble
            val hasUserText = turn.userTranscript.isNotBlank() && turn.userTranscript != "(Attachment)"
            val attachments = turn.attachments

            if (hasUserText || !attachments.isNullOrEmpty()) {
                binding.layoutUserContainer.visibility = View.VISIBLE

                if (hasUserText) {
                    binding.tvUserMessage.visibility = View.VISIBLE
                    binding.tvUserMessage.text = turn.userTranscript
                } else {
                    binding.tvUserMessage.visibility = View.GONE
                }

                // Attachments preview inside user bubble
                if (!attachments.isNullOrEmpty()) {
                    binding.layoutUserAttachments.visibility = View.VISIBLE
                    binding.layoutUserAttachments.removeAllViews()
                    renderUserAttachments(context, binding.layoutUserAttachments, attachments)
                } else {
                    binding.layoutUserAttachments.visibility = View.GONE
                    binding.layoutUserAttachments.removeAllViews()
                }

                binding.btnUserCopy.setOnClickListener {
                    copyToClipboard(context, "User message", turn.userTranscript)
                }

                binding.btnUserEdit.setOnClickListener {
                    onEditUserMessage?.invoke(turn.userTranscript)
                }
            } else {
                binding.layoutUserContainer.visibility = View.GONE
            }

            // Assistant Bubble
            val assistantResponse = turn.jarvisResponse
            if (assistantResponse.isNotBlank()) {
                binding.layoutAssistantContainer.visibility = View.VISIBLE

                // Parse for code blocks
                val segments = MarkdownCodeParser.parse(assistantResponse)
                val proseTexts = mutableListOf<String>()
                val codeBlocks = mutableListOf<MessageSegment.Code>()

                for (segment in segments) {
                    when (segment) {
                        is MessageSegment.Text -> {
                            val trimmed = segment.text.trim()
                            if (trimmed.isNotEmpty()) {
                                proseTexts.add(trimmed)
                            }
                        }
                        is MessageSegment.Code -> {
                            codeBlocks.add(segment)
                        }
                    }
                }

                val proseCombined = proseTexts.joinToString("\n\n").trim()
                if (proseCombined.isNotEmpty()) {
                    binding.tvAssistantMessage.visibility = View.VISIBLE
                    binding.tvAssistantMessage.text = proseCombined
                } else if (codeBlocks.isEmpty()) {
                    binding.tvAssistantMessage.visibility = View.VISIBLE
                    binding.tvAssistantMessage.text = assistantResponse
                } else {
                    binding.tvAssistantMessage.visibility = View.GONE
                }

                // Render code blocks in layoutAssistantExtras
                binding.layoutAssistantExtras.removeAllViews()
                if (codeBlocks.isNotEmpty()) {
                    val inflater = LayoutInflater.from(context)
                    for (block in codeBlocks) {
                        val cardBinding = ViewCodeCardBinding.inflate(inflater, binding.layoutAssistantExtras, false)
                        val langText = if (block.language.isNotBlank()) block.language else "CODE"
                        cardBinding.tvCodeLanguage.text = langText
                        cardBinding.tvCodeContent.text = block.code

                        if (block.isUnclosed) {
                            cardBinding.tvCodeStatus.visibility = View.VISIBLE
                            cardBinding.tvCodeStatus.text = "Generating…"
                            cardBinding.btnCodeCopy.visibility = View.GONE
                            cardBinding.btnCodeExpand.visibility = View.GONE
                            cardBinding.scrollCodeVertical.post {
                                cardBinding.scrollCodeVertical.fullScroll(View.FOCUS_DOWN)
                            }
                        } else {
                            cardBinding.tvCodeStatus.visibility = View.GONE
                            cardBinding.btnCodeCopy.visibility = View.VISIBLE
                            cardBinding.btnCodeExpand.visibility = View.VISIBLE
                        }

                        // Copy code block
                        cardBinding.btnCodeCopy.setOnClickListener {
                            copyToClipboard(context, "Code block", block.code)
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                            cardBinding.btnCodeCopy.setImageResource(R.drawable.ic_check_small)
                            cardBinding.btnCodeCopy.postDelayed({
                                cardBinding.btnCodeCopy.setImageResource(R.drawable.ic_copy)
                            }, 1500)
                        }

                        // Expand code block dialog
                        cardBinding.btnCodeExpand.setOnClickListener {
                            showCodeExpandDialog(context, langText, block.code)
                        }

                        binding.layoutAssistantExtras.addView(cardBinding.root)
                    }
                }

                // Render generated images in layoutAssistantExtras
                val generatedImages = turn.generatedImages
                if (!generatedImages.isNullOrEmpty()) {
                    for (imgPath in generatedImages) {
                        val file = File(imgPath)
                        if (file.exists()) {
                            val card = CardView(context).apply {
                                radius = 12f * context.resources.displayMetrics.density
                                cardElevation = 0f
                                setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, (8 * context.resources.displayMetrics.density).toInt(), 0, (8 * context.resources.displayMetrics.density).toInt())
                                }
                            }

                            val imageView = ImageView(context).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT
                                )
                                adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                try {
                                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                    setImageBitmap(bmp)
                                } catch (_: Exception) {}

                                setOnClickListener {
                                    showImageFullScreenDialog(context, file.absolutePath)
                                }

                                setOnLongClickListener {
                                    showImageOptionsDialog(context, file)
                                    true
                                }
                            }

                            card.addView(imageView)
                            binding.layoutAssistantExtras.addView(card)
                        }
                    }
                }

                // Action Chips: Retry / Continue
                val hasError = turn.isError == true
                val hasMaxTokens = turn.isMaxTokens == true

                if (hasError || hasMaxTokens) {
                    binding.layoutAssistantActions.visibility = View.VISIBLE
                    if (hasError) {
                        binding.chipRetry.visibility = View.VISIBLE
                        binding.chipRetry.setOnClickListener {
                            onRetry?.invoke(turn)
                        }
                    } else {
                        binding.chipRetry.visibility = View.GONE
                    }

                    if (hasMaxTokens) {
                        binding.chipContinue.visibility = View.VISIBLE
                        binding.chipContinue.setOnClickListener {
                            onContinue?.invoke()
                        }
                    } else {
                        binding.chipContinue.visibility = View.GONE
                    }
                } else {
                    binding.layoutAssistantActions.visibility = View.GONE
                }

                binding.btnAssistantCopy.setOnClickListener {
                    copyToClipboard(context, "Siya Ai response", assistantResponse)
                }

                // Download ZIP button: one per assistant message with code blocks
                if (codeBlocks.isNotEmpty()) {
                    binding.btnDownloadZip.visibility = View.VISIBLE
                    val isStreaming = codeBlocks.any { it.isUnclosed } || assistantResponse.endsWith("Siya Ai is typing...")

                    if (isStreaming) {
                        binding.btnDownloadZip.isEnabled = false
                        binding.btnDownloadZip.alpha = 0.5f
                        binding.tvDownloadZipText.text = context.getString(R.string.chat_btn_download_generating)
                    } else {
                        binding.btnDownloadZip.isEnabled = true
                        binding.btnDownloadZip.alpha = 1.0f
                        binding.tvDownloadZipText.text = context.getString(R.string.chat_btn_download_zip)
                    }

                    val lastSaved = savedZipsByTurnId[turn.id]
                    if (lastSaved != null) {
                        binding.layoutZipSavedInfo.visibility = View.VISIBLE
                        binding.tvZipSavedPath.text = "Saved to ${lastSaved.displayPath}"
                        binding.btnZipOpen.setOnClickListener {
                            openZip(context, lastSaved.uri)
                        }
                        binding.btnZipShare.setOnClickListener {
                            shareZip(context, lastSaved.uri, lastSaved.fileName)
                        }
                    } else {
                        binding.layoutZipSavedInfo.visibility = View.GONE
                    }

                    binding.btnDownloadZip.setOnClickListener {
                        if (isStreaming) return@setOnClickListener

                        binding.btnDownloadZip.isEnabled = false
                        binding.tvDownloadZipText.text = context.getString(R.string.chat_btn_download_saving)

                        val scope = binding.root.findViewTreeLifecycleOwner()?.lifecycleScope
                            ?: CoroutineScope(Dispatchers.Main)

                        scope.launch {
                            try {
                                val freshTime = System.currentTimeMillis()
                                val title = turn.userTranscript.ifBlank { "snippet" }
                                val zipResult = CodeZipBuilder.buildZip(codeBlocks, title = title, timestamp = freshTime)
                                val saveResult = DownloadSaver.saveZip(context, zipResult.zipBytes, zipResult.zipFileName)

                                when (saveResult) {
                                    is DownloadSaver.Result.Success -> {
                                        savedZipsByTurnId[turn.id] = saveResult
                                        binding.layoutZipSavedInfo.visibility = View.VISIBLE
                                        binding.tvZipSavedPath.text = "Saved to ${saveResult.displayPath}"
                                        binding.btnZipOpen.setOnClickListener {
                                            openZip(context, saveResult.uri)
                                        }
                                        binding.btnZipShare.setOnClickListener {
                                            shareZip(context, saveResult.uri, saveResult.fileName)
                                        }
                                        Toast.makeText(context, "Saved to ${saveResult.displayPath}", Toast.LENGTH_SHORT).show()
                                    }
                                    is DownloadSaver.Result.Error -> {
                                        Toast.makeText(context, saveResult.userMessage, Toast.LENGTH_LONG).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Failed to generate ZIP", Toast.LENGTH_SHORT).show()
                            } finally {
                                binding.btnDownloadZip.isEnabled = true
                                binding.tvDownloadZipText.text = context.getString(R.string.chat_btn_download_zip)
                            }
                        }
                    }
                } else {
                    binding.btnDownloadZip.visibility = View.GONE
                    binding.layoutZipSavedInfo.visibility = View.GONE
                }
            } else {
                binding.layoutAssistantContainer.visibility = View.GONE
            }
        }

        private fun openZip(context: Context, uri: Uri) {
            try {
                val intent = DownloadSaver.createOpenIntent(uri)
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "No app found to open ZIP files.", Toast.LENGTH_SHORT).show()
            }
        }

        private fun shareZip(context: Context, uri: Uri, fileName: String) {
            try {
                val intent = DownloadSaver.createShareIntent(uri, fileName)
                context.startActivity(Intent.createChooser(intent, "Share ZIP"))
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to share ZIP file.", Toast.LENGTH_SHORT).show()
            }
        }

        private fun renderUserAttachments(
            context: Context,
            container: LinearLayout,
            attachments: List<ChatAttachment>
        ) {
            val inflater = LayoutInflater.from(context)
            for (att in attachments) {
                if (att.type == "image" || att.mime.startsWith("image/")) {
                    val card = CardView(context).apply {
                        radius = 8f * context.resources.displayMetrics.density
                        cardElevation = 0f
                        setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                        val sizePx = (72 * context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                            setMargins(0, 0, (6 * context.resources.displayMetrics.density).toInt(), 0)
                        }
                    }

                    val imageView = ImageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        val file = File(att.path)
                        if (file.exists()) {
                            try {
                                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                setImageBitmap(bmp)
                            } catch (_: Exception) {
                                setImageResource(R.drawable.ic_attach_gallery)
                            }
                        } else {
                            setImageResource(R.drawable.ic_attach_gallery)
                        }
                    }

                    card.addView(imageView)
                    card.setOnClickListener {
                        showImageFullScreenDialog(context, att.path)
                    }
                    container.addView(card)
                } else {
                    // Document or Code chip
                    val chip = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundResource(R.drawable.bg_jarvis_chip)
                        val padH = (10 * context.resources.displayMetrics.density).toInt()
                        val padV = (6 * context.resources.displayMetrics.density).toInt()
                        setPadding(padH, padV, padH, padV)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, (6 * context.resources.displayMetrics.density).toInt(), 0)
                        }
                        layoutParams = lp
                    }

                    val icon = ImageView(context).apply {
                        val iconSize = (16 * context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                            setMargins(0, 0, (6 * context.resources.displayMetrics.density).toInt(), 0)
                        }
                        setImageResource(R.drawable.ic_attach_file)
                    }

                    val text = TextView(context).apply {
                        text = att.name
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        maxWidth = (140 * context.resources.displayMetrics.density).toInt()
                        setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    }

                    chip.addView(icon)
                    chip.addView(text)
                    container.addView(chip)
                }
            }
        }

        private fun showImageFullScreenDialog(context: Context, imagePath: String) {
            val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val layout = FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            val imageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                val file = File(imagePath)
                if (file.exists()) {
                    try {
                        setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                    } catch (_: Exception) {}
                }
            }

            val closeButton = ImageButton(context).apply {
                val size = (48 * context.resources.displayMetrics.density).toInt()
                val margin = (16 * context.resources.displayMetrics.density).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, margin, margin, 0)
                }
                setBackgroundResource(R.drawable.bg_jarvis_icon_square)
                setImageResource(R.drawable.ic_close)
                setColorFilter(android.graphics.Color.WHITE)
                contentDescription = "Close"
                setOnClickListener { dialog.dismiss() }
            }

            layout.addView(imageView)
            layout.addView(closeButton)
            dialog.setContentView(layout)
            dialog.show()
        }

        private fun showCodeExpandDialog(context: Context, language: String, code: String) {
            val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            val dialogBinding = DialogCodeExpandBinding.inflate(LayoutInflater.from(context))
            dialog.setContentView(dialogBinding.root)

            dialogBinding.tvDialogLanguage.text = language
            dialogBinding.tvDialogCode.text = code

            dialogBinding.btnDialogClose.setOnClickListener {
                dialog.dismiss()
            }

            dialogBinding.btnDialogCopy.setOnClickListener {
                copyToClipboard(context, "Code", code)
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }

            dialog.show()
        }

        private fun showImageOptionsDialog(context: Context, file: File) {
            val options = arrayOf("Save to Gallery", "Share Image")
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Generated Image")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val uri = com.jarvis.assistant.network.ImageGenClient().saveImageToGallery(context, file)
                            if (uri != null) {
                                Toast.makeText(context, "Saved to Gallery (Pictures/SiyaAi)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Could not save to Gallery", Toast.LENGTH_SHORT).show()
                            }
                        }
                        1 -> {
                            val intent = com.jarvis.assistant.network.ImageGenClient().createShareIntent(context, file)
                            context.startActivity(android.content.Intent.createChooser(intent, "Share Image"))
                        }
                    }
                }
                .show()
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
