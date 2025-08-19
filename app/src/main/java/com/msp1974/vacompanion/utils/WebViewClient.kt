package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.jsinterface.WebAppInterface
import com.msp1974.vacompanion.jsinterface.WebViewJavascriptInterface
import com.msp1974.vacompanion.settings.APPConfig

interface WebClientCallback {
    fun showDiagnosticsPopup(show: Boolean)

    fun finishActivity()
}

open class InternalWebViewClient(val config: APPConfig, val resources: Resources, val callback: WebClientCallback): WebViewClient() {
    val log = Logger()
    private val firebase = FirebaseManager.getInstance()

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val swipe = view.parent as SwipeRefreshLayout
        swipe.isRefreshing = true
    }

    override fun onPageFinished(view: WebView, url: String) {
        val swipe = view.parent as SwipeRefreshLayout
        swipe.isRefreshing = false
        callback.showDiagnosticsPopup(config.diagnosticsEnabled)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        log.e("Webview render process gone: $detail")
        if (detail?.didCrash() == true) {
            val swipe = view.parent as SwipeRefreshLayout
            swipe.removeAllViews()
            view.removeAllViews()
            view.destroy()
            callback.finishActivity()
        }
        firebase.addToCrashLog("Render process gone: ${detail.toString()}")
        firebase.logEvent (FirebaseManager.RENDER_PROCESS_GONE,mapOf("detail" to detail.toString()))
        return super.onRenderProcessGone(view, detail)
    }

    private fun getHAUrl(): String {
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

    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        view.loadUrl("about:blank")
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
                setPositiveButton("Ignore") { _: DialogInterface?, _: Int ->
                    handler.proceed()
                    config.ignoreSSLErrors = true
                }
                setNeutralButton("Always Ignore") { _: DialogInterface?, _: Int ->
                    config.ignoreSSLErrors = true
                    config.alwaysIgnoreSSLErrors = true
                    handler.proceed()
                }
                setNegativeButton("Abort") { _: DialogInterface?, _: Int ->
                    super.onReceivedSslError(view, handler, error)
                    view.loadUrl("file:///android_asset/web/error.html")
                }
            }.create().show()
        } else {
            handler.proceed()
        }
    }
}