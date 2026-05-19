package com.adoetz.gpt.flash

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import androidx.webkit.WebViewAssetLoader

/**
 * Custom WebViewClient for AdoetzGPT Flash.
 *
 * Uses WebViewAssetLoader to serve bundled frontend assets from a proper
 * HTTPS origin (https://appassets.androidplatform.net/) instead of file://,
 * which fixes:
 *   - Absolute paths (/static/splash.png, /api/v1/...)
 *   - CORS restrictions
 *   - WebSocket connections to external servers
 *   - fetch/XHR from the frontend
 *
 * External links open in the system browser.
 * Backend API calls go to the remote server configured in localStorage.
 */
class FlashWebViewClient(
    private val activity: MainActivity,
    private val assetLoader: WebViewAssetLoader
) : WebViewClient() {

    // Domains that should stay inside the WebView
    private var backendHost: String? = null

    fun setBackendHost(host: String?) {
        backendHost = host
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        // Let WebViewAssetLoader handle requests to appassets.androidplatform.net
        val intercepted = request?.let { assetLoader.shouldInterceptRequest(it.url) }
        if (intercepted != null) return intercepted
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val scheme = url.scheme ?: return false

        // Only handle http/https
        if (scheme != "http" && scheme != "https") return false

        val host = url.host ?: return false

        // Always allow navigation within the asset loader domain
        if (host == WebViewAssetLoader.DEFAULT_DOMAIN) return false

        val currentHost = backendHost ?: view?.url?.let {
            runCatching { java.net.URL(it).host }.getOrNull()
        }

        // Stay inside WebView if navigating within the backend
        if (currentHost != null && host == currentHost) return false

        // If currently on the bundled page → this is the setup redirect to the backend
        val currentUrl = view?.url ?: ""
        if (currentUrl.contains(WebViewAssetLoader.DEFAULT_DOMAIN)) {
            setBackendHost(host)
            return false
        }

        // External link → open in system browser
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                Uri.parse(url.toString())
            )
            activity.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("FlashWebView", "Could not open external URL: $url")
        }
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        android.util.Log.d("FlashWebView", "Loading: $url")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        android.util.Log.d("FlashWebView", "Loaded: $url")

        // Track backend host for future navigation decisions
        if (url != null &&
            !url.startsWith("file://") &&
            !url.contains(WebViewAssetLoader.DEFAULT_DOMAIN)
        ) {
            try {
                val host = java.net.URL(url).host
                setBackendHost(host)
            } catch (_: Exception) {}
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            android.util.Log.e(
                "FlashWebView",
                "Error loading ${request.url}: ${error?.description} (${error?.errorCode})"
            )
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // For development/self-signed certs: allow.
        // In production builds, you may want to call handler?.cancel() instead.
        android.util.Log.w("FlashWebView", "SSL error: ${error?.primaryError} — proceeding anyway (dev)")
        handler?.proceed()
    }
}
