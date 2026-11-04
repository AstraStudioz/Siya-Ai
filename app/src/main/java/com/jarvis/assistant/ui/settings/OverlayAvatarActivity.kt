package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.BrandConfig
import com.jarvis.assistant.util.ThemeManager

class OverlayAvatarActivity : AppCompatActivity() {
    private lateinit var avatarPreview: WebView
    private lateinit var overlaySwitch: SwitchCompat
    private val preferences get() = JarvisApp.instance.preferences
    private lateinit var assetLoader: WebViewAssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_avatar)
        ThemeManager.applyDynamicBackground(findViewById(R.id.overlayAvatarRoot))

        // Ensure avatar is strictly Siya
        preferences.taskOverlayAvatar = "siya"

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.tvUnifiedFooter)?.setOnClickListener {
            com.jarvis.assistant.util.ExternalLinks.open(this, com.jarvis.assistant.util.BrandConfig.TELEGRAM_CHANNEL_URL)
        }
        overlaySwitch = findViewById(R.id.switchOverlayEnabled)
        overlaySwitch.isChecked = preferences.isTaskOverlayEnabled
        val modeGroup = findViewById<RadioGroup>(R.id.radioOverlayMode)
        modeGroup.check(if (preferences.taskOverlayMode == "working") R.id.rbOverlayWorking else R.id.rbOverlayAlways)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            preferences.taskOverlayMode = if (checkedId == R.id.rbOverlayWorking) "working" else "always"
        }
        val edgeSpinner = findViewById<Spinner>(R.id.spinnerOverlayEdge)
        val edgeValues = listOf("right", "left", "top", "bottom")
        var edgeSelectionReady = false
        edgeSpinner.setSelection(edgeValues.indexOf(preferences.taskOverlayEdge).coerceAtLeast(0))
        edgeSpinner.post { edgeSelectionReady = true }
        modeGroup.isEnabled = preferences.isTaskOverlayEnabled
        edgeSpinner.isEnabled = preferences.isTaskOverlayEnabled
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = preferences.isTaskOverlayEnabled
        edgeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (edgeSelectionReady) edgeValues.getOrNull(position)?.let {
                    preferences.taskOverlayFreePosition = false
                    preferences.taskOverlayEdge = it
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        overlaySwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.isTaskOverlayEnabled = enabled
            modeGroup.isEnabled = enabled
            edgeSpinner.isEnabled = enabled
            for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
        }

        findViewById<TextView>(R.id.tvUnifiedFooter)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BrandConfig.OFFICIAL_WEBSITE_URL)))
            } catch (_: Exception) {}
        }

        avatarPreview = findViewById(R.id.avatarPreview)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        setupPreview()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPreview() {
        avatarPreview.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        avatarPreview.settings.javaScriptEnabled = true
        avatarPreview.settings.domStorageEnabled = true
        avatarPreview.settings.allowFileAccess = false
        avatarPreview.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        avatarPreview.loadUrl("https://appassets.androidplatform.net/assets/overlay-toys/siya.html")
    }

    override fun onDestroy() {
        if (::avatarPreview.isInitialized) {
            avatarPreview.stopLoading()
            avatarPreview.destroy()
        }
        super.onDestroy()
    }
}
