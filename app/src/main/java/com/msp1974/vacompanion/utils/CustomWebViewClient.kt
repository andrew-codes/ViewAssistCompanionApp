package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.Configuration
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
import androidx.webkit.WebViewClientCompat
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.jsinterface.ExternalAuthCallback
import com.msp1974.vacompanion.jsinterface.WebAppInterface
import com.msp1974.vacompanion.jsinterface.WebViewJavascriptInterface
import com.msp1974.vacompanion.ui.VAViewModel

open class CustomWebViewClient(viewModel: VAViewModel): WebViewClientCompat(), EventListener  {
    val log = Logger()
    private val firebase = FirebaseManager.getInstance()
    private lateinit var view: WebView
    private val config = viewModel.config!!
    private val resources = viewModel.resources!!

    @SuppressLint("SetJavaScriptEnabled")
    fun initialise(webView: WebView) {
        view = webView
        // Add javascript interface for view assist
        view.addJavascriptInterface(WebAppInterface(config.uuid), "ViewAssistApp")
        // Add JS interface for HA external auth support
        view.addJavascriptInterface(
            WebViewJavascriptInterface(externalAuthCallback),
            "externalApp"
        )

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            mediaPlaybackRequiresUserGesture = false
            safeBrowsingEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        val nightModeFlag = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlag == Configuration.UI_MODE_NIGHT_YES) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                   view.settings,
                    WebSettingsCompat.FORCE_DARK_ON
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    view.settings,
                    DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                )
            }
        }

        config.eventBroadcaster.addListener(this)
        view.removeAllViews()
    }

    fun setDarkMode(isDark: Boolean) {
        runOnUiThread {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    view.settings,
                    if (isDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
        }
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        log.e("Webview render process gone: $detail")
        if (detail?.didCrash() == true) {
            //val swipe = view.parent as SwipeRefreshLayout
            //swipe.removeAllViews()
            //view.removeAllViews()
            //view.destroy()
            //callback.finishActivity()
        }
        firebase.addToCrashLog("Render process gone: ${detail.toString()}")
        firebase.logEvent (FirebaseManager.RENDER_PROCESS_GONE,mapOf("detail" to detail.toString()))
        return super.onRenderProcessGone(view, detail)
    }

    fun getHAUrl(): String {
        if (config.homeAssistantURL == "") {
            return "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
        }
        return config.homeAssistantURL
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        // If the url is our client id then capture the auth code and get an access token
        if (url.contains(AuthUtils.CLIENT_URL)) {
            val authCode = AuthUtils.getReturnAuthCode(url)
            if (authCode != "") {
                // Get access token using auth token
                val auth = AuthUtils.authoriseWithAuthCode(getHAUrl(), authCode, !config.ignoreSSLErrors)
                if (auth.accessToken == "") {
                    // Not authorised.  Send back to login screen
                    view.loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
                } else {
                    // Authorised. Load HA default dashboard
                    config.accessToken = auth.accessToken
                    config.refreshToken = auth.refreshToken
                    config.tokenExpiry = auth.expires
                    view.loadUrl(AuthUtils.getURL(getHAUrl()))
                }
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        view.loadUrl("file:///android_asset/web/error.html")
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        log.e("SSL Error: $error")
        if (!config.ignoreSSLErrors) {
            var message = resources.getString(R.string.dialog_message_ssl_generic)
            when (error.primaryError) {
                SslError.SSL_UNTRUSTED -> message =
                    resources.getString(R.string.dialog_message_ssl_untrusted)
                SslError.SSL_EXPIRED -> message =
                    resources.getString(R.string.dialog_message_ssl_expired)
                SslError.SSL_IDMISMATCH -> message =
                    resources.getString(R.string.dialog_message_ssl_mismatch)
                SslError.SSL_NOTYETVALID -> message =
                    resources.getString(R.string.dialog_message_ssl_not_yet_valid)
            }
            message += resources.getString(R.string.dialog_message_ssl_continue)
            val alertDialog = AlertDialog.Builder(view.context)
            alertDialog.apply {
                setTitle(resources.getString(R.string.dialog_title_ssl_error))
                setMessage(message)
                setPositiveButton(resources.getString(R.string.dialog_button_yes)) { _: DialogInterface?, _: Int ->
                    handler.proceed()
                    config.ignoreSSLErrors = true
                }
                setNeutralButton(resources.getString(R.string.dialog_button_always_yes)) { _: DialogInterface?, _: Int ->
                    config.ignoreSSLErrors = true
                    config.alwaysIgnoreSSLErrors = true
                    handler.proceed()
                }
                setNegativeButton(resources.getString(R.string.dialog_button_no)) { _: DialogInterface?, _: Int ->
                    super.onReceivedSslError(view, handler, error)
                    view.loadUrl("file:///android_asset/web/error.html")
                }
            }.create().show()
        } else {
            handler.proceed()
        }
    }

    // Add external auth callback for HA authentication
    val externalAuthCallback = object : ExternalAuthCallback {
        override fun onRequestExternalAuth() {
            log.d("External auth callback in progress...")
            if (config.refreshToken == "") {
                log.d("No refresh token.  Proceeding to login screen")
                loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
                return
            } else if (System.currentTimeMillis() > config.tokenExpiry && config.refreshToken != "") {
                // Need to get new access token as it has expired
                log.d("Auth token has expired.  Requesting new token using refresh token")
                val success: Boolean = reAuthWithRefreshToken()
                if (success) {
                    log.d("Authorising with new token")
                    callAuthJS()
                } else {
                    log.d("Failed to refresh auth token.  Proceeding to login screen")
                    loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
                }
            } else if (config.accessToken != "") {
                log.d("Auth token is still valid - authorising")
                callAuthJS()
            }
        }

        override fun onRequestRevokeExternalAuth() {
            log.d("External auth revoke callback in progress...")
            config.accessToken = ""
            config.refreshToken = ""
            config.tokenExpiry = 0
            loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
        }

        private fun loadUrl(url: String) {
            Handler(Looper.getMainLooper()).post({
                view.loadUrl(url)
            })
        }

        private fun callAuthJS() {
            Handler(Looper.getMainLooper()).post({
                view.evaluateJavascript(
                    "window.externalAuthSetToken(true, {\n" +
                            "\"access_token\": \"${config.accessToken}\",\n" +
                            "\"expires_in\": 1800\n" +
                            "});",
                    null
                )
            })
        }

        private fun reAuthWithRefreshToken(): Boolean {
            log.d("Auth token has expired.  Requesting new token using refresh token")
            val auth = AuthUtils.refreshAccessToken(
                getHAUrl(),
                config.refreshToken,
                !config.ignoreSSLErrors
            )
            if (auth.accessToken != "" && auth.expires > System.currentTimeMillis()) {
                log.d("Received new auth token")
                config.accessToken = auth.accessToken
                config.tokenExpiry = auth.expires
                return true
            } else {
                return false
            }
        }
    }

    fun reload() {
        view.reload()
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "refresh" -> {
                runOnUiThread {
                    reload()
                }
            }
        }
    }

    fun runOnUiThread(func: () -> Unit) {
        Handler(Looper.getMainLooper()).post({
            func()
        })

    }
}