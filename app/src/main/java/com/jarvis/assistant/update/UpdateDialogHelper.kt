package com.jarvis.assistant.update

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import androidx.fragment.app.FragmentActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.update.UpdateInfo
import com.jarvis.assistant.databinding.DialogUpdateAvailableBinding

/**
 * Helper to display the peach/rose themed in-app update popup dialog.
 */
object UpdateDialogHelper {

    fun showUpdatePopup(
        activity: FragmentActivity,
        updateInfo: UpdateInfo,
        onDownloadRequested: () -> Unit = {
            val bottomSheet = UpdateDownloadBottomSheet(updateInfo)
            bottomSheet.show(activity.supportFragmentManager, "UpdateDownloadBottomSheet")
        }
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val binding = DialogUpdateAvailableBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        binding.tvUpdateTitle.text = "New update available"
        binding.tvUpdateVersion.text = "Version ${updateInfo.versionName} (Build ${updateInfo.versionCode})"
        binding.tvUpdateChangelog.text = updateInfo.changelog.ifBlank {
            "• Performance improvements & neural speed optimizations\n• Bug fixes and stability updates"
        }

        if (updateInfo.mandatory) {
            binding.btnUpdateLater.visibility = View.GONE
            binding.tvSkipVersion.visibility = View.GONE
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        } else {
            binding.btnUpdateLater.visibility = View.VISIBLE
            binding.tvSkipVersion.visibility = View.VISIBLE
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
        }

        binding.btnUpdateDownloadNow.setOnClickListener {
            dialog.dismiss()
            onDownloadRequested()
        }

        binding.btnUpdateLater.setOnClickListener {
            dialog.dismiss()
        }

        binding.tvSkipVersion.setOnClickListener {
            JarvisApp.instance.preferences.skippedUpdateVersionCode = updateInfo.versionCode
            dialog.dismiss()
        }

        dialog.show()
    }
}
