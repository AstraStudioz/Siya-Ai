package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.BottomSheetModelPickerBinding

class ModelPickerBottomSheet(
    private val currentMode: String,
    private val onModelSelected: (selectedMode: String, displayName: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetModelPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetModelPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Highlight selected
        when (currentMode) {
            "image" -> binding.checkImage.visibility = View.VISIBLE
            "code" -> binding.checkCode.visibility = View.VISIBLE
            "gemini25" -> binding.checkGemini25.visibility = View.VISIBLE
            else -> binding.checkSiya.visibility = View.VISIBLE
        }

        binding.optModelSiya.setOnClickListener {
            onModelSelected("siya", "Siya")
            dismiss()
        }

        binding.optModelImage.setOnClickListener {
            onModelSelected("image", "Image")
            dismiss()
        }

        binding.optModelCode.setOnClickListener {
            onModelSelected("code", "Code")
            dismiss()
        }

        binding.optModelGemini25.setOnClickListener {
            onModelSelected("gemini25", "Gemini 2.5")
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
