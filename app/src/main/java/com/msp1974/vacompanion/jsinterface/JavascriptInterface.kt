package com.msp1974.vacompanion.jsinterface

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger

interface ExternalAuthCallback {
    fun onRequestExternalAuth(view: WebView)
    fun onRequestRevokeExternalAuth(view: WebView)
}

interface ViewAssistCallback {
    fun onEvent(event: String, data: String)
}

/** Instantiate the interface and set the context.  */
class WebAppInterface(val uuid: String, val cbCallback: ViewAssistCallback) {

    @JavascriptInterface
    fun getViewAssistCAUUID(): String {
        return uuid
    }

    @JavascriptInterface
    fun sendEvent(event: String, data: String) {
        cbCallback.onEvent(event, data)
    }
}

class WebViewJavascriptInterface(val view: WebView, val cbCallback: ExternalAuthCallback) {
    val log = Logger()
    @JavascriptInterface
    fun getExternalAuth(payload: String) {
        log.d("HA Requested external auth callback - $payload")
        log.d("About to call onRequestExternalAuth on callback: ${cbCallback::class.simpleName}")
        cbCallback.onRequestExternalAuth(view)
        log.d("Completed onRequestExternalAuth callback")
    }
    @JavascriptInterface
    fun revokeExternalAuth(payload: String) {
        log.d("HA Revoked external auth callback - $payload")
        cbCallback.onRequestRevokeExternalAuth(view)
    }
}
