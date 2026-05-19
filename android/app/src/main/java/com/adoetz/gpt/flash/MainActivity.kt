package com.adoetz.gpt.flash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.adoetz.gpt.flash.service.VoiceSessionService
import com.adoetz.gpt.flash.utils.BackendPreferences
import com.adoetz.gpt.flash.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * MainActivity — hosts the WebView for AdoetzGPT Flash.
 *
 * Flow:
 *   1. On first launch: loads the bundled SvelteKit frontend from Android assets
 *      via WebViewAssetLoader (served as https://appassets.androidplatform.net/).
 *   2. The frontend checks localStorage for a backend URL:
 *      - If not set: shows /setup route for backend URL configuration.
 *      - If set: loads the main Open WebUI interface, with API calls going to the
 *        remote backend URL (set in constants.ts via localStorage).
 *   3. All API calls from the frontend go to the remote backend URL.
 *   4. Static assets (JS, CSS, images) are served locally from the bundled build.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BackendPreferences
    private var webView: WebView? = null
    private var isWebViewLoaded = false

    // Permission launcher
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
            if (!audioGranted) {
                Toast.makeText(this, getString(R.string.mic_permission_required), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = BackendPreferences(this)

        requestPermissions()
        setupWebView(savedInstanceState)
    }

    // ─────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO))
            needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────
    // WebView Setup
    // ─────────────────────────────────────────

    private fun setupWebView(savedInstanceState: Bundle?) {
        webView = binding.webView

        // Create WebViewAssetLoader to serve bundled files from a proper HTTPS origin.
        // This maps https://appassets.androidplatform.net/ → assets/public/
        // Solves: absolute paths, CORS, fetch/XHR, WebSocket from file:// issues.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView?.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                allowFileAccess = true
                allowContentAccess = true
                setMediaPlaybackRequiresUserGesture(false)
                // User agent to identify as Android app
                userAgentString = "$userAgentString AdoetzGPTFlash/1.0 Android"
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = FlashWebViewClient(this@MainActivity, assetLoader)
            webChromeClient = FlashWebChromeClient()

            // Bridge JavaScript interface for native calls
            addJavascriptInterface(
                NativeBridge(this@MainActivity),
                "FlashNative"
            )

            // Restore or load initial URL
            if (savedInstanceState != null) {
                val wvState = savedInstanceState.getBundle(KEY_WEBVIEW_STATE)
                if (wvState != null) {
                    restoreState(wvState)
                    isWebViewLoaded = true
                    return
                }
            }

            // Load the bundled frontend via WebViewAssetLoader (HTTPS origin)
            // This maps to assets/public/index.html
            loadUrl(BUNDLED_URL)
        }
    }

    // ─────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        webView?.pauseTimers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { wv ->
            val wvBundle = Bundle()
            wv.saveState(wvBundle)
            outState.putBundle(KEY_WEBVIEW_STATE, wvBundle)
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val wvState = savedInstanceState.getBundle(KEY_WEBVIEW_STATE)
        if (wvState != null && webView != null) {
            webView!!.restoreState(wvState)
            isWebViewLoaded = true
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }

    // ─────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────

    /** Called from NativeBridge when setup completes and user saves a URL */
    fun onBackendUrlSaved(url: String) {
        lifecycleScope.launch {
            prefs.saveBackendUrl(url)
        }
    }

    /** Called from NativeBridge to clear saved URL (logout/reset) */
    fun onClearBackendUrl() {
        lifecycleScope.launch {
            prefs.clearBackendUrl()
        }
    }

    companion object {
        private const val KEY_WEBVIEW_STATE = "webview_state"
        // WebViewAssetLoader serves assets from:
        //   https://appassets.androidplatform.net/public/index.html
        // The path maps to: android/app/src/main/assets/public/index.html
        const val BUNDLED_URL = "https://appassets.androidplatform.net/public/index.html"
    }
}
