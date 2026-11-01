package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.databinding.BottomSheetAttachmentBinding

class AttachmentBottomSheet(
    private val onCameraClick: () -> Unit,
    private val onGalleryClick: () -> Unit,
    private val onFilesClick: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAttachmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAttachmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardAttachCamera.setOnClickListener {
            dismiss()
            onCameraClick()
        }

        binding.cardAttachGallery.setOnClickListener {
            dismiss()
            onGalleryClick()
        }

        binding.cardAttachFiles.setOnClickListener {
            dismiss()
            onFilesClick()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
