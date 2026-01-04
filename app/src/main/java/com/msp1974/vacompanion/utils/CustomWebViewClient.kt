package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewClientCompat
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import java.net.URL
import timber.log.Timber

class CustomWebViewClient(val viewModel: VAViewModel) : WebViewClientCompat() {
    val log = Logger()
    private val firebase = FirebaseManager.getInstance()
    val config = viewModel.config!!
    private val resources = viewModel.resources!!

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        log.e("Webview render process gone: $detail")
        var reason = FirebaseManager.RENDER_PROCESS_CRASHED
        if (detail?.didCrash() != true) {
            reason = FirebaseManager.RENDER_PROCESS_KILLED
        }
        firebase.logEvent(reason, mapOf("detail" to detail.toString()))
        try {
            view.reload()
        } catch (e: Exception) {
            log.e("Failed to reload webview: $e")
            // Broadcast to activity to handle webview restart
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.WEBVIEW_CRASH)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        // Handle HA authentication URLs when the URL is actually a Home Assistant URL
        if (url.contains(AuthUtils.CLIENT_URL)) {
            log.d("=== OAUTH CALLBACK RECEIVED ===")
            log.d("Callback URL: $url")
            val authCode = AuthUtils.getReturnAuthCode(url)
            log.d(
                    "Auth code extracted: ${if (authCode.isEmpty()) "EMPTY/INVALID" else "EXISTS (${authCode.take(20)}...)"}"
            )
            if (authCode != "") {
                // Get access token using auth token
                log.d("Exchanging auth code for tokens...")
                val auth =
                        AuthUtils.authoriseWithAuthCode(
                                AuthUtils.getHAUrl(config),
                                authCode,
                                !config.ignoreSSLErrors
                        )
                log.d(
                        "Token exchange result - Access token: ${if (auth.accessToken.isEmpty()) "EMPTY" else "EXISTS (${auth.accessToken.take(20)}...)"}"
                )
                log.d(
                        "Token exchange result - Refresh token: ${if (auth.refreshToken.isEmpty()) "EMPTY" else "EXISTS (${auth.refreshToken.take(20)}...)"}"
                )
                log.d("Token exchange result - Expiry: ${auth.expires}")

                if (auth.accessToken == "") {
                    // Not authorised.  Send back to login screen
                    log.e("!!! AUTH FAILED - No access token received !!!")
                    view.loadUrl(AuthUtils.getAuthUrl(AuthUtils.getHAUrl(config)))
                } else {
                    // Authorised. Save tokens and load HA dashboard
                    log.d("Saving tokens to config...")
                    config.accessToken = auth.accessToken
                    config.refreshToken = auth.refreshToken
                    config.tokenExpiry = auth.expires
                    log.d(
                            "Tokens saved - Access: ${config.accessToken.take(20)}..., Refresh: ${config.refreshToken.take(20)}..., Expiry: ${config.tokenExpiry}"
                    )

                    // Load HA with dashboard path
                    val baseUrl = AuthUtils.getHAUrl(config, withDashboardPath = true)
                    val finalUrl = AuthUtils.getURL(baseUrl, true, config)
                    log.d("Loading HA dashboard (with path): $baseUrl -> $finalUrl")
                    log.d("Dashboard path: '${config.homeAssistantDashboard}'")
                    view.loadUrl(finalUrl)
                }
            } else {
                log.e("!!! AUTH CODE VALIDATION FAILED !!!")
            }
            log.d("=== OAUTH CALLBACK COMPLETED ===")
            return true
        }

        // Allow normal navigation for all other URLs
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Timber.d("Page started: $url")
        setPageLoadingState(PageLoadingStage.STARTED)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        Timber.d("Page finished loading: $url")

        // Check if this is an HA page or external URL
        val isHAPage = url?.let { AuthUtils.isHomeAssistantMode(it, config) } ?: false

        if (isHAPage) {
            // For HA pages, the auth callback will handle setting LOADED state
            // Don't set it here to avoid interfering with auth flow
            Timber.d("HA page finished - auth callback will handle LOADED state")
        } else {
            // For non-HA pages (external URL), set LOADED immediately
            Timber.d("Non-HA page finished - setting LOADED state")
            setPageLoadingState(PageLoadingStage.LOADED)
        }

        super.onPageFinished(view, url)
    }

    fun setPageLoadingState(stage: PageLoadingStage) {
        viewModel.setWebViewPageLoadingState(stage)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
    ) {
        Timber.e("WebView error: Code=$errorCode, Description='$description', URL='$failingUrl'")
        log.e("WebView error: Code=$errorCode, Description='$description', URL='$failingUrl'")
        view.loadUrl("file:///android_asset/web/error.html")
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        log.e("SSL Error: $error")
        if (!config.ignoreSSLErrors) {
            var message = resources.getString(R.string.dialog_message_ssl_generic)
            when (error.primaryError) {
                SslError.SSL_UNTRUSTED ->
                        message = resources.getString(R.string.dialog_message_ssl_untrusted)
                SslError.SSL_EXPIRED ->
                        message = resources.getString(R.string.dialog_message_ssl_expired)
                SslError.SSL_IDMISMATCH ->
                        message = resources.getString(R.string.dialog_message_ssl_mismatch)
                SslError.SSL_NOTYETVALID ->
                        message = resources.getString(R.string.dialog_message_ssl_not_yet_valid)
            }
            message += resources.getString(R.string.dialog_message_ssl_continue)
            val alertDialog = AlertDialog.Builder(view.context)
            alertDialog
                    .apply {
                        setTitle(resources.getString(R.string.dialog_title_ssl_error))
                        setMessage(message)
                        setPositiveButton(resources.getString(R.string.dialog_button_yes)) {
                                _: DialogInterface?,
                                _: Int ->
                            handler.proceed()
                            config.ignoreSSLErrors = true
                        }
                        setNeutralButton(resources.getString(R.string.dialog_button_always_yes)) {
                                _: DialogInterface?,
                                _: Int ->
                            config.ignoreSSLErrors = true
                            config.alwaysIgnoreSSLErrors = true
                            handler.proceed()
                        }
                        setNegativeButton(resources.getString(R.string.dialog_button_no)) {
                                _: DialogInterface?,
                                _: Int ->
                            super.onReceivedSslError(view, handler, error)
                            view.loadUrl("file:///android_asset/web/error.html")
                        }
                    }
                    .create()
                    .show()
        } else {
            handler.proceed()
        }
    }

    override fun doUpdateVisitedHistory(
            view: WebView,
            url: String,
            isReload: Boolean,
    ) {
        Timber.d("Updating visited history: $url (isReload: $isReload)")

        // Only update currentPath for Home Assistant URLs, not external websites
        val isHA = AuthUtils.isHomeAssistantMode(url, config)
        Timber.d("URL '$url' is HA mode: $isHA")

        if (isHA) {
            val newPath = URL(url).path
            Timber.d("HA URL - updating path from '${config.currentPath}' to '$newPath'")
            config.currentPath = newPath
        } else {
            Timber.d("Non-HA URL - keeping currentPath unchanged: '${config.currentPath}'")
        }
    }
}
